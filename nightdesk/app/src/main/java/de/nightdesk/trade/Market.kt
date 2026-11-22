package de.nightdesk.trade

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.util.concurrent.TimeUnit

internal val precision = MathContext.DECIMAL128
internal val zero = BigDecimal.ZERO
internal fun String.decimal() = toBigDecimal()
internal fun JSONObject.decimal(name: String) = getString(name).decimal()

internal data class PairInfo(
    val symbol: String, val apiId: String, val quantityDecimals: Int,
    val minimum: BigDecimal, val minCost: BigDecimal, val restKey: String = apiId, val base: String = ""
)

internal data class Quote(
    val bid: BigDecimal, val ask: BigDecimal, val last: BigDecimal,
    val volumeEur: BigDecimal, val change: BigDecimal, val time: Instant,
    val bidQuantity: BigDecimal = BigDecimal.ONE, val askQuantity: BigDecimal = BigDecimal.ONE
) {
    val spread: BigDecimal get() = (ask - bid).divide(last, precision)
    fun isCurrent(now: Instant = Instant.now()) =
        bidQuantity > zero && askQuantity > zero && bid > zero && ask >= bid && last > zero &&
            !time.isAfter(now.plusSeconds(5)) && time.isAfter(now.minusSeconds(35))
}

private interface PublicApi {
    @GET("0/public/AssetPairs") suspend fun pairs(): ResponseBody
    @GET("0/public/Ticker") suspend fun ticker(@Query("pair") pairs: String): ResponseBody
    @GET("0/public/OHLC") suspend fun candles(@Query("pair") pair: String, @Query("interval") interval: Int): ResponseBody
    @GET("0/public/Depth") suspend fun depth(@Query("pair") pair: String, @Query("count") count: Int): ResponseBody
    @GET("0/public/Trades") suspend fun trades(@Query("pair") pair: String): ResponseBody
}

internal data class Candle(val time: Long, val open: BigDecimal, val high: BigDecimal,
                           val low: BigDecimal, val close: BigDecimal, val volume: BigDecimal)

internal class Markets {
    internal val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val api = Retrofit.Builder().baseUrl("https://api.kraken.com/").client(http)
        .build().create(PublicApi::class.java)
    val quotes = MutableStateFlow<Map<String, Quote>>(emptyMap())
    val pairs = MutableStateFlow<List<PairInfo>>(emptyList())
    val status = MutableStateFlow("Verbinde mit Kraken …")
    @Volatile private var socket: WebSocket? = null

    private fun result(body: ResponseBody): JSONObject = body.use {
        val root = JSONObject(it.string())
        check(root.getJSONArray("error").length() == 0) { "Kraken meldet einen Marktdatenfehler" }
        root.getJSONObject("result")
    }

    suspend fun pulse(pair: PairInfo): KrakenPulse =
        parseKrakenPulse(result(api.depth(pair.apiId, 25)), result(api.trades(pair.apiId)))

    suspend fun load() {
        val response = result(api.pairs())
        pairs.value = response.keys().asSequence().map { it to response.getJSONObject(it) }
            .filter { (_, it) -> it.optString("wsname").endsWith("/EUR") && it.optString("status") == "online" }
            .map { (key, it) ->
                PairInfo(it.getString("wsname").replace("XBT/", "BTC/").replace("XDG/", "DOGE/"), it.getString("altname"),
                    it.getInt("lot_decimals"), it.decimal("ordermin"), it.decimal("costmin"), key, it.getString("base"))
            }.distinctBy { it.symbol }.sortedBy { it.symbol }.toList()
        check(pairs.value.isNotEmpty()) { "Keine aktiven EUR-Paare verfügbar" }
    }

    suspend fun refresh(symbols: List<PairInfo>) {
        if (symbols.isEmpty()) return
        val received = mutableMapOf<String, Quote>()
        for (group in symbols.chunked(30)) {
            // REST keys are exchange-specific. Use individual ids only when a combined ticker cannot be mapped.
            val response = result(api.ticker(group.joinToString(",") { it.apiId }))
            for (pair in group) {
                val item = response.optJSONObject(pair.restKey) ?: response.optJSONObject(pair.apiId)
                    ?: continue
                received[pair.symbol] = parseTicker(item)
            }
        }
        quotes.update { previous ->
            previous + received.filter { (symbol, quote) -> previous[symbol]?.time?.isAfter(quote.time) != true }
        }
    }

    suspend fun quote(pair: PairInfo): Quote {
        val response = result(api.ticker(pair.apiId))
        val key = response.keys().asSequence().single()
        return parseTicker(response.getJSONObject(key)).also { q -> quotes.update { it + (pair.symbol to q) } }
    }

    private fun parseTicker(item: JSONObject): Quote {
        val last = item.getJSONArray("c").getString(0).decimal()
        val open = item.decimal("o")
        return Quote(item.getJSONArray("b").getString(0).decimal(),
            item.getJSONArray("a").getString(0).decimal(), last,
            item.getJSONArray("v").getString(1).decimal() * item.getJSONArray("p").getString(1).decimal(),
            if (open > zero) (last - open).divide(open, precision) else zero, Instant.now(),
            item.getJSONArray("b").getString(2).decimal(), item.getJSONArray("a").getString(2).decimal())
    }

    suspend fun maintain() {
        while (true) {
            if (pairs.value.isEmpty()) {
                try {
                    load()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    status.value = "Kraken-Marktdaten nicht erreichbar"
                }
            }
            if (pairs.value.isNotEmpty() && socket == null) connect(pairs.value.map { it.symbol })
            delay(5000)
        }
    }

    private fun connect(symbols: List<String>) {
        val url = "https://ws.kraken.com/v2".toHttpUrl()
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                symbols.chunked(35).forEach { batch ->
                    webSocket.send(JSONObject().put("method", "subscribe").put("params", JSONObject()
                        .put("channel", "ticker").put("event_trigger", "bbo").put("symbol", JSONArray(batch))).toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("channel") != "ticker") return
                    val values = json.getJSONArray("data")
                    repeat(values.length()) { i ->
                        val item = values.getJSONObject(i)
                        val symbol = item.getString("symbol")
                        val last = item.decimal("last")
                        val quote = Quote(item.decimal("bid"), item.decimal("ask"), last,
                            item.decimal("volume") * item.decimal("vwap"),
                            item.decimal("change_pct").movePointLeft(2),
                            Instant.parse(item.getString("timestamp")),
                            item.decimal("bid_qty"), item.decimal("ask_qty"))
                        if (quote.isCurrent()) {
                            quotes.update { previous ->
                                if (previous[symbol]?.time?.isAfter(quote.time) == true) previous
                                else previous + (symbol to quote)
                            }
                            status.value = "Kraken · WebSocket verbunden"
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = disconnected(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnected(webSocket)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        })
    }

    private fun disconnected(connection: WebSocket) {
        if (socket === connection) socket = null
        status.value = "WebSocket getrennt · REST-Abfrage verfügbar"
    }

    fun close() { socket?.cancel() }

    suspend fun candles(pair: PairInfo, interval: Int = 15): List<Candle> {
        val response = result(api.candles(pair.apiId, interval))
        val rows = response.getJSONArray(response.keys().asSequence().single { it != "last" })
        return (0 until (rows.length() - 1)).map { i ->
            val row = rows.getJSONArray(i)
            Candle(row.getLong(0), row.getString(1).decimal(), row.getString(2).decimal(),
                row.getString(3).decimal(), row.getString(4).decimal(), row.getString(6).decimal())
        }
    }
}
