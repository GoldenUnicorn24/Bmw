package de.nightdesk.trade

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class KrakenPulseTest {
    @Test fun depthAndWhaleEstimatesUseOnlyExchangeData() {
        val book = JSONObject("""{"XXBTZEUR":{"asks":[["100","2","0"],["101","1","0"]],"bids":[["99","3","0"]]}}""")
        val trades = JSONObject("""{"XXBTZEUR":[["100","600","123","b","m",""],["100","1","124","s","l",""]],"last":"125"}""")
        val pulse = parseKrakenPulse(book, trades, Instant.EPOCH)
        assertEquals(0, pulse.bidDepthEur.compareTo("297".decimal()))
        assertEquals(0, pulse.askDepthEur.compareTo("301".decimal()))
        assertEquals(1, pulse.largeTrades)
        assertEquals(2, pulse.observedTrades)
        assertEquals(0, pulse.largestTradeEur.compareTo("60000".decimal()))
        assertTrue(pulse.buyPressure > 0.99)
    }
}
