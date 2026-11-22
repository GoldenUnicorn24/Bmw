package de.nightdesk.trade

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class BackendCredentials(context: Context) {
    private val storage = context.getSharedPreferences("nightdesk-backend", Context.MODE_PRIVATE)
    private val keyName = "nightdesk.access.v1"
    val address: String get() = storage.getString("url", "").orEmpty()
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(keyName, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyName, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun save(url: String, token: String) {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null && parsed.encodedPath == "/") {
            "HTTPS-Serveradresse ohne Pfad eingeben"
        }
        require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Ungültiges Backend-Token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        check(storage.edit().putString("url", parsed.toString()).putString("token", encrypted).commit())
    }

    fun token(): String {
        val value = checkNotNull(storage.getString("token", null)) { "Backend-Token fehlt" }
        val data = Base64.decode(value, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            doFinal(data.copyOfRange(12, data.size)).decodeToString()
        }
    }

    fun forget() { storage.edit().clear().apply() }
}

private interface TradeApi {
    @GET suspend fun read(@Url path: String, @Header("Authorization") auth: String): Response<ResponseBody>
    @POST suspend fun write(@Url path: String, @Header("Authorization") auth: String,
                            @Body body: okhttp3.RequestBody): Response<ResponseBody>
}

internal class Backend(private val credentials: BackendCredentials, private val http: OkHttpClient) {
    suspend fun call(path: String, payload: JSONObject? = null): JSONObject {
        require(path.startsWith("v1/") && path.none { it == '\\' })
        if (!BuildConfig.LIVE_ENABLED) {
            val paperRead = payload == null && path in setOf("v1/status", "v1/paper/account")
            val paperControl = path == "v1/bot" && payload?.optString("command") in setOf("start_paper", "pause", "stop")
            require(paperRead || paperControl) { "Papier-Testbuild" }
        }
        val retrofit = Retrofit.Builder().baseUrl(credentials.address).client(http).build()
        val api = retrofit.create(TradeApi::class.java)
        val token = "Bearer " + credentials.token()
        val response = if (payload == null) api.read(path, token) else
            api.write(path, token, payload.toString().toRequestBody("application/json".toMediaType()))
        val body = (response.body() ?: response.errorBody())?.use { it.string() }
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        check(response.isSuccessful && json != null) {
            val reason = json?.opt("detail") as? String
            reason ?: "Backend antwortet mit HTTP ${response.code()}"
        }
        return json
    }
}
