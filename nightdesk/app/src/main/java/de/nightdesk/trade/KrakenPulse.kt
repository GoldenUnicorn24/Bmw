package de.nightdesk.trade

import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/**
 * Only public, directly observed Kraken order book and exchange prints.
 * No holder-wallet mapping, DEX pool lock audit, mint authority or social-media sentiment.
 */
internal data class KrakenPulse(
    val bidDepthEur: BigDecimal,
    val askDepthEur: BigDecimal,
    val buyPressure: Double,
    val largestTradeEur: BigDecimal,
    val largeTrades: Int,
    val observedTrades: Int,
    val asOf: Instant
)

internal fun parseKrakenPulse(depth: JSONObject, trades: JSONObject,
                              now: Instant = Instant.now()): KrakenPulse {
    val book = depth.getJSONObject(depth.keys().asSequence().first())
    fun side(name: String): BigDecimal {
        val rows = book.getJSONArray(name)
        var total = zero
        for (i in 0 until rows.length()) {
            val entry = rows.getJSONArray(i)
            val price = entry.getString(0).decimal()
            val qty = entry.getString(1).decimal()
            require(price > zero && qty >= zero) { "Ungültiges Kraken-Orderbuch" }
            total += price * qty
        }
        return total
    }
    val bids = side("bids")
    val asks = side("asks")
    val tradeKey = trades.keys().asSequence().first { it != "last" }
    val list = trades.getJSONArray(tradeKey)
    var buy = zero
    var sell = zero
    var largest = zero
    var large = 0
    var count = 0
    for (i in 0 until list.length()) {
        val row = list.getJSONArray(i)
        val notional = row.getString(0).decimal() * row.getString(1).decimal()
        if (notional <= zero) continue
        largest = maxOf(largest, notional)
        if (notional >= "50000".decimal()) large++
        when (row.getString(3)) {
            "b" -> buy += notional
            "s" -> sell += notional
        }
        count++
    }
    val total = buy + sell
    val pressure = if (total > zero) buy.divide(total, precision).toDouble() else 0.5
    return KrakenPulse(bids, asks, pressure, largest, large, count, now)
}
