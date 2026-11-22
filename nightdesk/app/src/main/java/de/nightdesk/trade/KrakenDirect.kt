package de.nightdesk.trade

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Direct API credentials are encrypted with Android Keystore; they never leave the device except for Kraken API authentication. */
internal class KrakenKeys(context: Context) {
    private val prefs = context.getSharedPreferences("nightdesk-kraken-direct", Context.MODE_PRIVATE)
    private val alias = "nightdesk.kraken.direct.v1"
    val configured: Boolean get() = prefs.contains("key") && prefs.contains("secret")
    private fun aes(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        require(data.size > 28) { "Beschädigte Kraken-Zugangsdaten" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }
    fun store(key: String, secret: String) {
        require(key.trim().length in 20..256) { "API-Key ungültig" }
        require(Base64.decode(secret.trim(), Base64.DEFAULT).size >= 32) { "API-Secret ungültig" }
        check(prefs.edit().putString("key", encrypt(key.trim()))
            .putString("secret", encrypt(secret.trim())).commit())
    }
    fun load() = decrypt(checkNotNull(prefs.getString("key", null))) to
        decrypt(checkNotNull(prefs.getString("secret", null)))
    fun lastNonce() = prefs.getLong("nonce", 0L)
    fun saveNonce(value: Long) { check(prefs.edit().putLong("nonce", value).commit()) }
    var uncertainOrder: Boolean
        get() = prefs.getBoolean("uncertain", false)
        set(value) { check(prefs.edit().putBoolean("uncertain", value).commit()) }
    fun forget() { prefs.edit().remove("key").remove("secret").apply() }
}

internal object KrakenSignature {
    fun sign(path: String, nonce: String, form: String, secret: String): String {
        require(path.startsWith("/0/private/"))
        val sha = MessageDigest.getInstance("SHA-256").digest((nonce + form).toByteArray(Charsets.UTF_8))
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(SecretKeySpec(Base64.decode(secret, Base64.DEFAULT), "HmacSHA512"))
        return Base64.encodeToString(hmac.doFinal(path.toByteArray(Charsets.UTF_8) + sha), Base64.NO_WRAP)
    }
}

/** Kraken Spot REST client. Never retries a trading request on timeout. */
internal class KrakenDirect(private val keys: KrakenKeys, http: OkHttpClient) {
    private val client = http.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val counter = AtomicLong(0)
    private val orders = Mutex()
    private fun nonce(): String = synchronized(counter) {
        val next = maxOf(System.currentTimeMillis() * 1000L, counter.get() + 1, keys.lastNonce() + 1)
        counter.set(next)
        keys.saveNonce(next)
        next.toString()
    }
    private suspend fun call(endpoint: String, params: Map<String, String> = emptyMap(),
                             creds: Pair<String, String>? = null): JSONObject = withContext(Dispatchers.IO) {
        require(endpoint.matches(Regex("[A-Za-z]{3,25}")))
        val (key, secret) = creds ?: keys.load()
        val id = nonce()
        val form = FormBody.Builder().add("nonce", id).apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val encoded = Buffer().also { form.writeTo(it) }.readUtf8()
        val path = "/0/private/" + endpoint
        val req = Request.Builder().url("https://api.kraken.com" + path)
            .header("API-Key", key).header("API-Sign", KrakenSignature.sign(path, id, encoded, secret))
            .header("Cache-Control", "no-store").post(form).build()
        val root = client.newCall(req).execute().use { res ->
            check(res.isSuccessful) { "Kraken HTTP " + res.code }
            JSONObject(res.body?.string().orEmpty())
        }
        val errors = root.optJSONArray("error") ?: error("Kraken-Antwort ungültig")
        if (errors.length() > 0) {
            val message = (0 until errors.length()).joinToString("; ") { errors.optString(it) }
            error("Kraken: " + message.take(160))
        }
        root.getJSONObject("result")
    }
    suspend fun connect(key: String, secret: String) {
        require(!keys.uncertainOrder) { "Ungeklärte Order erst in Kraken Pro prüfen" }
        call("Balance", creds = key.trim() to secret.trim())
        keys.store(key, secret)
    }
    suspend fun balances(): JSONObject = call("Balance")
    suspend fun openOrders(): JSONObject = call("OpenOrders").getJSONObject("open")
    suspend fun cancel(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9-]{8,80}")))
        return call("CancelOrder", mapOf("txid" to id))
    }
    suspend fun submitMarket(pair: PairInfo, side: String, qty: java.math.BigDecimal): String = orders.withLock {
        check(BuildConfig.LIVE_ENABLED) { "Für Echtgeld ist eine privat signierte Release-APK erforderlich" }
        check(!keys.uncertainOrder) { "Unklarer Orderstatus: Kraken Pro prüfen" }
        require(side in setOf("buy", "sell") && qty >= pair.minimum &&
            qty.stripTrailingZeros().scale() <= pair.quantityDecimals) { "Ungültige Ordermenge" }
        check(openOrders().length() == 0) { "Offene Kraken-Orders vorhanden" }
        val args = linkedMapOf("pair" to pair.apiId, "type" to side, "ordertype" to "market",
            "volume" to qty.toPlainString())
        call("AddOrder", args + ("validate" to "true"))
        keys.uncertainOrder = true
        val response = call("AddOrder", args)
        val ids = response.optJSONArray("txid")
        check(ids != null && ids.length() == 1) { "Orderstatus unbekannt – Kraken Pro prüfen" }
        keys.uncertainOrder = false
        ids.getString(0)
    }
    fun disconnect() = keys.forget()
}
