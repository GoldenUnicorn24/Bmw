package de.memepulse.ai

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

// Built-in deterministic scoring engine. NOT a trained neural network,
// and "strength" is NOT a calibrated profit probability.
data class NativeSignal(val action: String, val score: Int, val reason: String, val blockers: List<String>) {
    val eligible: Boolean get() = action == "BUY" && blockers.isEmpty()
    fun toJson(mint: String): JSONObject = JSONObject().put("action", action).put("mint", mint)
        .put("score", score).put("confidence", score / 100.0)
        .put("reason", reason).put("eligible", eligible)
        .put("engine", "memepulse-quant-v1")
}
object NativeStrategy {
    fun analyze(t: Coin): NativeSignal {
        val blockers = mutableListOf<String>()
        val liquid = t.liquidity
        val vol = t.volume
        val vol1 = t.volume1h
        val m5 = t.change5m
        val m1 = t.change1h
        val m6 = t.change6h
        val age = t.ageMin
        val ratio = if (t.sells > 0) t.buys / t.sells else if (t.buys > 0) 5.0 else 0.0
        val turn = if (liquid > 0) vol1 / liquid else Double.NaN
        fun reject(bad: Boolean, code: String) { if (bad) blockers.add(code) }
        reject(!t.price.isFinite() || t.price <= 0, "PRICE_INVALID")
        reject(!liquid.isFinite() || liquid < 75000, "LOW_LIQUIDITY")
        reject(!vol.isFinite() || vol < 180000, "LOW_VOLUME")
        reject(!vol1.isFinite() || vol1 < 15000, "LOW_1H_ACTIVITY")
        reject(!age.isFinite() || age < 60 || age > 525600, "TOKEN_AGE")
        reject(!m5.isFinite() || !m1.isFinite() || !m6.isFinite(), "INCOMPLETE_TREND")
        reject(m5.isFinite() && (m5 < -3 || m5 > 12), "5M_TREND_EXTREME")
        reject(m1.isFinite() && (m1 < 2 || m1 > 24), "1H_TREND_EXTREME")
        reject(m6.isFinite() && (m6 < -20 || m6 > 80), "6H_TREND_EXTREME")
        reject(!t.buys.isFinite() || !t.sells.isFinite() || t.buys < 25 || ratio < 1.20, "BUY_PRESSURE_WEAK")
        reject(!turn.isFinite() || turn < .02 || turn > 3, "ABNORMAL_TURNOVER")
        reject(t.fdvUsd.isFinite() && t.fdvUsd > 0 && t.fdvUsd / liquid > 120, "FDV_LIQUIDITY_IMBALANCE")
        var score = 0
        score += if (liquid >= 250000) 16 else if (liquid >= 120000) 12 else if (liquid >= 75000) 8 else 0
        score += if (vol >= 1000000) 14 else if (vol >= 400000) 11 else if (vol >= 180000) 8 else 0
        score += if (m5 in 1.0..7.0) 13 else if (m5 in 0.0..10.0) 9 else 0
        score += if (m1 in 4.0..14.0) 14 else if (m1 in 2.0..24.0) 10 else 0
        score += if (m6 in -5.0..35.0) 8 else if (m6 in -20.0..80.0) 4 else 0
        score += if (ratio in 1.7..3.0) 15 else if (ratio >= 1.25) 11 else 0
        score += if (turn in .1..1.4) 10 else if (turn in .02..3.0) 6 else 0
        score += if (age >= 240) 10 else if (age >= 120) 8 else if (age >= 60) 5 else 0
        score = score.coerceIn(0, 100)
        val buy = blockers.isEmpty() && score >= 85
        val why = if (buy) "Mehrere Momentum-Zeiträume, Kaufdruck und Liquidität bestätigen das Signal."
        else if (blockers.isNotEmpty()) "HOLD: " + blockers.joinToString(", ")
        else "HOLD: Signalstärke unter 85/100"
        return NativeSignal(if (buy) "BUY" else "HOLD", score, why, blockers)
    }

    // Full RugCheck report (not summary). Unsafe or missing token permissions => HOLD.
    fun securityReasons(r: JSONObject?): List<String> {
        if (r == null || r.isNull("score")) return listOf("RugCheck nicht verfügbar")
        val score = r.optDouble("score", Double.NaN)
        if (!score.isFinite() || score < 0) return listOf("RugCheck unvollständig")
        val failures = mutableListOf<String>()
        if (score > 800) failures.add("Hohes RugCheck-Risiko")
        val risks = r.optJSONArray("risks")
        if (risks == null) failures.add("RugCheck-Risiken fehlen")
        else for (i in 0 until risks.length()) {
            val level = risks.optJSONObject(i)?.optString("level", "")?.lowercase() ?: ""
            if (level == "danger" || level == "critical") failures.add("Kritischer Token-Risikohinweis")
        }
        val token = r.optJSONObject("token")
        if (token == null || !token.has("mintAuthority") || !token.has("freezeAuthority")) {
            failures.add("Token-Berechtigungen unbekannt")
        } else {
            if (!token.isNull("mintAuthority") && token.optString("mintAuthority").isNotBlank()) failures.add("Mint Authority aktiv")
            if (!token.isNull("freezeAuthority") && token.optString("freezeAuthority").isNotBlank()) failures.add("Freeze Authority aktiv")
        }
        return failures.distinct()
    }
}
