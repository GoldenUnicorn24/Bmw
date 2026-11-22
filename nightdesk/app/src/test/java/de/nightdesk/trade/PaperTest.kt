package de.nightdesk.trade

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PaperTest {
    private val pair = PairInfo("ETH/EUR", "ETHEUR", 4, "0.01".decimal(), "5".decimal())
    private val now = Instant.parse("2026-05-12T12:00:00Z")
    private val quote = Quote("99.50".decimal(), "100".decimal(), "100".decimal(), "22000".decimal(), zero, now)

    @Test fun feesSpreadAndSlippageAreIncluded() {
        val order = preview("epoch", pair, true, "1".decimal(), quote, now)
        assertEquals(0, order.price.compareTo("100.100".decimal()))
        assertEquals(0, order.fee.compareTo("0.4004".decimal()))
        val sell = preview("epoch", pair, false, "1".decimal(), quote, now)
        assertEquals(0, sell.price.compareTo("99.4005".decimal()))
    }

    @Test fun staleQuoteAndInvalidQuantityAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            preview("epoch", pair, true, "1".decimal(), quote, now.plusSeconds(36))
        }
        assertThrows(IllegalArgumentException::class.java) {
            preview("epoch", pair, true, "0.100001".decimal(), quote, now)
        }
    }

    @Test fun realizedAccountingPreservesRemainingCostBasis() {
        fun row(id: String, buy: Boolean, qty: String, price: String, fee: String) =
            PaperFill(id, "epoch", 0, pair.symbol, buy, qty, price, fee, "Manuell")
        val balance = portfolio("1000".decimal(), listOf(
            row("buy", true, "2", "100", "0.8"),
            row("sell", false, "1", "115", "0.46")
        ))
        assertEquals(0, balance.cash.compareTo("913.74".decimal()))
        assertEquals(0, balance.holdings.getValue(pair.symbol).basis.compareTo("100.4".decimal()))
        assertEquals(0, balance.results.single().second.compareTo("14.14".decimal()))
        assertNull(balance.value(emptyMap(), now))
        assertEquals(0, checkNotNull(balance.value(mapOf(pair.symbol to quote), now)).compareTo("1013.24".decimal()))
    }

    @Test fun testBuildCannotEnableRealMoney() {
        assertFalse(BuildConfig.LIVE_ENABLED)
    }
}
