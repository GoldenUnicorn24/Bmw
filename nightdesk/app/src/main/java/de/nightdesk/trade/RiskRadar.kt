package de.nightdesk.trade

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Real Kraken spot preflight for the same position sizing used by the local paper bot.
 * No LP locking, smart-contract audit, holder map or new-token sniping is implied.
 */
internal data class RiskReport(
    val reason: String,
    val quantity: BigDecimal? = null,
    val atrPercent: BigDecimal? = null,
    val riskBudgetEur: BigDecimal? = null
)

internal fun riskReport(
    pair: PairInfo,
    quote: Quote?,
    candles: List<Candle>,
    equity: BigDecimal?,
    cash: BigDecimal?,
    now: Instant = Instant.now()
): RiskReport {
    if (quote == null || !quote.isCurrent(now)) return RiskReport("Aktuelle Kraken-Kurse fehlen")
    if (quote.spread > "0.01".decimal()) return RiskReport("Spread über 1 % – Kauf gesperrt")
    if (quote.volumeEur < "20000".decimal()) return RiskReport("24h-Liquidität unter 20.000 € – Kauf gesperrt")
    if (candles.size < 200) return RiskReport("Mindestens 200 abgeschlossene 15m-Kerzen erforderlich")
    val age = now.epochSecond - candles.last().time
    if (age !in 890..1900) return RiskReport("Kerzen veraltet oder nicht abgeschlossen")
    if (candles.takeLast(15).zipWithNext().any { (a, b) ->
            b.time <= a.time || b.low <= zero || b.high < b.low || a.close <= zero
        }) return RiskReport("Ungültige Kraken-Kerzen")
    val trueRanges = candles.takeLast(15).zipWithNext { previous, current ->
        maxOf(current.high - current.low,
            (current.high - previous.close).abs(),
            (current.low - previous.close).abs())
    }
    val atr = trueRanges.reduce(BigDecimal::add).divide(trueRanges.size.toBigDecimal(), precision)
    if (atr <= zero || quote.ask <= zero) return RiskReport("Keine valide Volatilität")
    val atrPercent = atr.divide(quote.ask, precision)
    if (equity == null || cash == null || equity <= zero || cash <= zero)
        return RiskReport("Kein bewertbares Papierguthaben", atrPercent = atrPercent)
    val riskBudget = equity * "0.01".decimal()
    val distance = maxOf(atr * 2.toBigDecimal(), quote.ask * "0.03".decimal())
    val amount = minOf(
        riskBudget.divide(distance, precision),
        (cash * "0.30".decimal()).divide(quote.ask, precision),
        "250".decimal().divide(quote.ask, precision),
        quote.askQuantity
    ).setScale(pair.quantityDecimals, RoundingMode.DOWN)
    if (amount < pair.minimum || amount * quote.ask < pair.minCost)
        return RiskReport("Risikogerechte Menge unter Kraken-Mindestorder", atrPercent = atrPercent,
            riskBudgetEur = riskBudget)
    return RiskReport("Kraken-Spotprüfung bestanden · kein Kaufsignal", amount, atrPercent, riskBudget)
}
