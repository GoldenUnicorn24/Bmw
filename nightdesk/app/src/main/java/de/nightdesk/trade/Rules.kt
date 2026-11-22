package de.nightdesk.trade

import kotlin.math.abs
import kotlin.math.max

internal enum class Strategy(val label: String) {
    TREND("Trend Following"), MOMENTUM("Momentum"), REVERSION("Mean Reversion"),
    BREAKOUT("Breakout"), MEME("Meme-Volatilitätsfilter")
}

internal data class Signal(val direction: Int, val reason: String, val atr: Double, val regime: String)
private fun ema(values: List<Double>, n: Int): List<Double> {
    val a = 2.0 / (n + 1)
    return values.drop(1).runningFold(values.first()) { acc, item -> acc + a * (item - acc) }
}

internal fun signal(candles: List<Candle>, strategy: Strategy): Signal {
    if (candles.size < 200) return Signal(0, "Mindestens 200 abgeschlossene Kerzen erforderlich", 0.0, "Unbekannt")
    val c = candles.map { it.close.toDouble() }
    val e9 = ema(c, 9).last()
    val e21 = ema(c, 21).last()
    val e50 = ema(c, 50).last()
    val e200 = ema(c, 200).last()
    val diffs = c.zipWithNext { a, b -> b - a }.takeLast(14)
    val gain = diffs.sumOf { max(0.0, it) }
    val loss = diffs.sumOf { max(0.0, -it) }
    val rsi = if (loss == 0.0) (if (gain == 0.0) 50.0 else 100.0) else 100 - 100 / (1 + gain / loss)
    val macd = ema(c, 12).zip(ema(c, 26)) { a, b -> a - b }
    val hist = macd.last() - ema(macd, 9).last()
    val atr = candles.zipWithNext { previous, current ->
        val h = current.high.toDouble()
        val l = current.low.toDouble()
        val p = previous.close.toDouble()
        max(h - l, max(abs(h - p), abs(l - p)))
    }.takeLast(14).average()
    val last = c.last()
    val momentum = last / c[c.size - 13] - 1
    val vol = candles.last().volume.toDouble()
    val normalVol = candles.dropLast(1).takeLast(20).map { it.volume.toDouble() }.average()
    val rising = e50 > e200
    val regime = when {
        atr / last > .04 -> "Volatil"
        abs(e50 / e200 - 1) < .005 -> "Seitwärts"
        rising -> "Aufwärtstrend"
        else -> "Abwärtstrend"
    }
    val top = candles.dropLast(1).takeLast(20).maxOf { it.high.toDouble() }
    val bottom = candles.dropLast(1).takeLast(20).minOf { it.low.toDouble() }
    val (buy, sell, rule) = when (strategy) {
        Strategy.TREND -> Triple(rising && e9 > e21 && last > e9 && rsi in 52.0..68.0, e9 < e21, "EMA 9/21 und EMA 50/200")
        Strategy.MOMENTUM -> Triple(rising && momentum > .01 && hist > 0, momentum < 0 && hist < 0, "12-Kerzen-Momentum und MACD")
        Strategy.REVERSION -> Triple(regime == "Seitwärts" && rsi < 32 && last < e21 - atr, rsi > 55 || last >= e21, "RSI und ATR-Abstand zum EMA 21")
        Strategy.BREAKOUT -> Triple(rising && last > top && vol > 1.5 * normalVol, last < bottom || last < e21, "20-Kerzen-Kanal und Volumen")
        Strategy.MEME -> Triple(rising && atr / last in .003..0.025 && momentum > .008 && vol > 1.3 * normalVol,
            atr / last > .04 || e9 < e21, "ATR-Filter, Momentum und Volumen")
    }
    val side = when { buy && !sell -> 1; sell && !buy -> -1; else -> 0 }
    val decision = when (side) { 1 -> "Kauf"; -1 -> "Verkauf"; else -> "Warten" }
    return Signal(side, "$decision · $rule · $regime", atr, regime)
}

internal data class ResearchResult(val strategy: Strategy, val net: Double, val hold: Double, val drawdown: Double,
                                   val wins: Int, val trades: Int, val factor: Double?, val expectancy: Double?,
                                   val regimes: Map<String, Double>)

internal fun compare(candles: List<Candle>, interval: Int, context: List<Candle> = emptyList()): List<ResearchResult> {
    require(candles.size >= 300) { "Zu wenig echte OHLCV-Daten" }
    require(candles.all { it.low > zero && it.low <= minOf(it.open, it.close) &&
        it.high >= maxOf(it.open, it.close) && it.volume >= zero } &&
        candles.zipWithNext().all { (a, b) -> b.time > a.time && (b.time - a.time) % (interval * 60L) == 0L }) {
        "Ungültige OHLCV-Reihe"
    }
    if (context.isNotEmpty()) require(context.zipWithNext().all { (a, b) -> b.time > a.time } &&
        context.all { it.low > zero && it.low <= minOf(it.close, it.open) && it.high >= maxOf(it.close, it.open) && it.volume >= zero }) {
        "Ungültige 1-Stunden-Kerzen"
    }
    val split = max(200, candles.size / 2)
    val higher = (split until candles.size).associateWith { index ->
        val history = context.takeWhile { it.time + 3600 <= candles[index].time }
        signal(history.takeLast(300), Strategy.TREND).regime
    }
    if (context.isNotEmpty()) require(higher.values.none { it == "Unbekannt" }) { "Zu wenig 1-Stunden-Historie für den Testzeitraum" }
    return Strategy.entries.map { strategy ->
        var cash = 1000.0
        var units = 0.0
        var basis = 0.0
        var entry = 0.0
        var best = cash
        var dd = 0.0
        var currentRegime = ""
        val outcomes = mutableListOf<Double>()
        val regimes = mutableMapOf<String, Double>()
        for (fold in split until candles.size step 100) {
            for (i in fold until minOf(fold + 100, candles.size)) {
                val result = signal(candles.subList(max(0, i - 300), i), strategy)
                val open = candles[i].open.toDouble()
                val priorClose = candles[i - 1].close.toDouble()
                val riskExit = units > 0 && (priorClose <= entry * .97 || priorClose >= entry * 1.06)
                if (result.direction == 1 && units == 0.0 && result.atr > 0 && i < candles.lastIndex &&
                    higher.getValue(i) != "Abwärtstrend") {
                    val riskDistance = max(2 * result.atr, priorClose * .03)
                    basis = minOf(cash * .30, cash * .01 / riskDistance * priorClose)
                    entry = open * 1.001
                    units = basis / (entry * 1.004)
                    cash -= basis
                    currentRegime = result.regime
                }
                if ((result.direction == -1 || riskExit || i == candles.lastIndex) && units > 0) {
                    val proceeds = units * open * .999 * .996
                    val outcome = proceeds - basis
                    cash += proceeds
                    outcomes += outcome
                    regimes[currentRegime] = (regimes[currentRegime] ?: 0.0) + outcome
                    units = 0.0
                }
                val equity = cash + units * candles[i].close.toDouble() * .999 * .996
                best = max(best, equity)
                dd = max(dd, 1 - equity / best)
            }
        }
        val positive = outcomes.filter { it > 0 }
        val losses = -outcomes.filter { it < 0 }.sum()
        val hold = 1000 * (candles.last().open.toDouble() * .999 * .996 /
            (candles[split].open.toDouble() * 1.001 * 1.004) - 1)
        ResearchResult(strategy, cash - 1000, hold, dd, positive.size, outcomes.size,
            if (losses > 0) positive.sum() / losses else null, if (outcomes.isEmpty()) null else outcomes.average(), regimes)
    }
}
