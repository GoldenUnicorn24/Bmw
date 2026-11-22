import hashlib
import hmac
import json
import secrets
import threading
import time
from datetime import datetime, timezone
from decimal import InvalidOperation

from .accounting import positions
from .kraken import ExchangeError, Rejected
from .live import PENDING
from .market import D, ZERO
from .paper import FEE
from .risk import mark_equity, validate_order
from .storage import event, read, save


class Execution:
    def __init__(self, store, market, live, paper, config):
        self.store, self.market, self.live, self.paper, self.config = store, market, live, paper, config
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.control = threading.Lock()
        self.stop_generation = 0
        self.ticket_key = secrets.token_bytes(32)

    def _ticket(self, order, reference, until):
        data = json.dumps([order, reference, until], sort_keys=True).encode()
        return hmac.new(self.ticket_key, data, hashlib.sha256).hexdigest()

    def preview(self, order):
        self.live_enabled()
        pair = self.market.pair(order["symbol"])
        quote = self.market.quote(pair)
        limit = D(order["limit"]) if order.get("limit") else None
        qty = D(order["quantity"])
        validate_order(pair, quote, order["side"], qty, limit, self.config.max_order)
        reference = str(limit if limit is not None else (quote.ask if order["side"] == "buy" else quote.bid))
        until = int(time.time()) + 30
        ticket = f"{until}:{reference}:{self._ticket(order, reference, until)}"
        return {"ticket": ticket, "reference": reference, "fee_estimate": str(qty * D(reference) * FEE)}

    def _reference(self, order, ticket):
        try:
            until_s, ref, sig = ticket.split(":")
            until = int(until_s)
            if until < time.time() or not hmac.compare_digest(self._ticket(order, ref, until), sig):
                raise ValueError()
            return D(ref)
        except (ValueError, InvalidOperation):
            raise ValueError("Ordervorschau ungültig oder abgelaufen") from None

    def live_enabled(self):
        with self.store.connection() as conn:
            if not self.config.live or not read(conn, "live_enabled", False):
                raise ValueError("Echtgeldserver nicht freigeschaltet")

    def _existing(self, order, mode):
        with self.store.connection() as conn:
            row = conn.execute("SELECT * FROM orders WHERE id=?", (order["id"],)).fetchone()
        if row:
            match = (row["mode"], row["symbol"], row["side"], row["quantity"], row["limit_price"])
            if match != (mode, order["symbol"], order["side"], order["quantity"], order.get("limit")):
                raise ValueError("Order-ID ist bereits an einen anderen Auftrag gebunden")
        return dict(row) if row else None

    def _reserve(self, conn, order, mode, reference, strategy):
        limit = order.get("limit")
        conn.execute(
            """INSERT INTO orders (id,mode,symbol,side,kind,quantity,limit_price,status,at,strategy,reference)
               VALUES (?,?,?,?,?,?,?,'sending',unixepoch(),?,?)""",
            (order["id"], mode, order["symbol"], order["side"], "limit" if limit else "market",
             order["quantity"], limit, strategy, str(reference)))
        event(conn, "order_reserved", {"id": order["id"], "mode": mode, "strategy": strategy})

    def submit_paper(self, order, quote=None, strategy=None):
        with self.lock:
            existing = self._existing(order, "paper")
            if existing:
                return existing
            pair = self.market.pair(order["symbol"])
            quote = quote or self.market.quote(pair)
            limit = D(order["limit"]) if order.get("limit") else None
            qty = D(order["quantity"])
            validate_order(pair, quote, order["side"], qty, limit, self.config.max_order, allow_partial=True)
            if strategy:
                self._bot_allowed("paper")
            with self.store.write() as conn:
                self._reserve(conn, order, "paper", quote.last, strategy)
                self.paper.submit(conn, order, quote, limit)
            return self._existing(order, "paper")

    def submit_live(self, order, ticket=None, strategy=None):
        self.live_enabled()
        with self.lock:
            existing = self._existing(order, "live")
            if existing:
                return existing
            reference = None if strategy else self._reference(order, ticket or "")
            self.live.permission_check()
            if self.market.client.call("SystemStatus").get("status") not in (
                    ("online", "limit_only") if order.get("limit") else ("online",)):
                raise ValueError("Kraken-Status erlaubt die gewählte Orderart derzeit nicht")
            opened = self.live.open()
            self.reconcile(opened)
            self._check_journal(opened)
            pair = self.market.pair(order["symbol"])
            totals, available = self.live.balances()
            # Fetch the tradable quote last, after reconciling exchange state and balances.
            equity = self.equity(totals, pair.quote)
            quote = self.market.quote(pair)
            limit = D(order["limit"]) if order.get("limit") else None
            qty = D(order["quantity"])
            side = order["side"]
            buy = side == "buy"
            best = quote.ask if buy else quote.bid
            validate_order(pair, quote, side, qty, limit, self.config.max_order)
            if limit is None and reference is not None and (best > reference if buy else best < reference):
                raise ValueError("Kurs hat sich verschlechtert. Neue Vorschau erstellen.")
            estimate = (limit if limit is not None else best) * qty
            if buy and available.get(pair.quote, ZERO) < estimate * (1 + FEE):
                raise ValueError("Verfügbares Kraken-EUR-Guthaben reicht nicht")
            if not buy and available.get(pair.base, ZERO) < qty:
                raise ValueError("Verfügbare Kraken-Spot-Menge reicht nicht")
            with self.store.write() as conn:
                limit_hit = mark_equity(conn, "live", equity, self.config.daily_loss, self.config.drawdown)
                if limit_hit:
                    self._pause(conn, "Tagesverlust oder Drawdown erreicht")
            if limit_hit and (buy or strategy):
                raise ValueError("Tagesverlust oder Drawdown erreicht")
            with self.store.write() as conn:
                if strategy:
                    self._bot_allowed("live", conn)
                self._check_live_limits(conn, order, pair, best, estimate, equity, strategy)
                self._reserve(conn, order, "live", max(best, limit or ZERO), strategy)
                reserved = dict(conn.execute("SELECT * FROM orders WHERE id=?", (order["id"],)).fetchone())
            if not strategy:
                try:
                    self._reference(order, ticket or "")
                except ValueError as error:
                    self._set_status(order["id"], "rejected", str(error))
                    return self._existing(order, "live")
            if strategy and self.stop.is_set():
                self._set_status(order["id"], "rejected", "Not-Stopp vor Orderübermittlung")
                return self._existing(order, "live")
            try:
                quote.validate()
            except ValueError as error:
                self._set_status(order["id"], "rejected", str(error))
                return self._existing(order, "live")
            try:
                txid = self.live.place(reserved, pair)
            except Rejected as error:
                self._set_status(order["id"], "rejected", str(error))
            except ExchangeError as error:
                self._set_status(order["id"], "unknown", str(error), pause=True)
            else:
                with self.store.write() as conn:
                    conn.execute("UPDATE orders SET status='pending',exchange_id=? WHERE id=?", (txid, order["id"]))
                    event(conn, "order_sent", {"id": order["id"], "exchange_id": txid})
            return self._existing(order, "live")

    def _set_status(self, order_id, status, message, pause=False):
        with self.store.write() as conn:
            conn.execute("UPDATE orders SET status=?,error=? WHERE id=?", (status, message, order_id))
            event(conn, status, {"id": order_id, "reason": message})
            if pause:
                self._pause(conn, message)

    def _pause(self, conn, reason):
        self.stop.set()
        save(conn, "bot_status", "Pausiert")
        save(conn, "last_signal", reason)

    def _check_journal(self, opened):
        with self.store.connection() as conn:
            rows = conn.execute("SELECT id,status,exchange_id FROM orders WHERE mode='live'").fetchall()
        known = {row["exchange_id"] for row in rows}
        if any(txid not in known for txid in opened):
            raise ValueError("Fremde offene Kraken-Order erkannt")
        if any(row["status"] in {"sending", "unknown", "pending", "cancel_requested"} for row in rows):
            raise ValueError("Unbestätigte Kraken-Order blockiert neue Orders")
        if any(row["status"] == "open" for row in rows):
            raise ValueError("Eine offene NightDesk-Order ist bereits aktiv")

    def equity(self, balances, eur="ZEUR"):
        equity = balances.get(eur, ZERO)
        other = {asset: amount for asset, amount in balances.items() if asset != eur and amount}
        if len(other) > 10:
            raise ValueError("Zu viele Anlagen für eine zeitnahe Bewertung")
        pairs = {pair.base: pair for pair in self.market.pairs().values() if pair.quote == eur}
        oldest = time.monotonic()
        for asset, amount in other.items():
            if asset not in pairs:
                raise ValueError("Für eine Kontoanlage fehlt ein EUR-Spotkurs")
            quote = self.market.quote(pairs[asset])
            if quote.bid <= ZERO or quote.ask < quote.bid or quote.last <= ZERO:
                raise ValueError("Ungültige Bewertungsdaten")
            equity += quote.bid * amount
        if time.monotonic() - oldest > 18:
            raise ValueError("Kontobewertung dauerte zu lange")
        return equity

    def _check_live_limits(self, conn, order, pair, best, estimate, equity, strategy):
        quantity = D(order["quantity"])
        buy = order["side"] == "buy"
        held = self.bot_positions(conn, "live")
        current = held.get(pair.symbol)
        if buy and current:
            raise ValueError("Eine Bot-Position in diesem Paar ist bereits offen")
        if not buy and strategy and (not current or quantity > current.quantity):
            raise ValueError("Verkauf überschreitet die vom Bot geführte Menge")
        if not buy:
            return
        if quantity * max(best, D(order.get("limit") or "0")) * D(".03") > equity * self.config.risk:
            raise ValueError("Angenommenes Positionsrisiko über Grenzwert")
        start = int(datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0).timestamp())
        orders = conn.execute("""SELECT cost,reference,quantity,status FROM orders
            WHERE mode='live' AND side='buy' AND at>=? AND status!='rejected'""", (start,))
        used = sum((max(D(row["cost"]), D(row["reference"]) * D(row["quantity"]))
                    for row in orders), ZERO)
        if used + estimate > self.config.max_daily:
            raise ValueError("Tägliches EUR-Kauflimit erreicht")
        if strategy and len(held) >= self.config.max_positions:
            raise ValueError("Maximale Bot-Positionen erreicht")

    @staticmethod
    def bot_positions(conn, mode):
        rows = list(conn.execute(
            """SELECT orders.symbol, orders.side, fills.volume AS filled, fills.cost, fills.fee
               FROM fills JOIN orders ON orders.id=fills.order_id WHERE orders.mode=? AND (orders.strategy IS NOT NULL OR orders.side='sell')
               ORDER BY fills.at,fills.rowid""", (mode,)
        ))
        return positions(rows)

    def _bot_allowed(self, mode, conn=None):
        if conn is None:
            with self.store.connection() as connection:
                return self._bot_allowed(mode, connection)
        if self.stop.is_set() or read(conn, "bot_status") != "Aktiv" or read(conn, "bot_mode") != mode:
            raise ValueError("Bot ist pausiert oder gestoppt")

    def reconcile(self, opened=None):
        with self.lock:
            with self.store.connection() as conn:
                rows = conn.execute("SELECT * FROM orders WHERE mode='live' AND status IN (?,?,?,?,?)", PENDING).fetchall()
            if not rows:
                return {}
            if opened is None:
                opened = self.live.open()
            for row in rows:
                txid = row["exchange_id"]
                if txid:
                    data = opened.get(txid) or self.live.query(txid)
                else:
                    txid, data = self.live.find(row["id"], row["at"], opened)
                if not txid or not data:
                    if row["status"] != "unknown":
                        self._set_status(row["id"], "unknown", "Order bei Kraken noch nicht gefunden", pause=True)
                    continue
                if data.get("cl_ord_id") != row["id"]:
                    self._set_status(row["id"], "unknown", "Order-ID-Abgleich fehlgeschlagen", pause=True)
                    continue
                status = data["status"]
                if status not in {"pending", "open", "closed", "canceled", "expired"}:
                    self._set_status(row["id"], "unknown", "Kraken-Orderstatus unbekannt", pause=True)
                    continue
                observed = tuple(D(data[field]) for field in ("vol_exec", "cost", "fee"))
                previous = tuple(D(row[key]) for key in ("filled", "cost", "fee"))
                if any(new < old for new, old in zip(observed, previous)) or observed[0] > D(row["quantity"]):
                    self._set_status(row["id"], "unknown", "Ausführungsdaten widersprechen dem Journal", pause=True)
                    continue
                try:
                    fills = self.live.fills(txid, data) if observed != previous else []
                except (ValueError, KeyError, InvalidOperation) as error:
                    self._set_status(row["id"], "unknown", "Einzelausführungen noch nicht eindeutig bestätigt", pause=True)
                    raise ValueError("Einzelausführungen noch nicht eindeutig bestätigt") from error
                if status != row["status"] or observed != previous or row["exchange_id"] != txid:
                    with self.store.write() as conn:
                        for id_, volume, cost, fee, at in fills:
                            conn.execute("INSERT INTO fills VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                                         (id_, row["id"], str(volume), str(cost), str(fee), at))
                        conn.execute("UPDATE orders SET exchange_id=?,status=?,filled=?,cost=?,fee=?,error=NULL WHERE id=?",
                                     (txid, status, *(str(value) for value in observed), row["id"]))
                        event(conn, "live_reconciled", {"id": row["id"], "status": status, "filled": str(observed[0])})
            return opened

    def cancel(self, order_id):
        self.live_enabled()
        with self.lock:
            self.reconcile()
            with self.store.connection() as conn:
                row = conn.execute("SELECT * FROM orders WHERE mode='live' AND id=?", (order_id,)).fetchone()
            if not row or row["status"] not in {"open", "pending"} or not row["exchange_id"]:
                raise ValueError("Order kann derzeit nicht storniert werden")
            self._set_status(order_id, "cancel_requested", "Stornierung angefordert")
            try:
                self.live.cancel(row["exchange_id"])
            except ExchangeError as error:
                self._set_status(order_id, "unknown", str(error), pause=True)
                return {"status": "unknown"}
            return {"status": "cancel_requested"}
