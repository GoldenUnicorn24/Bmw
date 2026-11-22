package de.nightdesk.trade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import org.json.JSONObject
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF0B1014)
private val Panel = Color(0xFF121C22)
private val Borders = Color(0xFF263640)
private val Mint = Color(0xFF65E5B0)
private val Red = Color(0xFFFF8390)
private val Amber = Color(0xFFFFC078)
private val Muted = Color(0xFF9EAFBA)
private val AppColors = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink,
    surface = Panel, surfaceVariant = Borders, onSurface = Color(0xFFEDF4F5), onSurfaceVariant = Muted,
    outline = Color(0xFF40525D), error = Red)
private val tabs = listOf("Dashboard" to "▥", "Märkte" to "⌁", "Trading" to "⇄", "Bot" to "◈", "Statistik" to "▤")
private val pairsOfInterest = setOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "DOGE/EUR", "PEPE/EUR", "SHIB/EUR", "BONK/EUR")
private val stamp = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneOffset.UTC)
private fun eur(amount: BigDecimal?): String = amount?.let { NumberFormat.getCurrencyInstance(Locale.GERMANY).format(it) } ?: "—"
private fun price(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString().replace('.', ',') + " €"
private fun pct(value: Double): String = String.format(Locale.GERMANY, "%+.2f %%", value * 100)
private fun signed(value: BigDecimal?): Color = if (value == null) Muted else if (value >= zero) Mint else Red

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Desk(viewModel())
            }
        }
    }
}

