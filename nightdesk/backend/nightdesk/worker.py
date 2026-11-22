import logging
import threading
import time
import uuid
from decimal import InvalidOperation

from .market import D, ZERO
from .risk import mark_equity, quantity_step
from .storage import event, read, save
from .strategy import evaluate, parse

logger = logging.getLogger("nightdesk.worker")


class Worker:
    def __init__(self, execution):
        self.execution = execution
        self.shutdown = threading.Event()
        self.thread = threading.Thread(target=self.loop, name="nightdesk-worker", daemon=True)

    def loop(self):
        while not self.shutdown.is_set():
            try:
                self.cycle()
            except (ValueError, KeyError, TypeError, InvalidOperation) as error:
                with self.execution.store.write() as conn:
                    save(conn, "worker_ok", False)
                    save(conn, "last_signal", f"Zyklus unterbrochen · {str(error)[:135]}")
                logger.warning("Zyklus ohne neue Order abgebrochen (%s)", type(error).__name__)
            except Exception:
                with self.execution.store.write() as conn:
                    self.execution._pause(conn, "Interner Fehler im Worker")
                    save(conn, "worker_ok", False)
                logger.error("Interner Workerfehler, Automatik pausiert")
            self.shutdown.wait(30)

    def cycle(self):
        ex = self.execution
        store = ex.store
        with store.connection() as conn:
            mode = read(conn, "bot_mode", "paper")
            status = read(conn, "bot_status", "Pausiert")
            symbol = read(conn, "bot_symbol")
            strategy = read(conn, "bot_strategy")
            managed = ex.bot_positions(conn, mode)
            live_enabled = read(conn, "live_enabled", False) and ex.config.live
        if ex.config.live:
            ex.reconcile()
        if mode == "live" and not live_enabled:
            return
        if status == "Aktiv" and not ex.stop.is_set():
            selected = list(dict.fromkeys([*managed, symbol]))
            for item in selected:
                if self.shutdown.is_set() or ex.stop.is_set():
                    return
                self.trade(mode, item, strategy)
        with store.write() as conn:
            save(conn, "worker_at", int(time.time()))
            save(conn, "worker_ok", True)

    def trade(self, mode, symbol, strategy):
        ex = self.execution
        with ex.store.connection() as conn:
            position = ex.bot_positions(conn, mode).get(symbol)
            if position:
                previous = conn.execute("""SELECT strategy FROM orders WHERE mode=? AND symbol=?
                    AND strategy IS NOT NULL AND side='buy' AND filled!='0' ORDER BY at DESC LIMIT 1""",
                                        (mode, symbol)).fetchone()
                strategy = previous[0] if previous else strategy
        pair = ex.market.pair(symbol)
        lower = parse(ex.market.candles(pair, 15), 15)
        higher = parse(ex.market.candles(pair, 60), 60)
        now = time.time()
        if not lower or not higher or not now - 1900 <= lower[-1].time <= now - 890 or not now - 7400 <= higher[-1].time <= now - 3590:
            raise ValueError("Abgeschlossene Kerzen fehlen oder sind veraltet")
        signal = evaluate(lower, strategy)
        context = evaluate(higher, "trend")
        if context.regime == "Unbekannt":
            raise ValueError("Zu wenig historische 1-Stunden-Kerzen")
        with ex.store.write() as conn:
            if ex.stop.is_set():
                return
            save(conn, "last_signal", f"{symbol} · {signal.reason} · 1h {context.regime}")
            candle_id = f"{mode}:{symbol}:{lower[-1].time}"
            already_traded = read(conn, "last_candle:" + mode + ":" + symbol) == candle_id
        quote = ex.market.quote(pair)
        quote.validate()
        stop_loss = position and quote.bid <= position.average * D(".97")
        take_profit = position and quote.bid >= position.average * D("1.06")
        buy = not position and signal.direction == 1 and context.regime != "Abwärtstrend" and signal.atr > 0
        sell = position and (signal.direction == -1 or stop_loss or take_profit)
        if mode == "live":
            ex.live_enabled()
            totals, available = ex.live.balances()
            equity = ex.equity(totals, pair.quote)
            cash = available.get(pair.quote, ZERO)
        else:
            with ex.store.connection() as conn:
                cash, holdings = ex.paper.account(conn)
            equity = cash + sum((p.quantity * ex.market.quote(ex.market.pair(key)).bid
                                 for key, p in holdings.items()), ZERO)
        with ex.store.write() as conn:
            if mark_equity(conn, mode, equity, ex.config.daily_loss, ex.config.drawdown):
                ex._pause(conn, "Tagesverlust oder Drawdown erreicht")
                return
        # Protective exits must run even after an entry in the same 15-minute candle.
        # Ordinary strategy signals remain limited to one decision per candle.
        protective_exit = bool(position and (stop_loss or take_profit))
        if (already_traded and not protective_exit) or (not buy and not sell):
            return
        if buy:
            with ex.store.connection() as conn:
                if len(ex.bot_positions(conn, mode)) >= ex.config.max_positions:
                    raise ValueError("Positionslimit erreicht")
            distance = max(D(str(2 * signal.atr)), quote.ask * D(".03"))
            amount = min(equity * ex.config.risk / distance, cash * D(".30") / quote.ask,
                         ex.config.max_order / quote.ask, quote.ask_quantity)
        else:
            amount = min(position.quantity, ex.config.max_order / quote.bid, quote.bid_quantity)
        qty = quantity_step(amount, pair.quantity_decimals)
        if qty < pair.minimum or qty * (quote.ask if buy else quote.bid) < pair.minimum_cost:
            raise ValueError("Dynamische Positionsgröße unter dem Kraken-Mindestwert")
        # The side and current managed quantity make an in-candle stop distinct
        # from an entry, while retaining deterministic IDs for retries.
        episode = f":guard:{position.quantity}" if protective_exit else ":signal"
        order = {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, "nightdesk:" + candle_id + episode)), "symbol": symbol,
                 "side": "buy" if buy else "sell", "quantity": format(qty, "f"), "limit": None}
        with ex.store.write() as conn:
            ex._bot_allowed(mode, conn)
            save(conn, "last_candle:" + mode + ":" + symbol, candle_id)
            reason = "Stop-Loss (Serverregel)" if stop_loss else "Take-Profit (Serverregel)" if take_profit else signal.reason
            event(conn, "bot_decision", {"id": order["id"], "symbol": symbol, "strategy": strategy, "reason": reason})
        if mode == "live":
            ex.submit_live(order, strategy=strategy)
        else:
            ex.submit_paper(order, strategy=strategy)
