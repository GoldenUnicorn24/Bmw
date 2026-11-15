package de.memepulse.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class Coin(
    val mint: String,
    val symbol: String,
    val name: String,
    val price: Double,
    val change1h: Double,
    val liquidity: Double,
    val volume: Double,
    val ageMin: Double,
    val buys: Double,
    val sells: Double,
    val live: Boolean,
    val change5m: Double = Double.NaN,
    val change6h: Double = Double.NaN,
    val volume1h: Double = Double.NaN,
    val fdvUsd: Double = 0.0
) {
    val score: Int get() {
        if (price <= 0) return 0
        var s = 0
        s += if (liquidity >= 100000) 25 else if (liquidity >= 50000) 16 else 0
        s += if (volume >= 250000) 20 else if (volume >= 100000) 10 else 0
        s += if (ageMin >= 120) 15 else if (ageMin >= 30) 8 else 0
        if (buys > 20 && buys / max(1.0, sells) >= 1.1) s += 20
        if (change1h in 3.0..25.0) s += 20
        return s
    }
}

data class PaperPosition(val mint: String, val symbol: String, val quantity: Double, val invested: Double, val entry: Double)
data class PaperFill(val at: Long, val side: String, val symbol: String, val amount: Double, val pnl: Double)

fun usd(v: Double): String = "$" + String.format(Locale.US, "%,.2f", v)
fun price(v: Double): String = "$" + String.format(Locale.US, if (v < .001) "%.8f" else if (v < 1) "%.5f" else "%.3f", v)
fun signed(v: Double): String = (if (v > 0) "+" else "") + String.format(Locale.US, "%.2f%%", v)

class MemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("memepulse-v2", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    val coins = mutableStateListOf<Coin>()
    val positions = mutableStateListOf<PaperPosition>()
    val fills = mutableStateListOf<PaperFill>()
    val realTrades = mutableStateListOf<JSONObject>()
    val equityPoints = mutableStateListOf<Double>()
    var cash by mutableDoubleStateOf(1000.0)
    var message by mutableStateOf("Bereit · Paper Trading")
    var marketLoading by mutableStateOf(false)
    var realMarket by mutableStateOf(false)
    var marketTimestamp by mutableLongStateOf(0L)
    var paperMode by mutableStateOf(prefs.getBoolean("simulationEnabled", false))
    var backendUrl by mutableStateOf(prefs.getString("backendUrl", "") ?: "")
    var backendToken by mutableStateOf("") // NEVER persisted. Never holds a wallet private key.
    var serverStatus by mutableStateOf<JSONObject?>(null)
    var pendingPreview by mutableStateOf<JSONObject?>(null)
    var aiSignal by mutableStateOf<JSONObject?>(null)
    var selectedCoin by mutableStateOf<Coin?>(null)
    var tradeSol by mutableStateOf("0.005")
    var tradeUsd by mutableStateOf("10")
    var activePage by mutableIntStateOf(0)
    var requesting by mutableStateOf(false)
    // Agent is opt-in and ephemeral: never silently restarts after process death.
    var paperBotEnabled by mutableStateOf(false)
    var paperBotWorking by mutableStateOf(false)
    var paperBotLastCheck by mutableLongStateOf(0L)
    var paperBotLastResult by mutableStateOf("Noch kein Quant-Signal")
    val paperBotEvents = mutableStateListOf<String>()
    val agentEvents = mutableStateListOf<JSONObject>()
    val realPositions = mutableStateListOf<JSONObject>()
    var realSolBalance by mutableStateOf<Double?>(null)
    var botRisk by mutableStateOf<JSONObject?>(null)
    private val simulatedInitial = 1000.0

    init {
        restore()
        if (paperMode) seedDemo() else refreshMarket()
    }
    private fun seedDemo() {
        coins.clear()
        coins.addAll(listOf(
            Coin("demo-dova", "DOVA", "DogeNova (DEMO)", .00284, 8.2, 348000.0, 1820000.0, 1280.0, 286.0, 123.0, false),
            Coin("demo-mfrg", "MFRG", "MoonFrog (DEMO)", .0000924, 5.7, 153000.0, 722000.0, 467.0, 137.0, 92.0, false),
            Coin("demo-pupx", "PUPX", "PixelPup (DEMO)", .0132, -2.3, 97000.0, 480000.0, 3100.0, 124.0, 107.0, false),
            Coin("demo-rcat", "RCAT", "RocketCat (DEMO)", .0781, -6.1, 24000.0, 68000.0, 95.0, 38.0, 94.0, false)
        ))
        realMarket = false
        marketTimestamp = 0
    }
    fun equity(): Double = cash + positions.sumOf { p -> p.quantity * (coins.find { it.mint == p.mint }?.price ?: p.entry) }
    fun unrealized(): Double = positions.sumOf { p -> p.quantity * (coins.find { it.mint == p.mint }?.price ?: p.entry) - p.invested }
    fun realized(): Double = fills.sumOf { it.pnl }
    fun winrate(): Double {
        val closed = fills.filter { it.side == "SELL" }
        return if (closed.isEmpty()) 0.0 else 100.0 * closed.count { it.pnl > 0 } / closed.size
    }
    private fun recordEquity() {
        equityPoints.add(equity())
        while (equityPoints.size > 120) equityPoints.removeAt(0)
    }
    fun paperBuy(c: Coin) {
        paperBuyAmount(c, tradeUsd.toDoubleOrNull() ?: 0.0)
    }
    private fun paperBuyAmount(c: Coin, spend: Double) {
        if (spend <= 0 || spend > cash || spend > equity() * .01 + .001) {
            message = "Papier-Kauf gestoppt: max. 1 % des Kapitals je Order."
            return
        }
        if (positions.size >= 3 || positions.any { it.mint == c.mint }) {
            message = "Maximal 3 Positionen oder Coin bereits gehalten."
            return
        }
        if (!paperMode || !realMarket || !c.live) { message = "Paper-Kauf nur im aktivierten Simulationsmodus mit Live-Kursen"; return }
        val quantity = spend * .992 / c.price // 0.8 % simulated total entry cost
        cash -= spend
        positions.add(PaperPosition(c.mint, c.symbol, quantity, spend, c.price))
        fills.add(0, PaperFill(System.currentTimeMillis(), "BUY", c.symbol, spend, 0.0))
        recordEquity()
        save()
        message = "Paper BUY " + c.symbol + " · " + usd(spend)
    }
    fun paperSell(p: PaperPosition) {
        val mark = coins.find { it.mint == p.mint }?.price ?: p.entry
        val proceeds = p.quantity * mark * .992 // 0.8 % simulated exit cost
        cash += proceeds
        val pnl = proceeds - p.invested
        positions.remove(p)
        fills.add(0, PaperFill(System.currentTimeMillis(), "SELL", p.symbol, proceeds, pnl))
        recordEquity()
        save()
        message = "Paper SELL " + p.symbol + " · P&L " + usd(pnl)
    }
    fun resetPaper() {
        cash = simulatedInitial
        positions.clear()
        fills.clear()
        equityPoints.clear()
        recordEquity()
        save()
        message = "Papierkonto zurückgesetzt"
    }
    fun refreshMarket() {
        if (marketLoading) return
        marketLoading = true
        thread {
            try {
                val boosts = JSONArray(getJson("https://api.dexscreener.com/token-boosts/top/v1"))
                val profiles = JSONArray(getJson("https://api.dexscreener.com/token-profiles/latest/v1"))
                val mints = linkedSetOf<String>()
                for (feed in listOf(boosts, profiles)) {
                    for (i in 0 until feed.length()) {
                        val item = feed.optJSONObject(i) ?: continue
                        if (item.optString("chainId") == "solana") {
                            val mint = item.optString("tokenAddress")
                            if (mint.isNotBlank()) mints.add(mint)
                        }
                        if (mints.size >= 30) break
                    }
                    if (mints.size >= 30) break
                }
                if (mints.isEmpty()) error("Keine Solana-Token im Feed")
                val pairs = JSONArray(getJson("https://api.dexscreener.com/tokens/v1/solana/" + mints.joinToString(",")))
                val best = mutableMapOf<String, Coin>()
                for (i in 0 until pairs.length()) {
                    val pair = pairs.optJSONObject(i) ?: continue
                    val base = pair.optJSONObject("baseToken") ?: continue
                    val mint = base.optString("address")
                    if (mint !in mints) continue
                    val liq = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                    val created = pair.optLong("pairCreatedAt", System.currentTimeMillis())
                    val trade = pair.optJSONObject("txns")?.optJSONObject("h1")
                    val token = Coin(mint, base.optString("symbol", "???").take(16),
                        base.optString("name", "Token").take(70),
                        pair.optString("priceUsd").toDoubleOrNull() ?: 0.0,
                        pair.optJSONObject("priceChange")?.optDouble("h1", 0.0) ?: 0.0,
                        liq, pair.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0,
                        max(0.0, (System.currentTimeMillis() - created) / 60000.0),
                        trade?.optDouble("buys", 0.0) ?: 0.0,
                        trade?.optDouble("sells", 0.0) ?: 0.0, true,
                        pair.optJSONObject("priceChange")?.optDouble("m5", Double.NaN) ?: Double.NaN,
                        pair.optJSONObject("priceChange")?.optDouble("h6", Double.NaN) ?: Double.NaN,
                        pair.optJSONObject("volume")?.optDouble("h1", Double.NaN) ?: Double.NaN,
                        pair.optDouble("fdv", 0.0))
                    if (token.price > 0 && (best[mint]?.liquidity ?: -1.0) < liq) best[mint] = token
                }
                if (best.isEmpty()) error("Keine handelbaren Kurse im Feed")
                main.post {
                    coins.clear()
                    coins.addAll(best.values.sortedByDescending { it.score })
                    realMarket = true
                    marketTimestamp = System.currentTimeMillis()
                    marketLoading = false
                    recordEquity()
                    message = "Live-Kurse aktualisiert · ausschließlich Marktdaten"
                    if (paperBotEnabled && paperMode) runPaperBotCycle()
                }
            } catch (e: Exception) {
                main.post {
                    marketLoading = false
                    message = "Live-Daten fehlgeschlagen: " + (e.message ?: "Netzwerkfehler").take(100)
                }
            }
        }
    }
    // Discover any user-supplied Solana mint; no trading authorization is implied.
    fun lookupMint(mint: String) {
        val clean = mint.trim()
        if (!Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$").matches(clean)) {
            message = "Bitte vollständige Solana-Mint-Adresse eingeben (Base58)"
            return
        }
        if (marketLoading) return
        marketLoading = true
        thread {
            try {
                val pairs = JSONArray(getJson("https://api.dexscreener.com/token-pairs/v1/solana/" +
                    java.net.URLEncoder.encode(clean, "UTF-8")))
                var best: JSONObject? = null
                var maxLiq = -1.0
                for (i in 0 until pairs.length()) {
                    val pair = pairs.optJSONObject(i) ?: continue
                    val base = pair.optJSONObject("baseToken") ?: continue
                    if (base.optString("address") != clean) continue
                    val liq = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                    if (liq > maxLiq) { maxLiq = liq; best = pair }
                }
                val p = best ?: error("Kein Solana-DEX-Markt zu dieser Mint-Adresse gefunden")
                val b = p.optJSONObject("baseToken") ?: error("Fehlende Tokendaten")
                val tx = p.optJSONObject("txns")?.optJSONObject("h1")
                val tok = Coin(clean, b.optString("symbol", "???").take(16),
                    b.optString("name", "Token").take(70),
                    p.optString("priceUsd").toDoubleOrNull() ?: 0.0,
                    p.optJSONObject("priceChange")?.optDouble("h1", Double.NaN) ?: Double.NaN,
                    p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0,
                    p.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0,
                    max(0.0, (System.currentTimeMillis() - p.optLong("pairCreatedAt", System.currentTimeMillis())) / 60000.0),
                    tx?.optDouble("buys", 0.0) ?: 0.0, tx?.optDouble("sells", 0.0) ?: 0.0,
                    true,
                    p.optJSONObject("priceChange")?.optDouble("m5", Double.NaN) ?: Double.NaN,
                    p.optJSONObject("priceChange")?.optDouble("h6", Double.NaN) ?: Double.NaN,
                    p.optJSONObject("volume")?.optDouble("h1", Double.NaN) ?: Double.NaN,
                    p.optDouble("fdv", 0.0))
                if (!tok.price.isFinite() || tok.price <= 0) error("Kein gültiger Marktpreis")
                main.post {
                    coins.removeAll { it.mint == clean }
                    coins.add(0, tok)
                    realMarket = true
                    marketTimestamp = System.currentTimeMillis()
                    marketLoading = false
                    message = "Mint geladen: " + tok.symbol + " · Signalanalyse erforderlich"
                }
            } catch (err: Exception) { main.post {
                marketLoading = false
                message = "Mint-Suche: " + (err.message ?: "Netzwerkfehler").take(120)
            } }
        }
    }
    fun simulateDemoTick() {
        if (realMarket) return
        for (i in coins.indices) {
            val c = coins[i]
            val v = (Random.nextDouble() - .5) * .025
            coins[i] = c.copy(price = max(.0000000001, c.price * (1 + v)), change1h = c.change1h + v * 100)
        }
        recordEquity()
    }
    fun setSimulationMode(enabled: Boolean) {
        if (paperMode == enabled) return
        paperMode = enabled
        paperBotEnabled = false
        prefs.edit().putBoolean("simulationEnabled", enabled).apply()
        message = if (enabled) "Simulation aktiviert – reale Kurse, fiktives Guthaben"
            else "Echtgeld-Oberfläche aktiv – Orders ohne Serverfreigabe gesperrt"
        if (!realMarket) refreshMarket()
    }
    private fun save() {
        val p = JSONArray()
        positions.forEach {
            p.put(JSONObject().put("mint", it.mint).put("symbol", it.symbol).put("quantity", it.quantity)
                .put("invested", it.invested).put("entry", it.entry))
        }
        val j = JSONArray()
        fills.take(150).forEach {
            j.put(JSONObject().put("at", it.at).put("side", it.side).put("symbol", it.symbol)
                .put("amount", it.amount).put("pnl", it.pnl))
        }
        prefs.edit().putFloat("cash", cash.toFloat()).putString("positions", p.toString())
            .putString("fills", j.toString()).putString("backendUrl", backendUrl)
            .putString("equity", JSONArray(equityPoints).toString()).apply()
    }
    private fun restore() {
        cash = prefs.getFloat("cash", 1000f).toDouble()
        try {
            val p = JSONArray(prefs.getString("positions", "[]"))
            for (i in 0 until p.length()) {
                val x = p.getJSONObject(i)
                positions.add(PaperPosition(x.getString("mint"), x.getString("symbol"), x.getDouble("quantity"),
                    x.getDouble("invested"), x.getDouble("entry")))
            }
            val j = JSONArray(prefs.getString("fills", "[]"))
            for (i in 0 until j.length()) {
                val x = j.getJSONObject(i)
                fills.add(PaperFill(x.getLong("at"), x.getString("side"), x.getString("symbol"),
                    x.getDouble("amount"), x.getDouble("pnl")))
            }
            val h = JSONArray(prefs.getString("equity", "[]"))
            for (i in 0 until h.length()) equityPoints.add(h.getDouble(i))
        } catch (_: Exception) { positions.clear(); fills.clear(); equityPoints.clear(); cash = 1000.0 }
        if (equityPoints.isEmpty()) equityPoints.add(simulatedInitial)
    }
    private fun getJson(url: String, body: JSONObject? = null, authenticated: Boolean = false): String {
        val con = URL(url).openConnection() as HttpURLConnection
        if (con.url.protocol != "https") error("Nur verschlüsselte HTTPS-Verbindungen sind erlaubt")
        con.connectTimeout = 13000
        con.readTimeout = 15000
        con.setRequestProperty("Accept", "application/json")
        if (authenticated) con.setRequestProperty("Authorization", "Bearer " + backendToken)
        if (body != null) {
            con.requestMethod = "POST"
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val stream = if (con.responseCode in 200..299) con.inputStream else con.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (con.responseCode !in 200..299) {
                val err = runCatching { JSONObject(raw).optString("error") }.getOrDefault(raw)
                error("HTTP " + con.responseCode + " " + err.take(180))
            }
            return raw
        } finally { con.disconnect() }
    }
    private fun backend(path: String, body: JSONObject? = null): JSONObject {
        val base = backendUrl.trim().removeSuffix("/")
        if (!base.startsWith("https://") || backendToken.length < 32) {
            error("HTTPS-Serveradresse und API-Token mit mindestens 32 Zeichen erforderlich")
        }
        return JSONObject(getJson(base + path, body, true))
    }
    private fun async(action: () -> Unit) {
        if (requesting) return
        requesting = true
        thread {
            try { action() }
            catch (e: Exception) {
                main.post { message = "Server: " + (e.message ?: "Unbekannter Fehler").take(180) }
            } finally { main.post { requesting = false } }
        }
    }
    fun connect() = async {
        val status = backend("/api/status")
        main.post {
            serverStatus = status
            message = if (status.optBoolean("live")) "Backend verbunden · REAL aktiviert (Server)" else "Backend verbunden · Live-Ausführung gesperrt"
            save()
        }
        fetchRealHistory()
        refreshRemoteBot()
        refreshRealPositions()
    }
    fun fetchRealHistory() = asyncHistory()
    private fun asyncHistory() {
        thread {
            try {
                val t = backend("/api/trades").getJSONArray("trades")
                main.post {
                    realTrades.clear()
                    for (i in 0 until min(80, t.length())) realTrades.add(t.getJSONObject(i))
                }
            } catch (_: Exception) { }
        }
    }
    fun analyze(c: Coin) {
        if (!c.live || !realMarket) { message = "Für eine Analyse sind echte Marktdaten erforderlich"; return }
        selectedCoin = c
        async {
            val signal = NativeStrategy.analyze(c)
            val safety = if (signal.eligible) {
                try {
                    NativeStrategy.securityReasons(JSONObject(getJson("https://api.rugcheck.xyz/v1/tokens/" +
                        java.net.URLEncoder.encode(c.mint, "UTF-8") + "/report")))
                } catch (err: Exception) { listOf("Tokenprüfung nicht erreichbar") }
            } else emptyList()
            val result = signal.toJson(c.mint)
            if (safety.isNotEmpty()) result.put("action", "HOLD").put("eligible", false)
                .put("reason", "Tokenprüfung: " + safety.joinToString(", "))
            main.post {
                aiSignal = result
                message = "MemePulse Engine: " + result.optString("action", "HOLD") +
                    " · Signalstärke " + signal.score + "/100"
            }
        }
    }
    fun previewBuy(c: Coin) {
        if (!c.live || !realMarket || System.currentTimeMillis() - marketTimestamp > 120000) {
            message = "Live-Daten aktualisieren, bevor ein echter Trade vorbereitet wird"
            return
        }
        selectedCoin = c
        async {
            val amount = tradeSol.toDoubleOrNull() ?: 0.0
            val preview = backend("/api/orders/prepare", JSONObject().put("side", "BUY")
                .put("mint", c.mint).put("amountSol", amount))
            main.post { pendingPreview = preview; message = "Echte Swap-Vorschau erstellt – noch nichts gekauft" }
        }
    }
    fun previewSell(mint: String, percent: Int = 100) = async {
        val preview = backend("/api/orders/prepare", JSONObject().put("side", "SELL")
            .put("mint", mint).put("percent", percent))
        main.post { pendingPreview = preview; message = "Echte Verkaufsvorschau erstellt" }
    }
    fun executePreview() {
        val p = pendingPreview ?: return
        pendingPreview = null
        async {
            val result = backend("/api/orders/execute", JSONObject().put("previewId", p.optString("previewId"))
                .put("confirm", "LIVE TRADE"))
            main.post { message = "Live Swap " + result.optString("status") + " · " + result.optString("signature").take(12) }
            fetchRealHistory()
        }
    }
    fun startAgent() = async {
        val r = backend("/api/agent/start", JSONObject().put("confirm", "ACTIVATE AUTOMATIC LIVE TRADING"))
        main.post { message = "Auto-Trading aktiv – endet nach 30 Minuten"; serverStatus = serverStatus?.apply { put("autoActive", r.optBoolean("active")) } }
    }
    fun stopAgent() = async {
        backend("/api/agent/stop", JSONObject())
        main.post { message = "Auto-Trading deaktiviert"; serverStatus = serverStatus?.apply { put("autoActive", false) } }
    }
    private fun paperLog(note: String) {
        paperBotLastResult = note
        paperBotEvents.add(0, java.text.SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
            .format(java.util.Date()) + " · " + note)
        while (paperBotEvents.size > 40) paperBotEvents.removeAt(paperBotEvents.lastIndex)
    }
    fun togglePaperBot() {
        if (paperBotEnabled) {
            paperBotEnabled = false
            paperLog("KI-Paper-Bot angehalten")
            message = "Paper-Automatik ausgeschaltet"
            return
        }
        if (!paperMode) { message = "Simulation zuerst in den Einstellungen aktivieren"; return }
        if (!realMarket || System.currentTimeMillis() - marketTimestamp > 120000) {
            message = "Zuerst frische Live-Marktdaten im Radar abrufen"
            return
        }
        paperBotEnabled = true
        paperBotLastCheck = 0
        paperLog("KI-Paper-Bot aktiviert · nur Simulation · max. 1 % pro Kauf")
        runPaperBotCycle()
    }
    // Native quant signal + independent RugCheck approval; no external AI model or key.
    fun runPaperBotCycle() {
        if (!paperMode || !paperBotEnabled || paperBotWorking || !realMarket) return
        if (System.currentTimeMillis() - marketTimestamp > 120000) {
            paperLog("Kurse veraltet · keine Orders")
            return
        }
        if (System.currentTimeMillis() - paperBotLastCheck < 60000) return
        paperBotLastCheck = System.currentTimeMillis()
        // Exits are deterministic and always take priority over new signals.
        var sold = false
        for (p in positions.toList()) {
            val coin = coins.find { it.mint == p.mint } ?: continue
            val changePct = (coin.price / p.entry - 1.0) * 100
            if (changePct <= -12.0 || changePct >= 25.0) {
                paperSell(p)
                paperLog("Paper EXIT " + p.symbol + ": " + signed(changePct)
                    + if (changePct <= -12.0) " Stop-Loss" else " Take-Profit")
                sold = true
            }
        }
        if (sold || positions.size >= 3 || cash < equity() * .005) return
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (fills.count { it.side == "BUY" && it.at >= today } >= 3) {
            paperLog("Tageslimit erreicht: maximal 3 Paper-Käufe")
            return
        }
        val recentlyBought = fills.filter { it.side == "BUY" && it.at >= System.currentTimeMillis() - 86400000 }
            .map { it.symbol }.toSet()
        val candidates = coins.filter { it.live && NativeStrategy.analyze(it).eligible &&
            it.symbol !in recentlyBought && positions.none { p -> p.mint == it.mint } }
            .sortedByDescending { NativeStrategy.analyze(it).score }.take(4)
        if (candidates.isEmpty()) { paperLog("HOLD · keine positiven Quant-Signale"); return }
        paperBotWorking = true
        thread {
            try {
                var found = false
                for (coin in candidates) {
                    if (!paperBotEnabled || !paperMode) break
                    val signal = NativeStrategy.analyze(coin)
                    val safety = try {
                        NativeStrategy.securityReasons(JSONObject(getJson(
                            "https://api.rugcheck.xyz/v1/tokens/" +
                                java.net.URLEncoder.encode(coin.mint, "UTF-8") + "/report")))
                    } catch (err: Exception) { listOf("RugCheck nicht erreichbar") }
                    val approved = signal.eligible && safety.isEmpty()
                    main.post {
                        paperLog(coin.symbol + " · Signal " + signal.score + "/100 · " +
                            if (approved) "RugCheck OK" else "HOLD: " + safety.joinToString(", "))
                        if (approved && paperBotEnabled && paperMode &&
                            System.currentTimeMillis() - marketTimestamp < 120000 &&
                            positions.none { it.mint == coin.mint }) {
                            paperBuyAmount(coin, min(9.0, equity() * .009))
                            paperLog("Quant-Engine: PAPER BUY " + coin.symbol + " simuliert")
                        }
                    }
                    if (approved) { found = true; break }
                }
                if (!found) main.post { paperLog("Scan beendet · Sicherheitsfilter verhindert Kauf") }
            } finally { main.post { paperBotWorking = false } }
        }
    }
    fun refreshRemoteBot() {
        if (backendToken.length < 32) return
        thread {
            try {
                val x = backend("/api/agent")
                val events = x.optJSONArray("events") ?: JSONArray()
                main.post {
                    agentEvents.clear()
                    for (i in 0 until min(events.length(), 30)) agentEvents.add(events.getJSONObject(i))
                    botRisk = x.optJSONObject("risk")
                    serverStatus = serverStatus?.apply { put("autoActive", x.optBoolean("active")) }
                }
            } catch (_: Exception) { }
        }
    }
    fun refreshRealPositions() {
        if (backendToken.length < 32) return
        thread {
            try {
                val portfolio = backend("/api/portfolio")
                val wallet = backend("/api/wallet")
                val p = portfolio.optJSONArray("positions") ?: JSONArray()
                main.post {
                    realPositions.clear()
                    for (i in 0 until min(20, p.length())) realPositions.add(p.getJSONObject(i))
                    realSolBalance = if (wallet.isNull("balanceSol")) null else wallet.optDouble("balanceSol")
                }
            } catch (_: Exception) { }
        }
    }

}
