package de.nightdesk.trade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/**
 * Read-only Solana/DEX data. No private Kraken credential is used or sent.
 * Token accounts are NOT necessarily distinct human holders; LP locks require
 * additional audited data and are always reported as unknown.
 */
internal data class DexReport(
    val mint: String,
    val poolName: String,
    val dex: String,
    val liquidityUsd: BigDecimal,
    val volume24hUsd: BigDecimal,
    val priceChange24h: Double?,
    val pairCreatedAt: Long?,
    val top10AccountsShare: Double?,
    val mintAuthority: Boolean?,
    val freezeAuthority: Boolean?,
    val buyTransactions: Int,
    val sellTransactions: Int,
    val sources: String = "DEX Screener + Solana RPC"
)

internal fun parseDexReport(mint: String, pairs: JSONArray, account: JSONObject,
                            supply: JSONObject, largest: JSONObject): DexReport {
    val matching = (0 until pairs.length()).map { pairs.getJSONObject(it) }
        .filter { it.optString("chainId") == "solana" && (
            it.optJSONObject("baseToken")?.optString("address") == mint ||
            it.optJSONObject("quoteToken")?.optString("address") == mint) }
    require(matching.isNotEmpty()) { "Kein Solana-DEX-Pool für diese Mint-Adresse gefunden" }
    val pair = matching.maxBy { it.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0 }
    val details = pair.optJSONObject("liquidity") ?: JSONObject()
    val txns = pair.optJSONObject("txns")?.optJSONObject("h24")
    val change = pair.optJSONObject("priceChange")
    val info = account.optJSONObject("result")?.optJSONObject("value")?.optJSONObject("data")
        ?.optJSONObject("parsed")?.optJSONObject("info")
    val mintChecked = info != null && info.has("mintAuthority") && info.has("freezeAuthority")
    val supplyValue = supply.optJSONObject("result")?.optJSONObject("value")
        ?.optString("amount")?.toBigDecimalOrNull()
    val holders = largest.optJSONObject("result")?.optJSONArray("value")
    var total = zero
    if (holders != null) for (i in 0 until minOf(10, holders.length())) {
        total += holders.getJSONObject(i).optString("amount", "0").toBigDecimalOrNull() ?: zero
    }
    val concentration = if (supplyValue != null && supplyValue > zero && holders != null)
        total.divide(supplyValue, precision).toDouble().coerceIn(0.0, 1.0) else null
    val token = pair.optJSONObject("baseToken")?.takeIf { it.optString("address") == mint }
        ?: pair.optJSONObject("quoteToken")
    return DexReport(
        mint = mint,
        poolName = token?.optString("symbol", "?") ?: "?",
        dex = pair.optString("dexId", "?"),
        liquidityUsd = BigDecimal.valueOf(details.optDouble("usd", 0.0).coerceAtLeast(0.0)),
        volume24hUsd = BigDecimal.valueOf(pair.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0),
        priceChange24h = change?.takeIf { it.has("h24") }?.optDouble("h24"),
        pairCreatedAt = pair.optLong("pairCreatedAt").takeIf { it > 0 },
        top10AccountsShare = concentration,
        mintAuthority = if (mintChecked) !info!!.isNull("mintAuthority") else null,
        freezeAuthority = if (mintChecked) !info!!.isNull("freezeAuthority") else null,
        buyTransactions = txns?.optInt("buys") ?: 0,
        sellTransactions = txns?.optInt("sells") ?: 0
    )
}

internal class DexScout(http: OkHttpClient) {
    private val client = http.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    private suspend fun rpc(method: String, mint: String, options: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val params = JSONArray().put(mint)
            if (options != null) params.put(options)
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", method).put("params", params)
            val request = Request.Builder().url("https://api.mainnet-beta.solana.com")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Solana RPC HTTP " + response.code }
                JSONObject(response.body?.string().orEmpty()).also {
                    check(!it.has("error")) { "Solana RPC aktuell eingeschränkt oder Token ungültig" }
                }
            }
        }

    suspend fun scan(mintInput: String): DexReport = withContext(Dispatchers.IO) {
        val mint = mintInput.trim()
        require(mint.matches(Regex("[1-9A-HJ-NP-Za-km-z]{32,44}"))) {
            "Gültige Solana-Mint-Adresse eingeben"
        }
        val request = Request.Builder()
            .url("https://api.dexscreener.com/token-pairs/v1/solana/" + mint)
            .get().build()
        val pairs = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "DEX Screener HTTP " + response.code }
            JSONArray(response.body?.string().orEmpty())
        }
        // Public Solana RPC is rate-limited; never replace missing chain facts with guesses.
        val account = runCatching { rpc("getAccountInfo", mint, JSONObject().put("encoding", "jsonParsed")) }
            .getOrDefault(JSONObject())
        val supply = runCatching { rpc("getTokenSupply", mint) }.getOrDefault(JSONObject())
        val largest = runCatching { rpc("getTokenLargestAccounts", mint) }.getOrDefault(JSONObject())
        parseDexReport(mint, pairs, account, supply, largest)
    }
}
