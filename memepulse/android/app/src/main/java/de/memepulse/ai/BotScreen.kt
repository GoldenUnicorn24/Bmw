package de.memepulse.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    val grok = s.serverStatus?.optBoolean("grokConnected") == true
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
                MiniLabel("v0.3  BETA", secondary)
            }
            Spacer(Modifier.height(1.dp))
            Text("Trade smarter.", color = white, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
            Text("Analyse durch Grok · Schutzregeln im Server · keine Gewinngarantie",
                color = secondary, fontSize = 12.sp, lineHeight = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusPill(if (grok) "● GROK VERBUNDEN" else "○ GROK OFFLINE", if (grok) mint else secondary)
                StatusPill(if (s.realMarket) "● LIVE-MARKT" else "○ DEMO", if (s.realMarket) mint else bright)
            }
        }
        Text("01   KI-PAPER-TRADING", color = white, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 0.6.sp)
        Surface(color = tile, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Grok Paper Bot", color = white, fontWeight = FontWeight.Bold, fontSize = 19.sp)
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
                Text("Pro Kauf: AI-Signal von Grok, aktueller DEX-Kurs und serverseitiger RugCheck-Filter erforderlich. Höchstens drei offene Paper-Positionen. Die App handelt nur, solange sie aktiv ist.",
                    color = secondary, fontSize = 11.sp, lineHeight = 16.sp)
                Button(onClick = { s.togglePaperBot() }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = s.paperBotEnabled || (grok && s.realMarket && !s.marketLoading),
                    colors = ButtonDefaults.buttonColors(containerColor = if (s.paperBotEnabled) tile2 else mint),
                    shape = RoundedCornerShape(15.dp)) {
                    Text(if (s.paperBotEnabled) "■  PAPER-BOT STOPPEN" else "▶  KI-PAPER-BOT STARTEN",
                        color = if (s.paperBotEnabled) white else ink, fontWeight = FontWeight.Bold)
                }
                if (!grok) Text("Verbinde zuerst im System-Tab deinen Grok-Server. Ohne API gibt es bewusst keine vorgetäuschten KI-Käufe.",
                    color = bright, fontSize = 11.sp)
                if (!s.realMarket) Text("Im Radar zuerst echte Kurse abrufen; Demo-Coins werden vom KI-Bot ignoriert.",
                    color = bright, fontSize = 11.sp)
            }
        }
        Text("02   LIVE-TRADING-SERVER", color = white, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = .6.sp)
        Surface(color = tile, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Autonomer Echtgeld-Bot", color = white, fontWeight = FontWeight.Bold, fontSize = 19.sp)
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
                s.botRisk?.let { r ->
                    BotLine("Risikostopp", if (r.optBoolean("halted")) "AKTIV" else "NICHT AUSGELÖST",
                        if (r.optBoolean("halted")) danger else mint)
                    BotLine("Realisierte P&L heute", String.format(Locale.US, "%.5f SOL", r.optDouble("dailyRealizedPnlSol", 0.0)),
                        if (r.optDouble("dailyRealizedPnlSol") >= 0) mint else danger)
                }
                Text("Sicherheit: Nur freigegebene Mint-Adressen, Handelslimits und zeitlich begrenzte Aktivierung. Stop-Loss ist nicht garantiert. Nach Session-Ende läuft keine automatische Verkaufsüberwachung weiter.",
                    color = secondary, fontSize = 11.sp, lineHeight = 16.sp)
                Button(onClick = onAutoConfirm, enabled = autoAllowed && live && grok && !auto && !s.requesting,
                    modifier = Modifier.fillMaxWidth().height(47.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = danger),
                    shape = RoundedCornerShape(15.dp)) {
                    Text("KI-ECHTGELD-BOT 30 MIN. AKTIVIEREN", fontSize = 12.sp, fontWeight = FontWeight.Black, color = ink)
                }
                OutlinedButton(onClick = { s.stopAgent() }, enabled = !s.requesting,
                    modifier = Modifier.fillMaxWidth()) { Text("■  NOT-AUS / AUTO STOPPEN", color = danger, fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { s.refreshRemoteBot(); s.refreshRealPositions() },
                    modifier = Modifier.fillMaxWidth(), enabled = !s.requesting) {
                    Text("↻  Serverstatus & Wallet aktualisieren", color = bright)
                }
            }
        }
        Text("03   BOT-AKTIVITÄTEN", color = white, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = .6.sp)
        Surface(color = tile, shape = RoundedCornerShape(19.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                MiniLabel("PAPER · GROK SIGNALS", bright)
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
                HorizontalDivider(color = Color(0xFF28344D))
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
