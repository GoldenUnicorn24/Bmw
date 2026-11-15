package de.memepulse.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ink = Color(0xFF070B16)
private val tile = Color(0xFF121A2B)
private val tile2 = Color(0xFF19243B)
private val bright = Color(0xFFB69AFF)
private val mint = Color(0xFF41E5AA)
private val danger = Color(0xFFFF6F91)
private val secondary = Color(0xFFA0AFC7)
private val white = Color(0xFFF7F9FF)

@Composable
fun BotScreen(s: MemeStore, onAutoConfirm: () -> Unit) {
    val engineReady = s.serverStatus?.optBoolean("engineConnected") == true
    val clipboard = LocalClipboardManager.current
    val live = s.serverStatus?.optBoolean("live") == true
    val auto = s.serverStatus?.optBoolean("autoActive") == true
    val autoAllowed = s.serverStatus?.optBoolean("autoAllowed") == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(
            listOf(Color(0xFF33224D), Color(0xFF18263D), Color(0xFF101C2B))),
            RoundedCornerShape(25.dp))
            .border(1.dp, Color(0xFF554B79), RoundedCornerShape(25.dp))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                MiniLabel("◈   AI ENGINE / CONTROL CENTER", bright)
                MiniLabel("v0.7  DUAL RESEARCH", secondary)
            }
            Spacer(Modifier.height(1.dp))
            Text("Trade smarter.", color = white, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
            Text("Mehrfach bestätigte Signale · flexible Risikobudgets · keine Gewinngarantie",
                color = secondary, fontSize = 12.sp, lineHeight = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusPill(if (engineReady) "● ENGINE / SERVER" else "● ENGINE / GERÄT", if (engineReady) mint else bright)
                StatusPill(if (s.realMarket) "● LIVE-MARKT" else "○ KURSE FEHLEN", if (s.realMarket) mint else bright)
            }
        }
        if (s.paperMode) {
        Text("01   KI-PAPER-TRADING", color = white, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 0.6.sp)
        Surface(color = tile, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("MemePulse Paper Bot", color = white, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text(if (s.paperBotEnabled) "Automatische simulierte Trades laufen"
                            else "Echtgeldfrei Strategien testen", color = secondary, fontSize = 11.sp)
                    }
                    StatusPill(if (s.paperBotEnabled) "AKTIV" else "AUS",
                        if (s.paperBotEnabled) mint else secondary)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2B3650)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BotMetric("EINSATZ / TRADE", "≤ 1 %", Modifier.weight(1f))
                    BotMetric("STOP-LOSS", "−12 %", Modifier.weight(1f))
                    BotMetric("TAKE-PROFIT", "+25 %", Modifier.weight(1f))
                }
                Text("Jeder simulierte Kauf benötigt ein positives internes Quant-Signal und eine aktuelle RugCheck-Prüfung. Maximal drei Positionen und drei Käufe pro Tag. Der Bot läuft nur bei aktiver App.",
                    color = secondary, fontSize = 11.sp, lineHeight = 16.sp)
                Button(onClick = { s.togglePaperBot() }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = s.paperBotEnabled || (s.realMarket && !s.marketLoading),
                    colors = ButtonDefaults.buttonColors(containerColor = if (s.paperBotEnabled) tile2 else mint),
                    shape = RoundedCornerShape(15.dp)) {
                    Text(if (s.paperBotEnabled) "■  PAPER-BOT STOPPEN" else "▶  KI-PAPER-BOT STARTEN",
                        color = if (s.paperBotEnabled) white else ink, fontWeight = FontWeight.Bold)
                }
                Text("Die Signal-Engine läuft ohne externen KI-Anbieter.", color = mint, fontSize = 11.sp)
                if (!s.realMarket) Text("Im Radar zuerst echte Kurse abrufen. Ohne Kursdaten gibt es keine Order.",
                    color = bright, fontSize = 11.sp)
            }
        }
        }
        if (!s.paperMode) {
        Text("01   AUTOPILOT & EINZAHLUNG", color = white, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = .6.sp)
        Surface(color = tile, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("MemePulse Autopilot 2", color = white, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("Kauft und verkauft über Jupiter / Solana", color = secondary, fontSize = 11.sp)
                    }
                    StatusPill(if (auto) "● LIVE" else "● GESPERRT",
                        if (auto) danger else secondary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    BotMetric("TRADE-LIMIT", s.serverStatus?.optDouble("maxTradeSol", 0.0)?.toString()?.plus(" SOL") ?: "–", Modifier.weight(1f))
                    BotMetric("TAGESLIMIT", s.serverStatus?.optDouble("maxDailySpendSol", 0.0)?.toString()?.plus(" SOL") ?: "–", Modifier.weight(1f))
                }
                BotLine("Server Live-Trading", if (live) "FREIGEGEBEN" else "AUS", if (live) danger else mint)
                BotLine("KI-Automatik", if (autoAllowed) "KONFIGURIERT" else "AUS", if (autoAllowed) danger else mint)
                BotLine("Wallet", s.serverStatus?.optString("wallet")?.take(9)?.plus("…") ?: "Nicht verbunden", white)
                Text("EINZAHLUNG / NUR SOLANA MAINNET", color = bright, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                OutlinedButton(onClick = { s.refreshDeposit() }, modifier = Modifier.fillMaxWidth()) {
                    Text("↻ Eigene Einzahlungsadresse anzeigen", color = bright)
                }
                if (s.depositAddress.isNotBlank()) {
                    Text(s.depositAddress, color = white, fontSize = 12.sp, lineHeight = 18.sp)
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.depositAddress)) },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Adresse kopieren", color = mint)
                    }
                    Text("Balance: " + (s.depositBalance?.let {
                        String.format(Locale.US, "%.5f SOL", it)
                    } ?: "nicht verfügbar"), color = white, fontSize = 12.sp)
                } else {
                    Text("Noch keine echte Wallet-Adresse: Server und separate Hot Wallet einrichten.",
                        color = secondary, fontSize = 11.sp)
                }
                Text("NUR native SOL an eine zuvor von dir kontrollierte, unabhängig geprüfte Adresse senden. Nie SOL an unbekannte Adressen einzahlen. Eine Einzahlung startet keine Trades.",
                    color = danger, fontSize = 11.sp, lineHeight = 15.sp)
                Text("WICHTIG: Noch keine Auszahlungsfunktion in dieser Beta. Nicht einzahlen, bevor du selbst einen überprüften, sicheren Weg zur Rücküberweisung aus deiner Wallet besitzt.",
                    color = danger, fontSize = 11.sp, lineHeight = 15.sp)
                s.botRisk?.let { r ->
                    BotLine("Risikostopp", if (r.optBoolean("halted")) "AKTIV" else "NICHT AUSGELÖST",
                        if (r.optBoolean("halted")) danger else mint)
                    BotLine("Realisierte P&L heute", String.format(Locale.US, "%.5f SOL", r.optDouble("dailyRealizedPnlSol", 0.0)),
                        if (r.optDouble("dailyRealizedPnlSol") >= 0) mint else danger)
                }
                s.strategyHealth?.let { health ->
                    BotLine("Erfahrung", health.optInt("trades", 0).toString() + " abgeschlossene Trades", white)
                    BotLine("Strategie-Wächter",
                        if (health.optBoolean("halted", health.optBoolean("halt", false))) "KAUFSTOPP"
                        else health.optString("status", "Noch keine Daten"),
                        if (health.optBoolean("halt", false)) danger else secondary)
                }
                Text("Zuerst mehrere Kursbeobachtungen, Liquiditätskontrolle und Risikobudget. Exits sind zeitlich begrenzt; nach Sessionende bis zu 2 Stunden Exit-Only-Prüfung; danach keine laufende Überwachung. Nicht für unbeaufsichtigten Echtgeldbetrieb auditiert.",
                    color = secondary, fontSize = 11.sp, lineHeight = 16.sp)
                Button(onClick = onAutoConfirm, enabled = autoAllowed && live && engineReady && !auto && !s.requesting,
                    modifier = Modifier.fillMaxWidth().height(47.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = danger),
                    shape = RoundedCornerShape(15.dp)) {
                    Text("AUTOPILOT BIS ZU 24 H AKTIVIEREN", fontSize = 12.sp, fontWeight = FontWeight.Black, color = ink)
                }
                OutlinedButton(onClick = { s.stopAgent() }, enabled = !s.requesting,
                    modifier = Modifier.fillMaxWidth()) { Text("■  NOT-AUS / AUTO STOPPEN", color = danger, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { s.refreshRemoteBot(); s.refreshRealPositions() },
                    modifier = Modifier.fillMaxWidth(), enabled = !s.requesting) {
                    Text("↻  Serverstatus & Wallet aktualisieren", color = bright)
                }
            }
        }
        }
        Text("TRAINING LAB · AUSWERTUNG", color = white, fontWeight = FontWeight.Black,
            fontSize = 15.sp, letterSpacing = .6.sp)
        Surface(color = tile, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Meme- & Penny-Stock-Training", color = white,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    StatusPill("NUR BEOBACHTUNG", bright)
                }
                val studies = s.marketResearchReport?.optJSONObject("markets")
                if (studies != null) {
                    listOf(
                        "SOLANA MEME-COINS" to "solanaMemeCoins",
                        "US PENNY STOCKS" to "usPennyStocks"
                    ).forEach { (label, id) ->
                        val study = studies.optJSONObject(id)
                        Column(Modifier.fillMaxWidth().background(tile2, RoundedCornerShape(13.dp))
                            .padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, color = bright, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(study?.optString("status", "KEINE DATEN") ?: "KEIN BERICHT",
                                color = if (study?.optString("status") == "RESEARCH_GATE_PASSED") mint else secondary,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Marktbeispiele: " + (study?.optInt("sampleCount", 0) ?: 0),
                                color = white, fontSize = 12.sp)
                            val results = study?.optJSONObject("metrics")
                            val future = results?.optJSONObject("chronological")
                                ?: results?.optJSONObject("later")
                            val untrained = results?.optJSONObject("unseenPools")
                            if (future != null) {
                                Text("Späterer Zeitraum: " + future.optInt("samples", 0) +
                                    " Datenpunkte · " + future.optInt("signals", 0) + " Signale",
                                    color = secondary, fontSize = 11.sp)
                            }
                            if (untrained != null) {
                                Text("Ungesehene Coins/Aktien: " + untrained.optInt("samples", 0) +
                                    " Datenpunkte", color = secondary, fontSize = 11.sp)
                            }
                            if (id == "usPennyStocks") {
                                Text("Nur Forschung – kein Aktienbroker verbunden, keine echten Aktienkäufe.",
                                    color = secondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
                val report = s.researchReport
                val status = report?.optString("status") ?: "KEIN BERICHT VERBUNDEN"
                BotLine("Validierungsstatus", status.take(26), if (status == "RESEARCH_GATE_PASSED") mint else secondary)
                BotLine("Marktbeispiele", (report?.optInt("sampleCount", 0) ?: 0).toString(), white)
                val stats = report?.optJSONObject("metrics")
                val forward = stats?.optJSONObject("chronological")
                val unseen = stats?.optJSONObject("unseenPools")
                if (forward != null) {
                    BotLine("Späterer Zeitraum", forward.optInt("samples", 0).toString() + " Beispiele", white)
                    BotLine("Treffer nach Kosten-Simulation",
                        if (forward.isNull("precision")) "Nicht bestimmbar"
                        else String.format(Locale.US, "%.1f %%", forward.optDouble("precision") * 100), white)
                }
                if (unseen != null) {
                    BotLine("Ungelesene Pools", unseen.optInt("samples", 0).toString() + " Beispiele", white)
                }
                Text("Diese Auswertung wird von deinem Server geladen. Historische Kerzendaten sind keine ausführbaren Swaps. Das Forschungsmodell darf keine Echtgeld-Order autorisieren.",
                    color = secondary, fontSize = 11.sp, lineHeight = 16.sp)
                OutlinedButton(onClick = { s.refreshResearchStatus() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("↻ Trainingsbericht laden", color = bright)
                }
            }
        }
        Text("02   BOT-AKTIVITÄTEN", color = white, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = .6.sp)
        Surface(color = tile, shape = RoundedCornerShape(19.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                if (s.paperMode) {
                MiniLabel("PAPER · NATIVE SIGNALS", bright)
                if (s.paperBotEvents.isEmpty()) {
                    Text("Noch keine Paper-Bot-Entscheidungen.", color = secondary, fontSize = 12.sp)
                } else {
                    s.paperBotEvents.take(8).forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("◉", color = mint, fontSize = 11.sp)
                            Spacer(Modifier.width(9.dp))
                            Text(item, color = white, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
                }
                if (!s.paperMode) {
                MiniLabel("ECHTGELD · SERVER-EVENTS", danger)
                if (s.agentEvents.isEmpty()) Text("Keine Live-Bot-Events geladen.", color = secondary, fontSize = 12.sp)
                else s.agentEvents.take(8).forEach { e ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("◉", color = bright, fontSize = 11.sp)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(e.optString("event"), color = white, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(e.optString("details"), color = secondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
@Composable private fun StatusPill(label: String, color: Color) {
    Surface(color = color.copy(alpha = .12f), shape = RoundedCornerShape(9.dp)) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp))
    }
}
@Composable private fun MiniLabel(label: String, color: Color) {
    Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .7.sp)
}
@Composable private fun BotMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(tile2, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 9.sp, color = secondary, maxLines = 1)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = white, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun BotLine(label: String, value: String, tint: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = secondary, fontSize = 12.sp)
        Text(value, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
