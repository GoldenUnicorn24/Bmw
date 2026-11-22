package de.nightdesk.trade

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DexScoutTest {
    @Test fun identifiesAuthorityAndTokenAccountConcentrationWithoutGuessingLocks() {
        val address = "So11111111111111111111111111111111111111112"
        val pairs = JSONArray().put(JSONObject("""{
          "chainId":"solana","dexId":"raydium",
          "baseToken":{"address":"So11111111111111111111111111111111111111112","symbol":"WSOL"},
          "quoteToken":{"address":"q","symbol":"USD"},
          "liquidity":{"usd":150000},"volume":{"h24":80000},
          "txns":{"h24":{"buys":42,"sells":21}},"priceChange":{"h24":11.2},
          "pairCreatedAt":1760000000000}"""))
        val account = JSONObject("""{"result":{"value":{"data":{"parsed":{"info":{"mintAuthority":null,"freezeAuthority":"test"}}}}}}""")
        val supply = JSONObject("""{"result":{"value":{"amount":"1000"}}}""")
        val largest = JSONObject("""{"result":{"value":[{"amount":"200"},{"amount":"100"}]}}""")
        val result = parseDexReport(address, pairs, account, supply, largest)
        assertEquals(150000.0, result.liquidityUsd.toDouble(), 0.01)
        assertEquals(0.3, result.top10AccountsShare!!, 0.0001)
        assertEquals(false, result.mintAuthority)
        assertEquals(true, result.freezeAuthority)
        assertEquals(42, result.buyTransactions)
        assertEquals(21, result.sellTransactions)
    }
    @Test fun incompleteRpcDataIsUnknownNotSafe() {
        val address = "So11111111111111111111111111111111111111112"
        val pairs = JSONArray().put(JSONObject("""{"chainId":"solana","dexId":"x",
          "baseToken":{"address":"So11111111111111111111111111111111111111112","symbol":"T"},
          "quoteToken":{"address":"q"},"liquidity":{"usd":1}}"""))
        val report = parseDexReport(address, pairs, JSONObject(), JSONObject(), JSONObject())
        assertNull(report.top10AccountsShare)
        assertNull(report.freezeAuthority)
        assertNull(report.mintAuthority)
    }
}