@Composable
private fun Desk(model: DeskModel) {
    var tab by rememberSaveable { mutableStateOf("Dashboard") }
    val live by model.live.collectAsStateWithLifecycle()
    val banner by model.banner.collectAsStateWithLifecycle()
    val connection by model.markets.status.collectAsStateWithLifecycle()
    val backend by model.backendStatus.collectAsStateWithLifecycle()
    val backendState by model.backendState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Ink,
        topBar = {
            Column(Modifier.fillMaxWidth().background(Ink).statusBarsPadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NIGHTDESK", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text(if (live) "ECHTGELD · KRAKEN SPOT" else "PAPIERHANDEL · SIMULATION",
                            color = if (live) Amber else Mint, style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { tab = if (tab == "Einstellungen") "Dashboard" else "Einstellungen" }) {
                        Text(if (tab == "Einstellungen") "Zurück" else "Einstellungen")
                    }
                }
                Text(if (live) backend else connection, color = Muted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
                val server = backendState
                if (!live && server?.optString("bot_mode") == "live") {
                    Text("Separater Echtgeld-Serverbot · ${server.optString("bot_status")}", color = Amber,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
                }
                HorizontalDivider(color = Borders)
                if (banner.isNotBlank()) {
                    Text(banner, color = Amber, modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                tabs.forEach { (title, icon) ->
                    NavigationBarItem(selected = tab == title, onClick = { tab = title },
                        icon = { Text(icon, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Borders, selectedIconColor = Mint,
                            selectedTextColor = Mint, unselectedTextColor = Muted, unselectedIconColor = Muted))
                }
            }
        }
    ) { pad ->
        Crossfade(tab, Modifier.fillMaxSize().padding(pad), label = "Navigation") { page ->
            when (page) {
                "Märkte" -> MarketScreen(model) { tab = "Trading" }
                "Trading" -> Stack { TradeScreen(model, live) }
                "Bot" -> Stack { BotScreen(model, live) }
                "Statistik" -> Stack { StatsScreen(model, live) }
                "Einstellungen" -> Stack { SettingsScreen(model, live) }
                else -> Stack { Dashboard(model, live) }
            }
        }
    }
}

@Composable
private fun Stack(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Borders)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = color, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Chart(series: List<Double>, caption: String, accent: Color = Mint) {
    if (series.size < 2) {
        Text("Noch keine ausreichende Zeitreihe", color = Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    Canvas(Modifier.fillMaxWidth().height(124.dp)) {
        val low = series.min()
        val high = series.max()
        val span = high - low
        val path = Path()
        val area = Path()
        series.forEachIndexed { i, value ->
            val x = size.width * i / (series.size - 1)
            val y = size.height * (.92f - .82f * (if (span == 0.0) .5f else ((value - low) / span).toFloat()))
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, size.height)
            } else path.lineTo(x, y)
            area.lineTo(x, y)
        }
        area.lineTo(size.width, size.height)
        area.close()
        repeat(4) { i ->
            val y = size.height * i / 3f
            drawLine(Borders, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = .30f), accent.copy(alpha = .025f))))
        drawPath(path, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
    Text(caption, color = Muted, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Dashboard(model: DeskModel, live: Boolean) {
    val paper by model.account.collectAsStateWithLifecycle()
    val quotes by model.quotes.collectAsStateWithLifecycle()
    val bot by model.localBot.collectAsStateWithLifecycle()
    val state by model.backendState.collectAsStateWithLifecycle()
    val balances by model.liveBalances.collectAsStateWithLifecycle()
    if (live) {
        val details by model.livePortfolio.collectAsStateWithLifecycle()
        val data = details
        Section("Kraken-Konto") {
            val samples = data?.optJSONArray("samples")
            val accountValue = data?.decimalOrNull("equity")
            Text(eur(accountValue), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Amber)
            if (samples != null && accountValue != null) {
                val history = (0 until samples.length()).map { samples.getJSONArray(it) }
                val today = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
                val opening = history.firstOrNull {
                    Instant.ofEpochSecond(it.getLong(0)).atOffset(ZoneOffset.UTC).toLocalDate() == today
                }?.getString(1)?.decimal()
                val change = opening?.let { accountValue - it }
                Metric("Kontowertänderung heute · UTC", eur(change), signed(change))
                Chart(history.map { it.getString(1).toDouble() }, "Kontowert inklusive Kontobewegungen · EUR", Amber)
            }
            val account = balances
            if (account == null) Text("Kontostand derzeit nicht verifiziert", color = Amber)
            else for (asset in account.keys().asSequence().sorted()) Metric(asset, account.getString(asset))
        }
        Section("NightDesk-Positionen · erfasster Einstand") {
            val holdings = data?.optJSONObject("statistics")?.optJSONObject("positions")
            if (holdings == null || holdings.length() == 0) Text("Keine Position mit erfasstem Einstand", color = Muted)
            else holdings.keys().asSequence().sorted().forEach { symbol ->
                val holding = holdings.getJSONObject(symbol)
                Metric(symbol, holding.getString("quantity"))
                Metric("Erfasster Einstand", eur(holding.decimal("basis")))
            }
        }
        Section("Server-Überwachung") {
            Metric("Automatik", state?.optString("bot_status").orEmpty().ifBlank { "Unbestätigt" })
            Text(if (state?.optBoolean("worker_current") == true)
                "Serverregeln werden überwacht. Börsenorders bleiben auf Kraken sichtbar."
            else "Serverregeln nicht als aktiv bestätigt.", color = Amber)
        }
        return
    }
    val value = if (paper.account != null) paper.portfolio.value(quotes) else null
    val total = value?.minus(paper.account?.initial?.decimal() ?: zero)
    val day = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
    val start = paper.marks.firstOrNull { Instant.ofEpochMilli(it.at).atOffset(ZoneOffset.UTC).toLocalDate() == day }
    val daily = if (start == null) null else value?.minus(start.equity.decimal())
    Section("Virtuelles Portfolio") {
        Text(eur(value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Metric("Heute · UTC", eur(daily), signed(daily))
        Metric("Seit letzter Rücksetzung", eur(total), signed(total))
        Chart(paper.marks.takeLast(240).map { it.equity.toDouble() }, "Virtueller Portfoliowert · EUR", signed(total))
    }
    Section("Handelsstatus") {
        Metric("Verfügbar", eur(paper.portfolio.cash))
        Metric("Papierbot", if (bot) "Läuft in der App" else "Pausiert")
        Metric(if (state?.optString("bot_mode") == "live") "Echtgeld-Serverbot" else "Papier-Serverbot",
            state?.optString("bot_status").orEmpty().ifBlank { "Unbestätigt" })
    }
    Section("Virtuelle Positionen") {
        if (paper.portfolio.holdings.isEmpty()) Text("Keine offenen Positionen", color = Muted)
        for ((symbol, position) in paper.portfolio.holdings) {
            val quote = quotes[symbol]?.takeIf { it.isCurrent() }
            Metric(symbol, quote?.let { eur(it.bid * position.quantity) } ?: "Kurs fehlt")
            Text("${position.quantity.stripTrailingZeros().toPlainString()} Einheiten · Einstand ${eur(position.basis)}",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MarketScreen(model: DeskModel, openTrade: () -> Unit) {
    val pairs by model.pairs.collectAsStateWithLifecycle()
    val quotes by model.quotes.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val candles by model.chart.collectAsStateWithLifecycle()
    val chartStatus by model.chartStatus.collectAsStateWithLifecycle()
    val watchlist by model.watchlist.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var liquid by rememberSaveable { mutableStateOf(false) }
    var movement by rememberSaveable { mutableStateOf(false) }
    val watched = watchlist.map { it.symbol }.toSet()
    LaunchedEffect(pairs.isNotEmpty()) { if (pairs.isNotEmpty() && candles.isEmpty()) model.choose(selected) }
    val rows = pairs.filter {
        val quote = quotes[it.symbol]?.takeIf(Quote::currentForDisplay)
        it.symbol.contains(search.trim(), ignoreCase = true) && (!favorites || it.symbol in watched) &&
            (!liquid || quote?.let { q -> q.volumeEur >= "20000".decimal() && q.spread <= "0.01".decimal() } == true)
    }.sortedWith(compareBy<PairInfo> {
        if (movement) -(quotes[it.symbol]?.takeIf(Quote::currentForDisplay)?.change?.abs()?.toDouble() ?: -1.0)
        else if (it.symbol in pairsOfInterest) 0.0 else 1.0
    }.thenBy { it.symbol })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Section(selected) {
                val quote = quotes[selected]?.takeIf(Quote::currentForDisplay)
                Metric("Letzter Kurs", quote?.let { price(it.last) } ?: "—")
                Chart(candles.takeLast(160).map { it.close.toDouble() }, chartStatus.ifBlank { "Kraken · 15 Minuten" })
                if (candles.size > 20) {
                    val range = candles.takeLast(20).map { (it.high - it.low).divide(it.close, precision).toDouble() }.average()
                    val previous = candles.dropLast(1).takeLast(20).map { it.volume.toDouble() }.average()
                    Metric("Mittlere Handelsspanne", pct(range))
                    Metric("Volumen zur 20-Kerzen-Basis",
                        if (previous > 0) pct(candles.last().volume.toDouble() / previous - 1) else "—")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = openTrade) { Text("Handeln") }
                    OutlinedButton(onClick = { model.watch(selected) }) {
                        Text(if (selected in watched) "Watchlist −" else "Watchlist +")
                    }
                }
            }
        }
        item {
            OutlinedTextField(search, { search = it }, label = { Text("EUR-Märkte suchen") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = favorites, onClick = { favorites = !favorites }, label = { Text("Watchlist") })
                FilterChip(selected = liquid, onClick = { liquid = !liquid }, label = { Text("Liquid") })
                FilterChip(selected = movement, onClick = { movement = !movement }, label = { Text("Bewegung") })
            }
        }
        items(rows, key = { it.symbol }) { pair ->
            val quote = quotes[pair.symbol]?.takeIf(Quote::currentForDisplay)
            Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.clickable { model.choose(pair.symbol) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(pair.symbol, fontWeight = FontWeight.Medium)
                        if (quote != null) Text(
                            if (quote.spread > "0.01".decimal()) "Spread über 1 %" else "24h-Vol. ${eur(quote.volumeEur)}",
                            color = if (quote.spread > "0.01".decimal()) Amber else Muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(quote?.let { price(it.last) } ?: "Kurs ausstehend", fontFamily = FontFamily.Monospace)
                        if (quote != null) Text(pct(quote.change.toDouble()), color = signed(quote.change))
                    }
                }
            }
        }
    }
}

private fun Quote.currentForDisplay(): Boolean = isCurrent()
private fun JSONObject.decimalOrNull(name: String): BigDecimal? = optString(name).toBigDecimalOrNull()

@Composable
private fun TradeScreen(model: DeskModel, live: Boolean) {
    val selected by model.selected.collectAsStateWithLifecycle()
    val pairs by model.pairs.collectAsStateWithLifecycle()
    val quotes by model.quotes.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val order by model.activePreview.collectAsStateWithLifecycle()
    val realOrder by model.livePreview.collectAsStateWithLifecycle()
    val liveOrders by model.liveOrders.collectAsStateWithLifecycle()
    var side by rememberSaveable { mutableStateOf(true) }
    var qty by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf("") }
    var dropdown by remember { mutableStateOf(false) }
    Section(if (live) "Echte Kraken-Order" else "Papierorder") {
        Box {
            OutlinedButton(onClick = { dropdown = true }) { Text("$selected  ▾") }
            DropdownMenu(expanded = dropdown, onDismissRequest = { dropdown = false },
                modifier = Modifier.heightIn(max = 300.dp)) {
                val choices = pairs.sortedByDescending { it.symbol in pairsOfInterest }
                choices.forEach { pair ->
                    DropdownMenuItem(text = { Text(pair.symbol) }, onClick = {
                        model.choose(pair.symbol)
                        dropdown = false
                    })
                }
            }
        }
        val quote = quotes[selected]?.takeIf(Quote::currentForDisplay)
        Metric("Geld / Brief", quote?.let { price(it.bid) + " / " + price(it.ask) } ?: "—")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = side, onClick = { side = true; model.clearPreview() }, label = { Text("Kaufen") })
            FilterChip(selected = !side, onClick = { side = false; model.clearPreview() }, label = { Text("Verkaufen") })
        }
        AmountField(qty, { qty = it; model.clearPreview() }, "Menge in Einheiten")
        if (live) AmountField(limit, { limit = it; model.clearPreview() }, "Limit in EUR · leer für Marktorder")
        Button(onClick = { model.orderPreview(side, qty, limit) }, enabled = !busy && qty.isNotBlank()) { Text("Ordervorschau") }
    }
    val draft = order
    val real = realOrder
    if (draft != null || real != null) {
        Section("Order prüfen") {
            if (draft != null) {
                Metric("Simulierter Preis", price(draft.price))
                Metric("Menge / voraussichtlich ausgeführt", "${draft.requested} / ${draft.quantity}")
                Metric("Virtuelle Gebühr", eur(draft.fee))
                Metric("Virtuelle Belastung / Gutschrift", eur(if (draft.buy) draft.gross + draft.fee else draft.gross - draft.fee))
            }
            if (real != null) {
                val orderData = real.getJSONObject("order")
                Metric("Markt", orderData.getString("symbol"))
                Metric(if (orderData.getString("side") == "buy") "Kaufmenge" else "Verkaufsmenge",
                    orderData.getString("quantity"))
                Metric("Orderart", if (orderData.has("limit")) "Limit" else "Markt")
                Metric("Richtpreis", real.optString("reference") + " €")
                Metric("Gebührenschätzung", real.optString("fee_estimate") + " €")
                Text("Der tatsächliche Ausführungspreis und die Gebühr kommen von Kraken.", color = Amber)
            }
            Button(onClick = model::submit, enabled = !busy) {
                Text(if (live) "Echtgeldorder absenden" else "Papierorder ausführen")
            }
        }
    }
    if (live) Section("Kraken-Orders") {
        if (liveOrders.isEmpty()) Text("Keine synchronisierten Orders", color = Muted)
        for (row in liveOrders) {
            val status = row.optString("status")
            Metric(row.optString("symbol", "Kraken") + " · " + row.optString("side"), status)
            Text("Ausgeführt ${row.optString("filled", "0")} · Gebühr ${row.optString("fee", "0")} (Kurswährung)",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            if (row.optBoolean("cancelable")) OutlinedButton(onClick = { model.cancel(row.getString("id")) }, enabled = !busy) {
                Text("Stornieren")
            }
        }
    }
}

@Composable
private fun BotScreen(model: DeskModel, live: Boolean) {
    val selected by model.selected.collectAsStateWithLifecycle()
    val strategy by model.strategy.collectAsStateWithLifecycle()
    val signal by model.lastSignal.collectAsStateWithLifecycle()
    val backend by model.backendState.collectAsStateWithLifecycle()
    val local by model.localBot.collectAsStateWithLifecycle()
    val serverPaper by model.serverPaper.collectAsStateWithLifecycle()
    val address by model.savedAddress.collectAsStateWithLifecycle()
    val quotes by model.quotes.collectAsStateWithLifecycle()
    val candles by model.chart.collectAsStateWithLifecycle()
    val pairs by model.pairs.collectAsStateWithLifecycle()
    val paper by model.account.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    LaunchedEffect(selected, pairs.isNotEmpty()) {
        if (pairs.isNotEmpty() && candles.isEmpty()) model.choose(selected)
    }
    val quote = quotes[selected]
    val risk = pairs.firstOrNull { it.symbol == selected }?.let { pair ->
        riskReport(pair, quote, candles, if (live) null else paper.portfolio.value(quotes),
            if (live) null else paper.portfolio.cash)
    }
    Section("MEME RADAR · Kraken Spot") {
        Metric("Markt", selected)
        Metric("Spread", quote?.takeIf { it.isCurrent() }?.let { pct(it.spread.toDouble()) } ?: "—")
        Metric("24h-Volumen", quote?.takeIf { it.isCurrent() }?.let { eur(it.volumeEur) } ?: "—")
        Metric("ATR / Kurs", risk?.atrPercent?.let { pct(it.toDouble()) } ?: "—")
        if (!live) {
            Metric("Risiko-Budget (1 %)", eur(risk?.riskBudgetEur))
            Metric("RISK SIZER · Kaufmenge", risk?.quantity?.stripTrailingZeros()?.toPlainString() ?: "—")
        }
        Text(risk?.reason ?: "Kraken-EUR-Markt noch nicht geladen", color = Amber,
            style = MaterialTheme.typography.bodySmall)
        Metric("STOP GUARD", "−3 % vom Einstand")
        Metric("PROFIT TAKER", "+6 % vom Einstand")
        Text("Dies sind Bot-Überwachungsregeln, keine bei Kraken hinterlegten Stop-Orders. " +
            "LP-Audit, Holder Map, Whale Watch und DEX-Sniping benötigen separate On-Chain-Daten " +
            "und sind hier nicht verfügbar.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    Section("Bot · $selected") {
        Text("Regelbasierte Signale", color = Mint, style = MaterialTheme.typography.labelMedium)
        Strategy.entries.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable { model.strategy.value = item }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = strategy == item, onClick = { model.strategy.value = item })
                Text(item.label)
            }
        }
        if (live) {
            Metric("Server", backend?.optString("bot_status").orEmpty().ifBlank { "Unbestätigt" })
            Text(backend?.optString("last_signal").orEmpty().ifBlank { "Noch kein bestätigtes Serversignal" }, color = Muted)
            SecretField(code, { code = it }, "Separater Automatikcode")
            Button(onClick = { model.controlServer("start_live", code); code = "" }, enabled = code.isNotBlank()) {
                Text("Echtgeldautomatik freigeben")
            }
            OutlinedButton(onClick = { model.controlServer("pause") }) { Text("Server pausieren") }
        } else {
            Text(signal, color = Muted)
            Text("Papierautomatik läuft in der geöffneten App. Stop-Loss und Take-Profit werden lokal überwacht.", color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::startLocal, enabled = !local) { Text("Papierbot starten") }
                OutlinedButton(onClick = model::stopLocal, enabled = local) { Text("Pause") }
            }
            if (address.isNotBlank() && backend?.optString("bot_mode") != "live") {
                HorizontalDivider(color = Borders)
                Text("Server-Papierkonto · eigenes virtuelles Guthaben", color = Mint)
                Metric("Virtuelle Server-EUR", eur(serverPaper?.decimalOrNull("cash")))
                Text(backend?.optString("last_signal").orEmpty(), color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.controlServer("start_paper") }, enabled = backend != null) {
                        Text("Serverbot starten")
                    }
                    OutlinedButton(onClick = { model.controlServer("pause") }, enabled = backend != null) { Text("Pausieren") }
                }
            }
        }
        Button(onClick = {
            model.stopLocal()
            if (address.isNotBlank()) model.controlServer("stop")
        }, colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Ink)) { Text("Not-Stopp") }
    }
}

