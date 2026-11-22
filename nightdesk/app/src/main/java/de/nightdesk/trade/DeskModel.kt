package de.nightdesk.trade

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal data class DeskPaper(
    val account: PaperAccount?, val portfolio: PaperPortfolio, val fills: List<PaperFill>,
    val marks: List<PaperMark>, val archived: List<PaperFill> = emptyList()
)

internal class DeskModel(app: Application) : AndroidViewModel(app) {
    private val db = PaperDb.open(app)
    private val broker = PaperBroker(db)
    val markets = Markets()
    private val krakenKeys = KrakenKeys(app)
    private val kraken = KrakenDirect(krakenKeys, markets.http)
    val krakenConnected = MutableStateFlow(krakenKeys.configured)
    val krakenStatus = MutableStateFlow(if (krakenKeys.configured) "API-Schlüssel gespeichert" else "Nicht verbunden")
    val pulse = MutableStateFlow<KrakenPulse?>(null)
    val pulseStatus = MutableStateFlow("Noch keine Kraken-Orderbuchanalyse")
    private val dex = DexScout(markets.http)
    val dexResult = MutableStateFlow<DexReport?>(null)
    val dexStatus = MutableStateFlow("Noch kein Solana-Token geprüft")
    private val credentials = BackendCredentials(app)
    private val backend = Backend(credentials, markets.http)
    val savedAddress = MutableStateFlow(credentials.address)
    val pairs = markets.pairs
    val quotes = markets.quotes
    val watchlist = broker.dao.watchlist().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val account = combine(broker.dao.account(), broker.dao.history(), broker.dao.marks()) { settings, all, marks ->
        val fills = all.filter { it.epoch == settings?.epoch }
        DeskPaper(settings, portfolio(settings?.initial?.decimal() ?: zero, fills), fills,
            marks.filter { it.epoch == settings?.epoch }, all.filter { it.epoch != settings?.epoch })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeskPaper(null, portfolio(zero, emptyList()), emptyList(), emptyList()))
    val selected = MutableStateFlow("BTC/EUR")
    val chart = MutableStateFlow<List<Candle>>(emptyList())
    val chartStatus = MutableStateFlow("")
    val research = MutableStateFlow<List<ResearchResult>>(emptyList())
    val banner = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val backendState = MutableStateFlow<JSONObject?>(null)
    val backendStatus = MutableStateFlow("Backend nicht verbunden")
    val live = MutableStateFlow(false)
    val liveOrders = MutableStateFlow<List<JSONObject>>(emptyList())
    val liveBalances = MutableStateFlow<JSONObject?>(null)
    val livePortfolio = MutableStateFlow<JSONObject?>(null)
    val serverPaper = MutableStateFlow<JSONObject?>(null)
    private var accountFetched = Instant.EPOCH
    private val notices = TradeNotices(app)
    val notify = MutableStateFlow(notices.enabled)
    val lastSignal = MutableStateFlow("Noch kein Regelsignal")
    val localBot = MutableStateFlow(false)
    val strategy = MutableStateFlow(Strategy.TREND)
    val activePreview = MutableStateFlow<PaperPreview?>(null)
    val livePreview = MutableStateFlow<JSONObject?>(null)
    private var botTask: Job? = null
    private var chartTask: Job? = null
    private val botBars = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch { broker.initialize() }
        viewModelScope.launch { markets.maintain() }
        viewModelScope.launch {
            while (true) {
                val symbols = account.value.portfolio.holdings.keys + selected.value +
                    setOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "DOGE/EUR", "PEPE/EUR", "SHIB/EUR", "BONK/EUR") +
                    watchlist.value.map { it.symbol }
                try {
                    val missing = pairs.value.filter { it.symbol in symbols && quotes.value[it.symbol]?.isCurrent() != true }
                    markets.refresh(missing)
                    if (missing.any { quotes.value[it.symbol]?.isCurrent() == true } &&
                        markets.status.value != "Kraken · WebSocket verbunden") {
                        markets.status.value = "Kraken · REST-Kurse verfügbar"
                    }
                    broker.sample(quotes.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    markets.status.value = "Kraken · Kursabfrage fehlgeschlagen"
                }
                delay(12_000)
            }
        }
        // No private backend is required for the direct Kraken workflow.
        // Deliberately do not auto-connect a legacy server or start a live bot.
        viewModelScope.launch {
            while (true) {
                if (live.value && krakenConnected.value) {
                    try {
                        reloadLive()
                        krakenStatus.value = "Kraken Direct · Kontodaten aktuell"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        krakenStatus.value = "Kraken-Verbindung unterbrochen · Kontodaten nicht aktuell"
                    }
                }
                delay(25_000)
            }
        }
    }

    fun choose(symbol: String) {
        selected.value = symbol
        activePreview.value = null
        livePreview.value = null
        research.value = emptyList()
        pulse.value = null
        pulseStatus.value = "Kraken-Markt gewechselt"
        chart.value = emptyList()
        chartTask?.cancel()
        chartTask = viewModelScope.launch {
            chartStatus.value = "Lade Kraken-Kerzen …"
            try {
                chart.value = markets.candles(pair(symbol))
                chartStatus.value = "Kraken · 15-Minuten-Kerzen"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                chartStatus.value = "Kursverlauf derzeit nicht verfügbar"
            }
        }
    }

    private fun pair(symbol: String = selected.value): PairInfo =
        pairs.value.firstOrNull { it.symbol == symbol } ?: error("Kraken-Handelspaar noch nicht verfügbar")

    private fun number(text: String): BigDecimal {
        val normalized = text.trim().replace(',', '.')
        require(normalized.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,16})?"))) { "Gültige positive Zahl eingeben" }
        return normalized.decimal().stripTrailingZeros()
    }

    fun clearPreview() {
        activePreview.value = null
        livePreview.value = null
    }

    fun orderPreview(buy: Boolean, text: String, limit: String) = action {
        val amount = number(text)
        val selectedPair = pair()
        clearPreview()
        if (live.value) {
            check(BuildConfig.LIVE_ENABLED) { "Nur privat signierte Release-APKs dürfen Echtorders senden" }
            require(limit.isBlank()) { "Direkter Kraken-Handel unterstützt nur Marktorders" }
            require(amount >= selectedPair.minimum && amount.scale() <= selectedPair.quantityDecimals) {
                "Kraken-Mindestmenge oder Dezimalstellen ungültig"
            }
            val quote = markets.quote(selectedPair)
            require(quote.isCurrent() && quote.spread <= "0.01".decimal() &&
                quote.volumeEur >= "20000".decimal()) { "Kurs, Spread oder Liquidität unsicher" }
            require(amount <= if (buy) quote.askQuantity else quote.bidQuantity) {
                "Menge über der sichtbaren besten Kraken-Kursstufe"
            }
            val px = if (buy) quote.ask else quote.bid
            val total = amount * px
            require(total >= selectedPair.minCost && total <= "250".decimal()) {
                "Orderwert muss zwischen Börsenminimum und 250 € liegen"
            }
            val balances = kraken.balances()
            val asset = if (buy) "ZEUR" else selectedPair.base
            val available = balances.optString(asset, "0").toBigDecimalOrNull() ?: zero
            require(available >= if (buy) total * "1.01".decimal() else amount) {
                "Verfügbares Kraken-Guthaben reicht nicht"
            }
            val order = JSONObject().put("symbol", selectedPair.symbol)
                .put("side", if (buy) "buy" else "sell").put("quantity", amount.toPlainString())
            livePreview.value = JSONObject().put("order", order)
                .put("reference", px.toPlainString()).put("fee_estimate", (total * "0.004".decimal()).toPlainString())
                .put("at", System.currentTimeMillis())
        } else {
            val settings = checkNotNull(account.value.account)
            activePreview.value = preview(settings.epoch, selectedPair, buy, amount, markets.quote(selectedPair))
        }
    }

    fun submit() = action {
        if (live.value) {
            val draft = checkNotNull(livePreview.value)
            livePreview.value = null
            require(System.currentTimeMillis() - draft.getLong("at") in 0..30_000) {
                "Ordervorschau abgelaufen"
            }
            val order = draft.getJSONObject("order")
            val selectedPair = pair(order.getString("symbol"))
            val quote = markets.quote(selectedPair)
            val buy = order.getString("side") == "buy"
            val now = if (buy) quote.ask else quote.bid
            val previous = draft.getString("reference").decimal()
            require(quote.isCurrent() && quote.spread <= "0.01".decimal() &&
                quote.volumeEur >= "20000".decimal()) { "Aktuelle Kursprüfung fehlgeschlagen" }
            require(if (buy) now <= previous else now >= previous) {
                "Kurs verschlechtert – neue Vorschau erforderlich"
            }
            val qty = order.getString("quantity").decimal()
            val maxSize = if (buy) quote.askQuantity else quote.bidQuantity
            require(qty <= maxSize && qty * now <= "250".decimal()) { "Liquiditäts-/Orderlimit überschritten" }
            val balances = kraken.balances()
            val available = balances.optString(if (buy) "ZEUR" else selectedPair.base, "0")
                .toBigDecimalOrNull() ?: zero
            require(available >= if (buy) qty * now * "1.01".decimal() else qty) {
                "Nicht genügend Kraken-Guthaben"
            }
            val txid = kraken.submitMarket(selectedPair, order.getString("side"), qty)
            banner.value = "Kraken hat die Marktorder angenommen · " + txid
            reloadLive()
        } else {
            val draft = checkNotNull(activePreview.value)
            activePreview.value = null
            broker.execute(draft, markets.quote(draft.pair))
            broker.sample(quotes.value)
            banner.value = "Papierorder simuliert"
            notices.fill("Papierorder simuliert", draft.pair.symbol)
        }
    }

    fun scanDex(mint: String) = action {
        dexResult.value = null
        val report = dex.scan(mint)
        dexResult.value = report
        dexStatus.value = "DEX- und Solana-Daten geladen · nur Analyse, kein DEX-Handel"
    }

    fun analyzePulse() = action {
        pulse.value = markets.pulse(pair())
        pulseStatus.value = "Kraken-Orderbuch und Trades gelesen"
    }

    fun refreshKraken() = action {
        check(krakenConnected.value) { "Kraken noch nicht verbunden" }
        reloadLive()
        krakenStatus.value = "Kraken Direct · Kontostand aktualisiert"
    }

    fun connectKraken(key: String, secret: String) = action {
        kraken.connect(key, secret)
        krakenConnected.value = true
        krakenStatus.value = "Direkt mit Kraken verbunden · Guthaben geprüft"
        reloadLive()
        banner.value = "Kraken-Zugang erfolgreich verifiziert"
    }

    fun disconnectKraken() {
        stopLocal()
        kraken.disconnect()
        krakenConnected.value = false
        krakenStatus.value = "Kraken-Schlüssel vom Gerät entfernt"
        usePaper()
    }

    fun acknowledgeUncertain() = action {
        krakenKeys.acknowledgeUnknownAfterKrakenReview()
        banner.value = "Sperre nach manueller Kraken-Pro-Prüfung aufgehoben"
    }

    fun reset(text: String) = action {
        stopLocal()
        broker.reset(number(text))
        clearPreview()
        banner.value = "Neues virtuelles Guthaben eingerichtet"
    }

    fun watch(symbol: String) = action {
        if (watchlist.value.any { it.symbol == symbol }) broker.dao.unwatch(symbol)
        else broker.dao.watch(Watch(symbol))
    }

    fun runResearch() = action {
        val market = pair()
        research.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            compare(markets.candles(market), 15, markets.candles(market, 60))
        }
        banner.value = "Historischer Strategievergleich abgeschlossen"
    }

    fun startLocal() {
        if (live.value || localBot.value) return
        botTask = viewModelScope.launch {
            localBot.value = true
            val botStrategy = strategy.value
            val symbol = selected.value
            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        val market = pair(symbol)
                        val bars = markets.candles(market)
                        val higher = markets.candles(market, 60)
                        val now = Instant.now().epochSecond
                        require(bars.isNotEmpty() && bars.last().time in (now - 1900)..(now - 890) &&
                            higher.isNotEmpty() && higher.last().time in (now - 7400)..(now - 3590)) { "Veraltete Kerzen" }
                        val reading = signal(bars, botStrategy)
                        val confirmation = signal(higher, Strategy.TREND)
                        lastSignal.value = "$symbol · ${reading.reason} · 1h ${confirmation.regime}"
                        val state = account.value
                        val settings = checkNotNull(state.account)
                        val quote = markets.quote(market)
                        val equity = state.portfolio.value(quotes.value) ?: error("Portfolio-Kurse fehlen")
                        val day = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
                        val open = state.marks.firstOrNull {
                            Instant.ofEpochMilli(it.at).atOffset(ZoneOffset.UTC).toLocalDate() == day
                        }?.equity?.decimal() ?: settings.initial.decimal()
                        val peak = maxOf(settings.initial.decimal(), state.marks.maxOfOrNull { it.equity.decimal() } ?: zero)
                        require(equity > open * "0.97".decimal() && equity > peak * "0.9".decimal()) {
                            "Verlustgrenze erreicht"
                        }
                        val key = "${settings.epoch}:$symbol:${botStrategy.name}"
                        val current = state.portfolio.holdings[symbol]
                        val managed = state.fills.asSequence().filter { it.symbol == symbol }
                            .fold(zero) { amount, fill ->
                                if (!fill.buy) maxOf(zero, amount - fill.qty.decimal())
                                else if (fill.strategy == botStrategy.label) amount + fill.qty.decimal() else amount
                            }
                        val buy = reading.direction == 1 && confirmation.regime != "Abwärtstrend" && current == null
                        val exit = current?.let {
                            val average = it.basis.divide(it.quantity, precision)
                            quote.bid <= average * "0.97".decimal() || quote.bid >= average * "1.06".decimal()
                        } ?: false
                        val newCandle = botBars[key] != bars.last().time
                        val protectiveExit = managed > zero && exit
                        if ((newCandle && (buy || (managed > zero && reading.direction < 0))) || protectiveExit) {
                            val qty = if (buy) {
                                require(state.portfolio.holdings.size < 3 && reading.atr > 0) { "Positionslimit erreicht" }
                                val report = riskReport(market, quote, bars, equity, state.portfolio.cash)
                                checkNotNull(report.quantity) { report.reason }
                            } else managed
                            // A stop/take-profit exit may occur in the same 15-minute candle as an entry.
                            // Include the side and remaining position to prevent replaying the buy fill.
                            val event = if (buy) "entry" else if (protectiveExit) "guard" else "exit"
                            val id = UUID.nameUUIDFromBytes(
                                "$key:${bars.last().time}:$event:${if (buy) "" else managed.toPlainString()}"
                                    .toByteArray(StandardCharsets.UTF_8)).toString()
                            val draft = preview(settings.epoch, market, buy, qty, quote).copy(id = id)
                            broker.execute(draft, quote, botStrategy.label)
                            botBars[key] = bars.last().time
                            broker.sample(quotes.value)
                            notices.fill("Papierbot-Ausführung simuliert", symbol)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastSignal.value = "Papierbot wartet · ${e.message.orEmpty().take(130)}"
                    }
                    delay(45_000)
                }
            } finally {
                localBot.value = false
            }
        }
    }

    fun stopLocal() {
        botTask?.cancel()
        botTask = null
        localBot.value = false
    }

    fun connect(url: String, token: String) = action {
        credentials.save(url.trim(), token.trim())
        savedAddress.value = credentials.address
        accountFetched = Instant.EPOCH
        refreshBackend()
    }

    fun disconnect() {
        credentials.forget()
        savedAddress.value = ""
        backendState.value = null
        serverPaper.value = null
        backendStatus.value = "Backend nicht verbunden"
        usePaper()
    }

    private suspend fun refreshBackend() {
        try {
            val status = backend.call("v1/status")
            backendState.value = status
            backendStatus.value = "Backend verbunden"
            if (live.value && !status.optBoolean("live_enabled")) {
                usePaper()
                banner.value = "Server hat Echtgeld gesperrt"
            }
            if (live.value && accountFetched.isBefore(Instant.now().minusSeconds(30))) reloadLive()
            if (!live.value && status.optString("bot_mode") == "paper" &&
                accountFetched.isBefore(Instant.now().minusSeconds(30))) {
                serverPaper.value = backend.call("v1/paper/account")
                accountFetched = Instant.now()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            backendState.value = null
            liveBalances.value = null
            livePortfolio.value = null
            liveOrders.value = emptyList()
            serverPaper.value = null
            backendStatus.value = "Backend oder Kraken nicht erreichbar"
        }
    }

    fun prepareLive() = action {
        val data = backend.call("v1/live/prepare", JSONObject())
        banner.value = "Zugriff geprüft. Freigabecode aus Serverdatei innerhalb von fünf Minuten eingeben."
        challenge = data.getString("challenge")
    }

    private var challenge: String? = null

    fun confirmLive(code: String) = action {
        val value = checkNotNull(challenge) { "Zuerst den Kraken-Zugriff prüfen" }
        backend.call("v1/live/confirm", JSONObject().put("challenge", value).put("code", code))
        challenge = null
        stopLocal()
        clearPreview()
        live.value = true
        refreshBackend()
        banner.value = "Echtgeldmodus geöffnet"
    }

    fun useLive() = action {
        check(krakenConnected.value) { "Erst Kraken-API-Key und Secret hinterlegen" }
        check(BuildConfig.LIVE_ENABLED) { "Signierte Release-APK für Echtgeld erforderlich" }
        reloadLive()
        stopLocal()
        clearPreview()
        live.value = true
    }

    fun usePaper() {
        clearPreview()
        live.value = false
        liveOrders.value = emptyList()
        liveBalances.value = null
        livePortfolio.value = null
    }

    private suspend fun reloadLive() {
        val actual = kraken.balances()
        val normalized = JSONObject()
        var equity = zero
        var allValued = true
        for (code in actual.keys()) {
            val asset = when (code) { "ZEUR" -> "EUR"; "XXBT", "XBT" -> "BTC"; "XETH" -> "ETH"; else -> code }
            val amount = actual.getString(code).toBigDecimalOrNull() ?: zero
            if (amount <= zero) continue
            normalized.put(asset, amount.toPlainString())
            if (asset == "EUR") equity += amount
            else {
                val market = pairs.value.firstOrNull { it.base == code && it.symbol.endsWith("/EUR") }
                val quote = market?.let { quotes.value[it.symbol] }?.takeIf { it.isCurrent() }
                if (quote == null) allValued = false else equity += quote.bid * amount
            }
        }
        val open = kraken.openOrders()
        val orders = open.keys().asSequence().map { id ->
            val item = open.getJSONObject(id)
            val descr = item.optJSONObject("descr")
            JSONObject().put("id", id).put("symbol", descr?.optString("pair", "Kraken"))
                .put("side", descr?.optString("type", "?"))
                .put("status", item.optString("status", "open"))
                .put("filled", item.optString("vol_exec", "0"))
                .put("fee", item.optString("fee", "0")).put("cancelable", true)
        }.toList()
        liveBalances.value = normalized
        liveOrders.value = orders
        livePortfolio.value = JSONObject().put("balances", normalized)
            .put("equity", if (allValued) equity.toPlainString() else JSONObject.NULL)
            .put("statistics", JSONObject().put("positions", JSONObject()))
        accountFetched = Instant.now()
    }

    fun disableLive() {
        usePaper()
        banner.value = "Echtgeldansicht geschlossen; Kraken-Schlüssel bleibt gespeichert"
    }

    fun setNotices(enabled: Boolean) {
        notices.enabled = enabled
        notify.value = enabled
    }

    fun cancel(id: String) = action {
        kraken.cancel(id)
        reloadLive()
        banner.value = "Stornierung an Kraken übermittelt"
    }

    fun controlServer(command: String, code: String = "") {
        fun payload() = JSONObject().put("id", UUID.randomUUID().toString()).put("command", command).put("code", code)
            .put("symbol", selected.value).put("strategy", strategy.value.name.lowercase())
        if (command == "stop") {
            viewModelScope.launch {
                try {
                    backend.call("v1/bot", payload())
                    banner.value = "Server-Not-Stopp bestätigt"
                    refreshBackend()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    banner.value = "Server-Not-Stopp konnte nicht bestätigt werden"
                }
            }
        } else action {
            backend.call("v1/bot", payload())
            accountFetched = Instant.EPOCH
            refreshBackend()
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        banner.value = ""
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                banner.value = e.message?.take(210) ?: "Aktion fehlgeschlagen"
            } finally {
                busy.value = false
            }
        }
    }

    override fun onCleared() {
        markets.close()
        super.onCleared()
    }
}
