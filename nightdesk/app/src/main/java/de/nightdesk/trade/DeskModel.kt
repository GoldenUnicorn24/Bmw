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
        viewModelScope.launch {
            while (true) {
                if (savedAddress.value.isNotBlank()) refreshBackend()
                delay(10_000)
            }
        }
    }

    fun choose(symbol: String) {
        selected.value = symbol
        activePreview.value = null
        livePreview.value = null
        research.value = emptyList()
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
            val order = JSONObject().put("id", UUID.randomUUID().toString()).put("symbol", selectedPair.symbol)
                .put("side", if (buy) "buy" else "sell").put("quantity", amount.toPlainString())
            if (limit.isNotBlank()) order.put("limit", number(limit).toPlainString())
            livePreview.value = backend.call("v1/live/preview", order).put("order", order)
        } else {
            val settings = checkNotNull(account.value.account)
            activePreview.value = preview(settings.epoch, selectedPair, buy, amount, markets.quote(selectedPair))
        }
    }

    fun submit() = action {
        if (live.value) {
            val draft = checkNotNull(livePreview.value)
            livePreview.value = null
            val result = backend.call("v1/live/order", draft.getJSONObject("order").put("ticket", draft.getString("ticket")))
            val tradeStatus = result.optString("status")
            val diagnostic = result.optString("error").takeUnless { it.isBlank() || it == "null" }
            banner.value = listOfNotNull("Kraken-Order", tradeStatus, diagnostic).joinToString(" · ")
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
                        if (botBars[key] != bars.last().time && (buy || (managed > zero && (reading.direction < 0 || exit)))) {
                            val qty = if (buy) {
                                require(state.portfolio.holdings.size < 3 && reading.atr > 0) { "Positionslimit erreicht" }
                                val riskDistance = maxOf((2 * reading.atr).toBigDecimal(), quote.ask * "0.03".decimal())
                                minOf((equity * "0.01".decimal()).divide(riskDistance, precision),
                                    (state.portfolio.cash * "0.30".decimal()).divide(quote.ask, precision))
                                    .setScale(market.quantityDecimals, RoundingMode.DOWN)
                            } else managed
                            val id = UUID.nameUUIDFromBytes("$key:${bars.last().time}".toByteArray(StandardCharsets.UTF_8)).toString()
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
        check(backendState.value?.optBoolean("live_enabled") == true) { "Echtgeld zuerst freischalten" }
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
        val data = backend.call("v1/live/account")
        liveBalances.value = data.getJSONObject("balances")
        livePortfolio.value = data
        accountFetched = Instant.now()
        val rows = data.getJSONArray("orders")
        liveOrders.value = (0 until rows.length()).map { rows.getJSONObject(it) }
    }

    fun disableLive() = action {
        backend.call("v1/live/disable", JSONObject())
        usePaper()
        refreshBackend()
        banner.value = "Echtgeld auf dem Server gesperrt"
    }

    fun setNotices(enabled: Boolean) {
        notices.enabled = enabled
        notify.value = enabled
    }

    fun cancel(id: String) = action {
        backend.call("v1/live/cancel", JSONObject().put("id", id))
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
