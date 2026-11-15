package de.memepulse.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0B0C15)
private val Card = Color(0xFF171928)
private val CardEdge = Color(0xFF303147)
private val Purple = Color(0xFFB294FF)
private val Cyan = Color(0xFF72DFEB)
private val Mint = Color(0xFF63E4B6)
private val Danger = Color(0xFFFF718F)
private val Soft = Color(0xFF9DA5BC)
private val PrimaryText = Color(0xFFF5F5FC)

@Composable
fun StatusRibbons(s: MemeStore) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatusTag(if (s.realMarket) "● DEX LIVE" else "○ DEMO", if (s.realMarket) Mint else Purple, Modifier.weight(1f))
        StatusTag(
            if (s.serverStatus == null) "○ API OFFLINE" else "● API ONLINE",
            if (s.serverStatus == null) Soft else Cyan, Modifier.weight(1f))
        StatusTag(if (s.serverStatus?.optBoolean("autoActive") == true) "● AGENT RUNS" else "○ AGENT OFF",
            if (s.serverStatus?.optBoolean("autoActive") == true) Danger else Soft, Modifier.weight(1f))
    }
}
@Composable
private fun StatusTag(text: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Card, shape = RoundedCornerShape(11.dp)) {
        Text(text, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp))
    }
}
@Composable
fun AgentCommandCenter(s: MemeStore, onAutoConfirm: () -> Unit) {
    val running = s.serverStatus?.optBoolean("autoActive") == true
    val connected = s.serverStatus != null
    Column(
        modifier = Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(Color(0xFF292241), Color(0xFF1A2031), Color(0xFF151A28))),
            RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFF4C406E), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(45.dp).background(Color(0xFF3B3257), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                Text("✦", color = Purple, fontSize = 28.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("AI COMMAND CENTER", color = PrimaryText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("Grok x Solana · Regelbasierte Risiko-Freigabe", color = Soft, fontSize = 10.sp)
            }
            Surface(color = if (running) Color(0xFF57313E) else Color(0xFF303145), shape = RoundedCornerShape(10.dp)) {
                Text(if (running) "LIVE" else "OFFLINE",
                    color = if (running) Danger else Soft, fontSize = 10.sp,
                    fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
        HorizontalDivider(color = Color(0xFF493F64))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                InfoLabel("AI MODEL")
                Text(if (s.serverStatus?.optBoolean("grokConnected") == true) "Grok Connected" else "Nicht verbunden",
                    color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                InfoLabel("AUTO TRADING")
                Text(if (running) "Aktiv" else "Deaktiviert", color = if (running) Danger else Mint,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        val risk = s.liveRisk
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniValue("STOP LOSS", (s.serverStatus?.optDouble("stopLossPct", 12.0) ?: 12.0).toInt().toString() + "%",
                Danger, Modifier.weight(1f))
            MiniValue("TAKE PROFIT", (s.serverStatus?.optDouble("takeProfitPct", 25.0) ?: 25.0).toInt().toString() + "%",
                Mint, Modifier.weight(1f))
            MiniValue("DAILY LOSS", if (risk == null) "—" else String.format(Locale.US, "%.3f", risk.optDouble("dailyRealizedPnlSol", 0.0)) + " SOL",
                if ((risk?.optDouble("dailyRealizedPnlSol", 0.0) ?: 0.0) < 0) Danger else Mint, Modifier.weight(1f))
        }
        s.agentObservation?.let { obs ->
            Surface(color = Color(0xFF24243A), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    InfoLabel("LETZTE ENTSCHEIDUNG · " + obs.optString("action", "HOLD"))
                    Text(obs.optString("reason", "Deterministische Marktprüfung · Agent überwacht Positionen"),
                        color = Soft, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (risk?.optBoolean("halted") == true) {
            Text("■ RISIKO-STOPP: " + (risk.optJSONArray("reasons")?.optString(0) ?: "Neue Trades blockiert"),
                color = Danger, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        if (running) {
            OutlinedButton(onClick = { s.stopAgent() }, modifier = Modifier.fillMaxWidth()) {
                Text("■ KI-TRADING SOFORT STOPPEN", color = Danger, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = onAutoConfirm,
                enabled = connected && s.serverStatus?.optBoolean("autoAllowed") == true &&
                    s.serverStatus?.optBoolean("live") == true && s.liveRisk?.optBoolean("halted") != true &&
                    !s.requesting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                shape = RoundedCornerShape(12.dp)) {
                Text("KI LIVE FÜR 30 MIN AKTIVIEREN", color = Bg, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }
        Text(if (!connected) "Zum Start HTTPS-Backend und API-Zugang unter System verbinden." else
            "Nur freigegebene Coins · Einsatzlimits · Not-Aus · Marktbedingungen verändern sich schnell.",
            color = Soft, fontSize = 10.sp)
    }
}
@Composable
private fun MiniValue(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.background(Color(0xFF24253A), RoundedCornerShape(11.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoLabel(label)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
    }
}
@Composable
private fun InfoLabel(value: String) {
    Text(value, color = Soft, fontSize = 9.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold)
}
@Composable
fun LiveWalletPanel(s: MemeStore) {
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp))
        .border(1.dp, CardEdge, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
            Column {
                InfoLabel("ECHTES WALLET · SOLANA")
                Spacer(Modifier.height(6.dp))
                Text(if (s.liveWalletSol == null) "— SOL" else
                    String.format(Locale.US, "%.5f SOL", s.liveWalletSol),
                    color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Black)
            }
            TextButton(onClick = { s.refreshLiveStatus(); s.fetchRealHistory() }) {
                Text("↻ SYNC", color = Cyan)
            }
        }
        HorizontalDivider(color = CardEdge)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                InfoLabel("OPEN POSITIONS")
                Text(s.livePositions.size.toString(), color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                InfoLabel("TODAY REALIZED P&L")
                val pnl = s.liveRisk?.optDouble("dailyRealizedPnlSol", 0.0)
                Text(if (pnl == null) "—" else String.format(Locale.US, "%+.5f SOL", pnl),
                    color = if ((pnl ?: 0.0) < 0.0) Danger else Mint,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
        Text("Die SOL-Werte stammen vom Server, nicht aus dem Paper-Portfolio. P&L ohne externe Transfers, Netzwerkgebühren und Slippage.",
            color = Soft, fontSize = 10.sp)
    }
}
@Composable
fun BotHistory(s: MemeStore) {
    if (s.agentEvents.isEmpty()) {
        Surface(color = Card, shape = RoundedCornerShape(15.dp)) {
            Text("Noch keine KI-Entscheidungen vom Backend empfangen.", color = Soft, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        return
    }
    s.agentEvents.take(8).forEach { event ->
        Surface(color = Card, shape = RoundedCornerShape(13.dp)) {
            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                Text("●", color = if (event.optString("event").contains("ERROR") ||
                    event.optString("event").contains("HALT")) Danger else Cyan, fontSize = 13.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.optString("event").replace("_", " "), color = PrimaryText,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(event.optString("details"), color = Soft, fontSize = 11.sp, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(try { SimpleDateFormat("HH:mm", Locale.GERMANY)
                    .format(Date(event.optLong("at"))) } catch (_: Exception) { "—" },
                    color = Soft, fontSize = 10.sp)
            }
        }
    }
}
@Composable
fun LivePositionsPanel(s: MemeStore) {
    if (s.livePositions.isEmpty()) {
        Surface(color = Card, shape = RoundedCornerShape(15.dp)) {
            Text("Keine vom MemePulse-Backend dokumentierten offenen Echtgeldpositionen.",
                color = Soft, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        return
    }
    s.livePositions.forEach { item ->
        Surface(color = Card, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(item.optString("mint").take(12) + "…", color = PrimaryText, fontWeight = FontWeight.Bold)
                    val pct = item.optDouble("estimatedChangePct", Double.NaN)
                    Text(if (pct.isFinite()) String.format(Locale.US, "%+.2f%%*", pct) else "Nicht bewertet",
                        color = if (pct.isFinite() && pct < 0) Danger else Mint,
                        fontWeight = FontWeight.Bold)
                }
                InfoLabel("KOSTENBASIS " + String.format(Locale.US, "%.5f SOL",
                    item.optString("costSolAtomic", "0").toDoubleOrNull()?.div(1e9) ?: 0.0))
                Text("*Marktpreis-Schätzung, kein realisierbarer Verkaufserlös.", color = Soft, fontSize = 10.sp)
                OutlinedButton(onClick = { s.previewSell(item.optString("mint")) }, enabled = !s.requesting,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Echten Verkauf vorbereiten", color = Danger, fontSize = 12.sp)
                }
            }
        }
    }
}
@Composable
fun PaperAutomationPanel(s: MemeStore) {
    Surface(color = Card, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("REGELBASIERTER PAPER-BOT", color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Probiert 1-%-Käufe, Stop −12 % und Ziel +25 %. Kein Grok und kein echtes Geld.",
                    color = Soft, fontSize = 11.sp)
            }
            Switch(checked = s.paperAutoEnabled, onCheckedChange = { s.setPaperAuto(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Bg, checkedTrackColor = Purple))
        }
    }
}
