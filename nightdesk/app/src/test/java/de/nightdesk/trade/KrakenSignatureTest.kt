package de.nightdesk.trade

import org.junit.Assert.*
import org.junit.Test

class KrakenSignatureTest {
    @Test fun matchesOfficialKrakenHmacSha512Vector() {
        val key = "kQH5HW/8p1uGOVjbgWA7FunAmGO8lsSUXNsu3eow76sz84Q18fWxnyRzBHCd3pd5nE9qa99HAZtuZuj6F1huXg=="
        val encoded = "nonce=1616492376594&ordertype=limit&pair=XBTUSD&price=37500&type=buy&volume=1.25"
        assertEquals(
            "4/dpxb3iT4tp/ZCVEwSnEsLxx0bqyhLpdfOpc6fn7OR8+UClSV5n9E6aSS8MPtnRfp32bAb0nmbRn6H8ndwLUQ==",
            KrakenSignature.sign("/0/private/AddOrder", "1616492376594", encoded, key)
        )
    }

    @Test fun bindsSignatureToExactBodyAndEndpoint() {
        val secret = java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 13 })
        val original = KrakenSignature.sign("/0/private/Balance", "123", "nonce=123", secret)
        val changed = KrakenSignature.sign("/0/private/Balance", "124", "nonce=124", secret)
        val other = KrakenSignature.sign("/0/private/OpenOrders", "123", "nonce=123", secret)
        assertNotEquals(original, changed)
        assertNotEquals(original, other)
    }
}
