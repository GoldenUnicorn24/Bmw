package de.memepulse.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

private val Background = Color(0xFF090B14)
private val Panel = Color(0xFF151927)
private val Panel2 = Color(0xFF1B2031)
private val Violet = Color(0xFFAB8EFF)
private val Green = Color(0xFF5AE7AC)
private val Red = Color(0xFFFF6D8D)
private val Muted = Color(0xFF9098AD)
private val White = Color(0xFFF5F6FD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 11, 20)
        window.navigationBarColor = android.graphics.Color.rgb(9, 11, 20)
        setContent {
            val store = remember { MemeStore(applicationContext) }
            MemePulse(store)
        }
    }
}
@Composable
private fun MemePulse(s: MemeStore) {
    var showAutoConfirm by remember { mutableStateOf(false) }
    val scheme = darkColorScheme(
        primary = Violet, onPrimary = Background, secondary = Green,
        background = Background, surface = Panel, onSurface = White,
        onBackground = White, error = Red
    )
    LaunchedEffect(s.realMarket) {
        while (!s.realMarket) {
            delay(7000)
            if (s.activePage == 0 || s.activePage == 1) s.simulateDemoTick()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(45000)
            if (s.paperAutoEnabled) {
                s.paperAutoTick()
                if (!s.marketLoading) s.refreshMarket()
            }
            if (s.serverStatus != null) s.refreshLiveStatus()
        }
    }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = Background,
            topBar = { Header(s) },
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    listOf(Triple("⌂", "Dashboard", 0), Triple("⌕", "Radar", 1),
                        Triple("◫", "Positionen", 2), Triple("⚙", "System", 3)).forEach { (icon, title, page) ->
                        NavigationBarItem(selected = s.activePage == page, onClick = { s.activePage = page },
                            icon = { Text(icon, fontSize = 22.sp) }, label = { Text(title, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Violet,
                                selectedTextColor = Violet, indicatorColor = Panel2,
                                unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (s.activePage) {
                    0 -> Dashboard(s, onAutoConfirm = { showAutoConfirm = true })
                    1 -> Radar(s)
                    2 -> Positions(s)
                    else -> Settings(s, onAutoConfirm = { showAutoConfirm = true })
                }
            }
        }
        s.candidateReview?.let { review ->
            AlertDialog(onDismissRequest = { s.candidateReview = null }, containerColor = Panel2,
                title = { Text("KI-TOKEN SICHERHEITSCHECK", color = White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mint: " + review.optString("mint"), color = White, fontSize = 11.sp)
                        val safety = review.optJSONObject("safety")
                        Text("RugCheck: " + (safety?.optInt("score")?.toString() ?: "nicht verfügbar"), color = Muted)
                        Text("Liquidität: " + usd(review.optJSONObject("market")?.optDouble("liquidityUsd", 0.0) ?: 0.0), color = Muted)
                        Text("Markt-Score: " + (review.optJSONObject("market")?.optInt("score") ?: "siehe Radar"), color = Muted)
                        Text(if (review.optBoolean("approved")) "Die technischen Mindestprüfungen sind erfüllt. Dies ist keine Garantie gegen Rug Pulls." else
                            "NICHT FREIGEGEBEN: " + (review.optJSONArray("reasons")?.optString(0) ?: "Sicherheitsregel blockiert"),
                            color = if (review.optBoolean("approved")) Green else Red, fontSize = 12.sp)
                        Text("Durch Freigabe kann der KI-Agent diesen Token im aktiven 30-Minuten-Live-Modus mit echtem Geld kaufen.", color = Red, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    if (review.optBoolean("approved")) Button(onClick = { s.approveToken() },
                        colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text("Für KI-Trades freigeben", color = Background)
                    }
                },
                dismissButton = { TextButton(onClick = { s.candidateReview = null }) { Text("Abbrechen") } })
        }
        s.pendingPreview?.let { q ->
            AlertDialog(onDismissRequest = { s.pendingPreview = null },
                containerColor = Panel2,
                title = { Text("ECHTER Blockchain-Trade", color = White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("Du bewegst echtes Geld. Diese Transaktion ist nach Bestätigung nicht widerrufbar.", color = Red)
                        Text("Richtung: " + q.optString("side"), color = White)
                        Text("Token: " + q.optString("mint").take(15) + "…", color = White)
                        Text("Input (Atomics): " + q.optString("inputAtomic"), color = Muted)
                        Text("Geschätzte Ausgabe (Atomics): " + q.optString("outputAtomic"), color = Muted)
                        Text("Slippage-Grenze: " + q.optInt("slippageBps") / 100.0 + " %", color = Muted)
                        Text("Quote läuft nach 30 Sekunden ab. Es gibt keine Gewinngarantie.", color = Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { s.executePreview() }, colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                        Text("Echtgeld-Trade bestätigen", color = Background)
                    }
                },
                dismissButton = { TextButton(onClick = { s.pendingPreview = null }) { Text("Abbrechen") } })
        }
        if (showAutoConfirm) {
            AlertDialog(onDismissRequest = { showAutoConfirm = false }, containerColor = Panel2,
                title = { Text("KI-Autotrading aktivieren?", color = White) },
                text = { Text("Der Server kann dann bis zu 30 Minuten lang ohne jede einzelne Bestätigung ECHTE Token kaufen. Strikte Einsatzlimits und Allowlist gelten. Verluste bis zum eingesetzten Betrag sind möglich.", color = White) },
                confirmButton = { Button(onClick = { showAutoConfirm = false; s.startAgent() },
                    colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("30 Minuten Echtgeld-Auto aktivieren", color = Background) } },
                dismissButton = { TextButton(onClick = { showAutoConfirm = false }) { Text("Nein") } })
        }
    }
}
@Composable
private fun Header(s: MemeStore) {
    Column(Modifier.fillMaxWidth().background(Background).padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(35.dp).background(Brush.linearGradient(listOf(Violet, Color(0xFF6250B8))),
                    RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text("◈", color = White, fontSize = 24.sp)
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("MEMEPULSE", fontWeight = FontWeight.Black, color = White, letterSpacing = 1.3.sp, fontSize = 17.sp)
                    Text("AI TRADING TERMINAL", fontSize = 9.sp, color = Muted, letterSpacing = 1.6.sp)
                }
            }
            Surface(color = if (s.paperMode) Panel2 else Color(0xFF38262C), shape = RoundedCornerShape(12.dp)) {
                Text(if (s.paperMode) "● PAPER" else "● REAL MODE", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (s.paperMode) Green else Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(s.message, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable
private fun Dashboard(s: MemeStore, onAutoConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusRibbons(s)
        AgentCommandCenter(s, onAutoConfirm)
        if (s.serverStatus != null) LiveWalletPanel(s)
        SectionHeader("Paper Performance", "BACKTEST / SIMULATION")
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF2B2344), Panel)), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFF50426C), RoundedCornerShape(22.dp)).padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Caption("PAPER PORTFOLIO", color = Muted)
                Caption(if (s.realMarket) "● LIVE-KURSE" else "● DEMO-KURSE", color = if (s.realMarket) Green else Violet)
            }
            Spacer(Modifier.height(8.dp))
            Text(usd(s.equity()), color = White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            val net = (s.equity() / 1000.0 - 1.0) * 100
            Text(signed(net) + " seit Start · Simuliert", color = if (net >= 0) Green else Red, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            EquityChart(s.equityPoints.toList() + s.equity())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Caption("Kein Bank- oder Wallet-Guthaben", color = Muted)
                Caption("SIMULATION", color = Violet)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Metric("REALISIERT", usd(s.realized()), if (s.realized() >= 0) Green else Red) }
            Box(Modifier.weight(1f)) { Metric("OFFENE P&L", usd(s.unrealized()), if (s.unrealized() >= 0) Green else Red) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Metric("WIN RATE", signed(s.winrate()).removePrefix("+"), White) }
            Box(Modifier.weight(1f)) { Metric("POSITIONEN", s.positions.size.toString() + " / 3", White) }
        }
        PaperAutomationPanel(s)
        SectionHeader("Market Radar", if (s.realMarket) "DEX SCREENER · PROMOTED FEED" else "DEMO")
        s.coins.sortedByDescending { it.score }.take(5).forEach { TokenCard(it, s, compact = true) }
        Button(onClick = { s.activePage = 1 }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Panel2)) { Text("Alle Token anzeigen", color = Violet) }
    }
}
@Composable
private fun EquityChart(values: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(125.dp)) {
        val v = if (values.size > 1) values else listOf(1000.0, 1000.0)
        val lo = v.minOrNull() ?: 0.0
        val hi = v.maxOrNull() ?: 1000.0
        val diff = max(1.0, hi - lo)
        val path = Path()
        v.forEachIndexed { i, d ->
            val x = i.toFloat() / (v.size - 1) * size.width
            val y = (size.height - 8f) * (1f - ((d - lo) / diff).toFloat()) + 4f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in 1..3) drawLine(Color(0xFF37344E), Offset(0f, size.height * i / 4f),
            Offset(size.width, size.height * i / 4f), 1f)
        drawPath(path, Violet, style = Stroke(width = 4f))
    }
}
@Composable
private fun Radar(s: MemeStore) {
    var query by remember { mutableStateOf("") }
    var minScore by remember { mutableStateOf(false) }
    val shown = s.coins.filter { it.symbol.contains(query, true) || it.name.contains(query, true) || it.mint.contains(query, true) }
        .filter { !minScore || it.score >= 75 }.sortedByDescending { it.score }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        StatusRibbons(s)
        SectionHeader("Token entdecken", if (s.realMarket) "SOLANA · AKTUELLE KURSE" else "OFFLINE DEMO")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { s.refreshMarket() }, enabled = !s.marketLoading, modifier = Modifier.weight(1f)) {
                Text(if (s.marketLoading) "Lade ..." else "↻ Live-Kurse abrufen", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { s.useDemo() }) { Text("Demo", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(7.dp))
        Text("Die Live-Liste stammt aus einem Feed mit beworbenen Coins – keine sichere Kaufempfehlung.",
            color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Coin oder Mint-Adresse suchen") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = minScore, onCheckedChange = { minScore = it })
            Text("Nur Markt-Score ≥ 75", color = White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Caption(shown.size.toString() + " TOKEN", color = Muted)
        }
        s.aiSignal?.let {
            Surface(color = Panel2, shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("GROK: " + it.optString("action", "HOLD"), color = if (it.optString("action") == "BUY") Green else Violet, fontWeight = FontWeight.Bold)
                    Text(it.optString("reason", "Keine Analyse"), color = Muted, fontSize = 12.sp)
                    Text("Nicht als Kaufempfehlung oder Sicherheitsgarantie verstehen.", color = Muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(shown, key = { it.mint }) { coin -> TokenCard(coin, s, compact = false) }
        }
    }
}
@Composable
private fun TokenCard(c: Coin, s: MemeStore, compact: Boolean) {
    Surface(color = Panel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(40.dp).background(Panel2, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center) { Text(c.symbol.take(1).uppercase(), color = Violet, fontWeight = FontWeight.Black, fontSize = 19.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.symbol, color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(c.name, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(price(c.price), color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(signed(c.change1h), color = if (c.change1h >= 0) Green else Red, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Caption("LIQ  " + usd(c.liquidity), color = Muted)
                Caption("VOL  " + usd(c.volume), color = Muted)
                Caption(c.score.toString() + "/100", color = if (c.score >= 75) Green else Violet)
            }
            LinearProgressIndicator(progress = { c.score.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp), color = if (c.score >= 75) Green else Violet,
                trackColor = Panel2)
            if (!compact) {
                Caption(if (c.live) "MARKT-SCORE ≠ RUGCHECK-AUDIT" else "SIMULIERTE MARKTDATEN", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { if (s.paperMode) s.paperBuy(c) else s.previewBuy(c) },
                        enabled = !s.requesting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (s.paperMode) Violet else Red)) {
                        Text(if (s.paperMode) "Paper Buy" else "REAL Vorschau", color = Background, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { s.analyze(c) }, enabled = c.live && !s.requesting) {
                        Text("Grok prüfen", fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = { s.reviewToken(c) }, enabled = c.live && !s.requesting,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("◉ RugCheck + für KI-Freigabe prüfen", fontSize = 11.sp)
                }
            }
        }
    }
}
@Composable
private fun Positions(s: MemeStore) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Echtgeld-Wallet", "SOLANA · SERVER")
        LiveWalletPanel(s)
        SectionHeader("Echte Positionen", "AUS BESTÄTIGTEN SWAPS")
        LivePositionsPanel(s)
        SectionHeader("Paper-Portfolio", s.positions.size.toString() + " OFFENE POSITIONEN")
        Text("Papier-Guthaben: " + usd(s.cash) + " · simulierte Kosten: 0,8 % je Seite", color = Muted, fontSize = 12.sp)
        if (s.positions.isEmpty()) Hint("Noch keine Paper-Positionen. Öffne den Token-Radar.")
        s.positions.toList().forEach { pos ->
            val coin = s.coins.find { it.mint == pos.mint }
            val mark = coin?.price ?: pos.entry
            val value = pos.quantity * mark * .992
            val pnl = value - pos.invested
            Surface(color = Panel, shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(pos.symbol, color = White, fontWeight = FontWeight.Bold)
                        Text(usd(pnl), color = if (pnl >= 0) Green else Red, fontWeight = FontWeight.Bold)
                    }
                    Caption("Einstieg " + price(pos.entry) + " · Wert nach Kosten " + usd(value), color = Muted)
                    OutlinedButton(onClick = { s.paperSell(pos) }) { Text("Paper Sell 100 %") }
                }
            }
        }
        SectionHeader("Echte Order-Historie", "NUR BACKEND")
        OutlinedButton(onClick = { s.fetchRealHistory() }) { Text("Server-Journal aktualisieren") }
        if (s.realTrades.isEmpty()) Hint("Keine bestätigten Live-Transaktionen vom verbundenen Server geladen.")
        s.realTrades.forEach { t ->
            Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(t.optString("side") + " · " + t.optString("status") + " · " + t.optString("source"),
                        color = if (t.optString("status") == "SUCCESS") Green else White, fontWeight = FontWeight.Bold)
                    Caption(t.optString("mint").take(18) + "…", color = Muted)
                    Caption("TX: " + t.optString("signature", "unbekannt").take(24), color = Muted)
                    if (t.optString("side") == "BUY" && t.optString("status") == "SUCCESS")
                        TextButton(onClick = { s.previewSell(t.optString("mint")) }) { Text("Echten Bestand verkaufen (Vorschau)", color = Red) }
                }
            }
        }
        SectionHeader("KI-Entscheidungen", "BOT ACTIVITY")
        BotHistory(s)
        SectionHeader("Paper-Journal", s.fills.size.toString() + " ORDERS")
        s.fills.take(30).forEach { f ->
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.side, color = if (f.side == "BUY") Violet else Green, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(f.symbol, color = White, modifier = Modifier.weight(1f))
                Text(usd(f.amount), color = Muted)
            }
        }
    }
}
@Composable
private fun Settings(s: MemeStore, onAutoConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatusRibbons(s)
        PaperAutomationPanel(s)
        SectionHeader("Trading Modus", "DEFAULT: PAPER")
        Surface(color = Panel, shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Trades: " + if (s.paperMode) "PAPER / SIMULATION" else "REAL / ECHTES GELD",
                    color = if (s.paperMode) Green else Red, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { s.paperMode = true }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (s.paperMode) Violet else Panel2)) {
                        Text("Paper", color = if (s.paperMode) Background else White)
                    }
                    Button(onClick = { s.paperMode = false }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (!s.paperMode) Red else Panel2)) {
                        Text("Real-Modus", color = if (!s.paperMode) Background else White)
                    }
                }
                Text("Der Real-Modus aktiviert keinen Handel allein. Jeder manuelle Swap braucht weiterhin eine Quote und Bestätigung. Der Server muss Echtgeldhandel erlauben.", color = Muted, fontSize = 11.sp)
                OutlinedTextField(s.tradeUsd, onValueChange = { s.tradeUsd = it }, label = { Text("Paper-Einsatz USD (max. 1 % Equity)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.tradeSol, onValueChange = { s.tradeSol = it }, label = { Text("REAL-Einsatz SOL (Serverlimit gilt)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { s.resetPaper() }) { Text("Paper-Konto zurücksetzen") }
            }
        }
        SectionHeader("Backend verbinden", "HTTPS / AUTH")
        Surface(color = Panel, shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dein Server hält die Wallet-Schlüssel. In der App werden keine Seed-Phrasen gespeichert.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(s.backendUrl, onValueChange = { s.backendUrl = it }, label = { Text("https://dein-server.de") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.backendToken, onValueChange = { s.backendToken = it }, label = { Text("Bearer API-Token (nur im Arbeitsspeicher)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { s.connect() }, enabled = !s.requesting, modifier = Modifier.fillMaxWidth()) { Text("Verbindung prüfen") }
                Text(if (s.serverStatus?.optBoolean("live") == true) "● LIVE EXECUTION SERVERSEITIG FREIGEGEBEN" else "○ LIVE EXECUTION NICHT FREIGEGEBEN",
                    color = if (s.serverStatus?.optBoolean("live") == true) Red else Muted, fontSize = 11.sp)
                s.serverStatus?.let {
                    Caption("WALLET: " + it.optString("wallet", "nicht verbunden").take(24) + "…", color = Muted)
                    Caption("Max je Kauf: " + it.optDouble("maxTradeSol", 0.0) + " SOL · Tagesbudget: " + it.optDouble("maxDailySpendSol", 0.0) + " SOL", color = Muted)
                    Caption("Allowlist: " + (it.optJSONArray("whitelistedMints")?.length() ?: 0) + " geprüfte Coins", color = Muted)
                }
            }
        }
        SectionHeader("KI-Freigabeliste", "NUR GEPRÜFTE TOKEN")
        val allowed = s.serverStatus?.optJSONArray("whitelistedMints")
        if (allowed == null || allowed.length() == 0) Hint("Noch keine Coins freigegeben. Im Live-Radar einen Coin auswählen und Sicherheitscheck öffnen.")
        if (allowed != null) for (i in 0 until allowed.length()) {
            val mint = allowed.optString(i)
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(mint.take(10) + "…" + mint.takeLast(5), color = White, fontSize = 12.sp)
                    TextButton(onClick = { s.removeToken(mint) }, enabled = !s.requesting) { Text("Entfernen", color = Red) }
                }
            }
        }
        SectionHeader("KI-Trade-Automation", "STANDARD: AUS")
        Surface(color = Panel, shape = RoundedCornerShape(17.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (s.serverStatus?.optBoolean("autoActive") == true) "● AUTO-LIVE IST AKTIV" else "○ AUTO-LIVE IST AUS",
                    color = if (s.serverStatus?.optBoolean("autoActive") == true) Red else Green, fontWeight = FontWeight.Bold)
                Text("Nur freigegebene Token. Grok schlägt Trades vor; feste Regeln entscheiden über Zulassung. Automatik läuft nur auf einem gesondert gestarteten Server und endet nach 30 Minuten.", color = Muted, fontSize = 12.sp)
                Button(onClick = onAutoConfirm, enabled = s.serverStatus?.optBoolean("autoAllowed") == true && !s.requesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) {
                    Text("KI-Live-Modus 30 Min. aktivieren", color = Background)
                }
                OutlinedButton(onClick = { s.stopAgent() }, enabled = !s.requesting, modifier = Modifier.fillMaxWidth()) {
                    Text("■ NOT-AUS: Auto-Bot stoppen", color = Red)
                }
                Caption("Zusätzlicher Not-Aus: STOP-Datei auf dem Server anlegen.", color = Muted)
            }
        }
        Hint("Meme-Coins sind hochspekulativ. Keine KI kann einen Gewinn garantieren. Die Marktmetrik ist kein unabhängiges Sicherheits-Audit.")
    }
}
@Composable
private fun Metric(label: String, value: String, color: Color) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp)) {
            Caption(label, color = Muted)
            Spacer(Modifier.height(8.dp))
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}
@Composable
private fun SectionHeader(title: String, kicker: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(kicker, color = Muted, fontSize = 9.sp)
    }
}
@Composable
private fun Caption(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, letterSpacing = .25.sp)
}
@Composable
private fun Hint(text: String) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
    }
}
