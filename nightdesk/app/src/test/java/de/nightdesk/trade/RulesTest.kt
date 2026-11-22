package de.nightdesk.trade

import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private fun bars(size: Int) = List(size) { i ->
        val close = (100 + i).toBigDecimal()
        Candle(i * 900L, close, close + 1.toBigDecimal(), close - 1.toBigDecimal(), close, 20.toBigDecimal())
    }

    @Test fun momentumExplainsUptrendAndInsufficientHistory() {
        val candles = bars(240)
        assertEquals("Aufwärtstrend", signal(candles, Strategy.MOMENTUM).regime)
        assertEquals(0, signal(candles.take(100), Strategy.MOMENTUM).direction)
        assertTrue(signal(candles, Strategy.BREAKOUT).reason.contains("Volumen"))
    }

    @Test fun backtestRunsAllStrategiesAndRejectsUnorderedData() {
        val candles = bars(330)
        val results = compare(candles, 15)
        assertEquals(Strategy.entries, results.map { it.strategy })
        assertTrue(results.all { it.net.isFinite() && it.hold.isFinite() && it.drawdown in 0.0..1.0 })
        assertThrows(IllegalArgumentException::class.java) { compare(candles.reversed(), 15) }
    }
}
