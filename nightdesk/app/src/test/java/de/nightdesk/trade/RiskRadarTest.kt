package de.nightdesk.trade

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RiskRadarTest {
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private val pair = PairInfo("BTC/EUR", "XXBTZEUR", 5, "0.00001".decimal(), "5".decimal())
    private val quote = Quote("99.5".decimal(), "100".decimal(), "100".decimal(), "50000".decimal(),
        zero, now, "10".decimal(), "10".decimal())
    private val candles = (0 until 220).map { index ->
        val time = now.epochSecond - 900 - (219 - index) * 900L
        Candle(time, "100".decimal(), "102".decimal(), "98".decimal(), "100".decimal(), "30".decimal())
    }

    @Test fun calculatesVolatilityCappedAmountFromRealMarketInputs() {
        val report = riskReport(pair, quote, candles, "1000".decimal(), "1000".decimal(), now)
        assertEquals("Kraken-Spotprüfung bestanden · kein Kaufsignal", report.reason)
        assertNotNull(report.quantity)
        assertTrue(report.quantity!! > zero)
        assertTrue(report.quantity * quote.ask <= "250".decimal())
        assertEquals(0, report.riskBudgetEur!!.compareTo("10".decimal()))
        assertEquals(0, report.atrPercent!!.compareTo("0.04".decimal()))
    }

    @Test fun rejectsStaleQuotesAndIlliquidOrIncompleteMarket() {
        assertTrue(riskReport(pair, quote, candles, "1000".decimal(), "1000".decimal(),
            now.plusSeconds(36)).reason.contains("Kurse fehlen"))
        assertTrue(riskReport(pair, quote.copy(volumeEur = "10".decimal()), candles,
            "1000".decimal(), "1000".decimal(), now).reason.contains("Liquidität"))
        assertTrue(riskReport(pair, quote, candles.take(100), "1000".decimal(),
            "1000".decimal(), now).reason.contains("200"))
    }

    @Test fun neverSuggestsTradeBelowExchangeMinimumOrAboveBudget() {
        val tiny = riskReport(pair.copy(minCost = "1000".decimal()), quote, candles,
            "1000".decimal(), "1000".decimal(), now)
        assertNull(tiny.quantity)
        assertTrue(tiny.reason.contains("Mindestorder"))
        assertNull(riskReport(pair, quote, candles, null, null, now).quantity)
    }
}