@Composable
private fun StatsScreen(model: DeskModel, live: Boolean) {
    val paper by model.account.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val results by model.research.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val details by model.livePortfolio.collectAsStateWithLifecycle()
    if (live) Section("Abgerechnete NightDesk-Ausführungen") {
        val data = details?.optJSONObject("statistics")
        if (data == null) Text("Keine bestätigten Kontodaten", color = Muted)
        else {
            val trades = data.optInt("trades")
            Metric("Realisiertes Ergebnis mit erfasstem Einstand", eur(data.decimalOrNull("realized")))
            Metric("Gebühren aus Kraken-Ausführungen", eur(data.decimalOrNull("fees")))
            Metric("Trefferquote", if (trades == 0) "—" else pct(data.optInt("wins").toDouble() / trades))
            Metric("Durchschnittlicher Gewinn", eur(data.decimalOrNull("avg_gain")))
            Metric("Durchschnittlicher Verlust", eur(data.decimalOrNull("avg_loss")))
            val symbols = data.optJSONObject("by_symbol")
            symbols?.keys()?.asSequence()?.sorted()?.forEach { Metric(it, eur(symbols.decimalOrNull(it))) }
        }
    }
    if (!live) {
        val realized = paper.portfolio.results
        val wins = realized.count { it.second > zero }
        var peak = paper.account?.initial?.toDouble() ?: 0.0
        var drawdown = 0.0
        paper.marks.forEach { mark ->
            peak = maxOf(peak, mark.equity.toDouble())
            if (peak > 0) drawdown = maxOf(drawdown, 1 - mark.equity.toDouble() / peak)
        }
        Section("Papierstatistik") {
            Metric("Realisiertes Nettoergebnis", eur(realized.sumOf { it.second }))
            Metric("Virtuelle Gebühren", eur(paper.portfolio.fees))
            Metric("Trefferquote", if (realized.isEmpty()) "—" else pct(wins.toDouble() / realized.size))
            Metric("Maximaler beobachteter Drawdown", pct(drawdown))
            Metric("Erwartungswert pro Verkauf", if (realized.isEmpty()) "—" else
                eur(realized.sumOf { it.second }.divide(realized.size.toBigDecimal(), precision)))
            fun average(amounts: List<BigDecimal>) = amounts.takeIf { it.isNotEmpty() }
                ?.let { it.reduce(BigDecimal::add).divide(it.size.toBigDecimal(), precision) }
            Metric("Durchschnittlicher Gewinn", eur(average(realized.map { it.second }.filter { it > zero })))
            Metric("Durchschnittlicher Verlust", eur(average(realized.map { it.second }.filter { it < zero })))
            for ((symbol, trades) in realized.groupBy { it.first }) Metric(symbol, eur(trades.sumOf { it.second }))
            for ((strategy, fills) in paper.fills.groupBy { it.strategy }) {
                Metric("$strategy · Ausführungen", fills.size.toString())
            }
        }
        Section("Papierhistorie") {
            var archived by rememberSaveable { mutableStateOf(false) }
            var shown by rememberSaveable { mutableIntStateOf(30) }
            val history = if (archived) paper.archived else paper.fills
            if (paper.archived.isNotEmpty()) {
                FilterChip(archived, { archived = !archived; shown = 30 }, { Text("Frühere Zeiträume") })
            }
            if (history.isEmpty()) Text("Noch keine simulierten Ausführungen", color = Muted)
            for (fill in history.takeLast(shown).asReversed()) {
                Metric(fill.symbol + if (fill.buy) " · Kauf" else " · Verkauf", eur(fill.qty.decimal() * fill.price.decimal()))
                Text("${stamp.format(Instant.ofEpochMilli(fill.at))} UTC · ${fill.strategy} · ${fill.qty} Einheiten",
                    color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (history.size > shown) OutlinedButton(onClick = { shown += 30 }) { Text("Weitere Ausführungen") }
        }
    }
    Section("Historische Simulation · $selected") {
        Text("Kraken OHLCV · 15 Minuten und 1 Stunde · Gebühren und Slippage einberechnet",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Button(onClick = model::runResearch, enabled = !busy) { Text("Strategien vergleichen") }
        for (result in results) {
            HorizontalDivider(color = Borders)
            Text(result.strategy.label, fontWeight = FontWeight.SemiBold)
            Metric("Netto / Buy-and-Hold", "${eur(result.net.toBigDecimal())} / ${eur(result.hold.toBigDecimal())}")
            Metric("Drawdown", pct(result.drawdown))
            Metric("Gewinne / Trades", "${result.wins} / ${result.trades}")
            Metric("Profit Factor", result.factor?.let { String.format(Locale.GERMANY, "%.2f", it) } ?: "—")
            Metric("Erwartungswert", eur(result.expectancy?.toBigDecimal()))
            result.regimes.forEach { (regime, amount) -> Metric(regime, eur(amount.toBigDecimal())) }
        }
    }
}

@Composable
private fun SettingsScreen(model: DeskModel, live: Boolean) {
    val account by model.account.collectAsStateWithLifecycle()
    val savedAddress by model.savedAddress.collectAsStateWithLifecycle()
    val backendStatus by model.backendStatus.collectAsStateWithLifecycle()
    val state by model.backendState.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var capital by rememberSaveable { mutableStateOf("1000") }
    var address by rememberSaveable(savedAddress) { mutableStateOf(savedAddress) }
    var token by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val notify by model.notify.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.setNotices(it)
    }
    Section("Handelsmodus") {
        Text(if (live) "Echtgeld · Kraken" else "Papier · Simulation", color = if (live) Amber else Mint)
        if (!BuildConfig.LIVE_ENABLED) Text("Installierbarer Papier-Testbuild. Echtgeld ist gesperrt.", color = Muted)
        else {
            Button(onClick = model::usePaper) { Text("Papiermodus") }
            if (state?.optBoolean("live_enabled") == true) {
                OutlinedButton(onClick = model::useLive, enabled = !busy && !live) { Text("Echtgeldübersicht öffnen") }
                OutlinedButton(onClick = { model.disableLive() }, enabled = !busy) { Text("Echtgeld serverseitig sperren") }
            } else {
                OutlinedButton(onClick = model::prepareLive, enabled = state != null && !busy) { Text("1 · Kraken-Zugriff prüfen") }
                SecretField(code, { code = it }, "Freigabecode vom Server")
                Button(onClick = { model.confirmLive(code); code = "" }, enabled = code.isNotBlank() && !busy) {
                    Text("2 · Echtgeld freischalten")
                }
            }
        }
    }
    Section("Papierkonto") {
        Metric("Virtuelles Startkapital", eur(account.account?.initial?.decimal()))
        AmountField(capital, { capital = it }, "Neues Startkapital in EUR")
        OutlinedButton(onClick = { confirmReset = true }, enabled = !busy && !live) { Text("Papierkonto zurücksetzen") }
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Papierkonto neu starten?") },
            text = { Text("Ein neuer Simulationszeitraum beginnt. Das bisherige Journal bleibt auf dem Gerät gespeichert.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; model.reset(capital) }) { Text("Zurücksetzen") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Abbrechen") } })
    }
    Section("Backend · HTTPS") {
        Text(backendStatus, color = Muted)
        OutlinedTextField(address, { address = it }, label = { Text("HTTPS-Adresse") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        SecretField(token, { token = it }, "Zugriffstoken")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.connect(address, token); token = "" }, enabled = !busy && token.isNotBlank()) { Text("Verbinden") }
            if (savedAddress.isNotBlank()) OutlinedButton(onClick = model::disconnect) { Text("Trennen") }
        }
    }
    Section("Risiko und Datenschutz") {
        val risk = state?.optJSONObject("risk")
        if (live && risk != null) {
            Metric("Server · Positionsrisiko", pct(risk.getString("per_trade").toDouble()))
            Metric("Server · Tagesverlust", pct(risk.getString("daily_loss").toDouble()))
            Metric("Server · Drawdown", pct(risk.getString("drawdown").toDouble()))
            Metric("Server · Positionslimit", risk.getString("max_positions"))
            Metric("Server · Orderlimit", eur(risk.decimalOrNull("max_order_eur")))
            Metric("Server · Tageskaufgrenze", eur(risk.decimalOrNull("max_daily_eur")))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Benachrichtigungen für Papierausführungen", modifier = Modifier.weight(1f))
            Switch(checked = notify, onCheckedChange = {
                if (it && Build.VERSION.SDK_INT >= 33)
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else model.setNotices(it)
            })
        }
        Metric("Papierbot · Risiko je Position", "1 %")
        Metric("Papierbot · Tagesverlust", "3 %")
        Metric("Papierbot · Drawdown", "10 %")
        Metric("Papierbot · Positionslimit", "3")
        Text("Papierdaten bleiben lokal. Kraken erhält öffentliche Kursanfragen. Das konfigurierte Backend erhält dein Zugriffstoken und die dort ausgelösten Handelsanfragen.", color = Muted)
    }
}

@Composable
private fun AmountField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SecretField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
}
