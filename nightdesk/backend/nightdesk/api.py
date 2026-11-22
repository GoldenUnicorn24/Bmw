import fcntl
import hashlib
import hmac
import re
import secrets
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from decimal import InvalidOperation
from typing import Literal
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .execution import Execution
from .kraken import Kraken
from .risk import mark_equity
from .live import LiveBroker
from .market import D, MarketData
from .paper import PaperBroker
from .settings import Config, secret, valid_hash
from .storage import Store, event, read, save
from .worker import Worker


class Body(BaseModel):
    model_config = ConfigDict(extra="forbid", str_max_length=192)


class Confirmation(Body):
    challenge: str
    code: str


class Order(Body):
    id: UUID
    symbol: str = Field(pattern=r"^[A-Z0-9]{1,16}/EUR$")
    side: Literal["buy", "sell"]
    quantity: str
    limit: str | None = None

    @field_validator("id")
    @classmethod
    def check_uuid(cls, value):
        if value.version != 4:
            raise ValueError("UUID v4 erforderlich")
        return value

    @field_validator("quantity", "limit")
    @classmethod
    def number(cls, value):
        if value is not None and (not re.fullmatch(r"[0-9]{1,12}(\.[0-9]{1,16})?", value) or D(value) <= 0):
            raise ValueError("Positive Dezimalzahl erforderlich")
        if value is None:
            return value
        return format(D(value).normalize(), "f")


class Submitted(Order):
    ticket: str


class Cancel(Body):
    id: UUID


class BotCommand(Body):
    id: UUID
    command: Literal["start_live", "start_paper", "pause", "stop"]
    symbol: str = Field(default="BTC/EUR", pattern=r"^[A-Z0-9]{1,16}/EUR$")
    strategy: Literal["trend", "momentum", "reversion", "breakout", "meme"] = "trend"
    code: str = ""


def make_app(config=None, client=None, public=None, run_worker=True):
    config = config or Config.from_env()
    store = Store(config.path)
    live_client = client or Kraken(secret("NIGHTDESK_KRAKEN_KEY"), secret("NIGHTDESK_KRAKEN_SECRET"))
    market = MarketData(public or Kraken())
    live = LiveBroker(live_client)
    paper = PaperBroker(store, config.paper_start)
    execution = Execution(store, market, live, paper, config)
    worker = Worker(execution)
    windows = defaultdict(deque)
    rate_lock = threading.Lock()

    @asynccontextmanager
    async def lifespan(_):
        with open(config.path + ".lock", "a") as lockfile:
            fcntl.flock(lockfile, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with store.write() as conn:
                save(conn, "worker_ok", False)
                if read(conn, "bot_status") == "Gestoppt":
                    execution.stop.set()
            if run_worker:
                worker.thread.start()
            try:
                yield
            finally:
                if run_worker:
                    worker.shutdown.set()
                    worker.thread.join(timeout=180)
                    if worker.thread.is_alive():
                        # Preserve the advisory lock until process exit.
                        raise RuntimeError("Worker konnte nicht sauber beendet werden")

    app = FastAPI(title="NightDesk Trade 2.0", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.execution = execution

    def permit(bucket, maximum, seconds):
        now = time.monotonic()
        with rate_lock:
            slots = windows[bucket]
            while slots and slots[0] <= now - seconds:
                slots.popleft()
            if len(slots) >= maximum:
                raise HTTPException(429, "Zu viele Anfragen. Später erneut versuchen.")
            slots.append(now)

    def authorize(request: Request):
        auth = request.headers.get("Authorization", "")
        if len(auth) > 150 or not auth.startswith("Bearer ") or not config.authenticate(auth[7:]):
            permit("invalid", 30, 60)
            raise HTTPException(401, "Backend-Token ungültig")
        if request.url.path == "/v1/bot":
            return
        permit("read" if request.method == "GET" else "write", 120 if request.method == "GET" else 30, 60)

    @app.exception_handler(ValueError)
    @app.exception_handler(InvalidOperation)
    async def business_error(_request, error):
        return JSONResponse(status_code=409, content={"detail": str(error)[:225]})

    @app.exception_handler(HTTPException)
    async def http_error(_request, error):
        return JSONResponse(status_code=error.status_code, content={"detail": error.detail})

    from fastapi.exceptions import RequestValidationError

    @app.exception_handler(RequestValidationError)
    async def invalid_input(_request, _error):
        return JSONResponse(status_code=422, content={"detail": "Anfrage enthält ungültige oder fehlende Felder"})

    @app.middleware("http")
    async def bounded_body(request, call_next):
        if request.method != "GET":
            raw = request.headers.get("content-length", "")
            if not raw.isdigit() or len(raw) > 5 or int(raw) > 4096:
                return JSONResponse(status_code=413, content={"detail": "Anfrage zu groß oder ohne Längenangabe"})
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/v1/status", dependencies=[Depends(authorize)])
    def status():
        with store.connection() as conn:
            bot = read(conn, "bot_status", "Pausiert")
            mode = read(conn, "bot_mode", "paper")
            worker_current = bool(read(conn, "worker_ok") and time.time() - read(conn, "worker_at", 0) < 100)
            return {"live_enabled": config.live and read(conn, "live_enabled", False), "bot_mode": mode,
                    "bot_status": bot if worker_current or bot != "Aktiv" else "Unbestätigt",
                    "worker_current": worker_current and bot == "Aktiv",
                    "last_signal": read(conn, "last_signal", ""),
                    "risk": {"per_trade": str(config.risk), "daily_loss": str(config.daily_loss),
                             "drawdown": str(config.drawdown), "max_positions": config.max_positions,
                             "max_order_eur": str(config.max_order), "max_daily_eur": str(config.max_daily)}}

    @app.post("/v1/live/prepare", dependencies=[Depends(authorize)])
    def prepare():
        permit("setup_prepare", 4, 60)
        if not config.live:
            raise HTTPException(403, "Echtgeld ist in der Serverkonfiguration gesperrt")
        with execution.lock:
            live.permission_check()
            live.balances()
            live.open()
            live.closed()
            challenge = secrets.token_urlsafe(32)
            with store.write() as conn:
                save(conn, "challenge", {"value": hashlib.sha256(challenge.encode()).hexdigest(),
                                         "until": int(time.time()) + 300})
                event(conn, "live_permission_checked", {})
        return {"challenge": challenge}

    @app.post("/v1/live/confirm", dependencies=[Depends(authorize)])
    def confirm(body: Confirmation):
        permit("setup_confirm", 4, 60)
        if not config.live or not valid_hash(body.code, config.live_code_hash):
            raise HTTPException(403, "Freigabecode ungültig")
        with store.write() as conn:
            challenge = read(conn, "challenge") or {}
            given = hashlib.sha256(body.challenge.encode()).hexdigest()
            if challenge.get("until", 0) < time.time() or not hmac.compare_digest(challenge.get("value", ""), given):
                raise HTTPException(403, "Kraken-Prüfung fehlt oder ist abgelaufen")
            save(conn, "challenge", None)
            save(conn, "live_enabled", True)
            event(conn, "live_enabled", {})
        return {"live_enabled": True}

    @app.post("/v1/live/disable", dependencies=[Depends(authorize)])
    def disable():
        with execution.control, store.write() as conn:
            execution.stop_generation += 1
            execution.stop.set()
            save(conn, "live_enabled", False)
            save(conn, "bot_status", "Gestoppt")
            save(conn, "challenge", None)
            event(conn, "live_disabled", {})
        return {"live_enabled": False}

    @app.get("/v1/live/account", dependencies=[Depends(authorize)])
    def live_account():
        permit("account", 15, 60)
        execution.live_enabled()
        opened = live.open()
        execution.reconcile(opened)
        balances, _ = live.balances()
        closed, _ = live.closed()
        equity = None
        try:
            valuation = execution.equity(balances)
            with store.write() as conn:
                if mark_equity(conn, "live", valuation, config.daily_loss, config.drawdown):
                    execution._pause(conn, "Tagesverlust oder Drawdown erreicht")
            equity = str(valuation)
        except (ValueError, KeyError, InvalidOperation):
            pass
        with store.connection() as conn:
            rows = [dict(row) for row in conn.execute("SELECT * FROM orders WHERE mode='live' ORDER BY at DESC LIMIT 100")]
            fills = list(conn.execute("""SELECT orders.symbol, orders.side, orders.strategy, fills.volume AS filled,
                fills.cost, fills.fee FROM fills JOIN orders ON orders.id=fills.order_id
                WHERE orders.mode='live' ORDER BY fills.at, fills.rowid"""))
            samples = [[row["at"], row["amount"]] for row in conn.execute(
                "SELECT at,amount FROM equity_samples WHERE mode='live' ORDER BY at DESC LIMIT 300")][::-1]
        known = {row["exchange_id"] for row in rows}
        for row in rows:
            row["cancelable"] = bool(row["exchange_id"] and row["status"] in {"open", "pending"})
        for txid, data in (opened | closed).items():
            if txid not in known:
                order = data.get("descr", {})
                rows.append({"id": txid, "symbol": order.get("pair", "Kraken"), "side": order.get("type", ""),
                             "status": data["status"], "filled": data["vol_exec"], "fee": data["fee"],
                             "cancelable": False, "external": True})
        from .accounting import statistics
        return {"balances": {k: str(v) for k, v in balances.items()}, "orders": rows,
                "equity": equity, "samples": samples, "statistics": statistics(fills)}

    @app.post("/v1/live/preview", dependencies=[Depends(authorize)])
    def preview(body: Order):
        return execution.preview(body.model_dump(mode="json"))

    @app.post("/v1/live/order", dependencies=[Depends(authorize)])
    def place(body: Submitted):
        permit("order", 6, 60)
        return execution.submit_live(body.model_dump(mode="json", exclude={"ticket"}), body.ticket)

    @app.post("/v1/live/cancel", dependencies=[Depends(authorize)])
    def cancel(body: Cancel):
        return execution.cancel(str(body.id))

    @app.post("/v1/paper/order", dependencies=[Depends(authorize)])
    def paper_order(body: Order):
        return execution.submit_paper(body.model_dump(mode="json"))

    @app.get("/v1/paper/account", dependencies=[Depends(authorize)])
    def paper_account():
        with store.connection() as conn:
            cash, holdings = paper.account(conn)
            orders = [dict(row) for row in conn.execute("SELECT * FROM orders WHERE mode='paper' ORDER BY at DESC LIMIT 100")]
        return {"cash": str(cash), "positions": {key: str(value.quantity) for key, value in holdings.items()}, "orders": orders}

    @app.get("/v1/events", dependencies=[Depends(authorize)])
    def events():
        with store.connection() as conn:
            return {"events": [dict(row) for row in conn.execute("SELECT * FROM events ORDER BY id DESC LIMIT 100")]}

    @app.post("/v1/bot", dependencies=[Depends(authorize)])
    def bot(body: BotCommand):
        command_id = "command:" + str(body.id)
        if body.command in {"stop", "pause"}:
            with execution.control, store.write() as conn:
                execution.stop_generation += 1
                execution.stop.set()
                save(conn, "bot_status", "Gestoppt" if body.command == "stop" else "Pausiert")
                save(conn, command_id, True)
                event(conn, body.command, {})
            return status()
        generation = execution.stop_generation
        permit("bot_start", 4, 60)
        mode = "live" if body.command == "start_live" else "paper"
        if mode == "live":
            execution.live_enabled()
            if not valid_hash(body.code, config.bot_code_hash):
                raise HTTPException(403, "Separater Automatikcode ungültig")
            live.permission_check()
        market.pair(body.symbol)
        with execution.lock, execution.control, store.write() as conn:
            if generation != execution.stop_generation or (mode == "live" and not read(conn, "live_enabled")):
                raise HTTPException(409, "Start durch einen neueren Not-Stopp abgebrochen")
            if read(conn, command_id):
                raise HTTPException(409, "Automatikkommando wurde bereits verarbeitet")
            current_mode = read(conn, "bot_mode")
            if current_mode and mode != current_mode and execution.bot_positions(conn, current_mode):
                raise HTTPException(409, "Offene Bot-Positionen im bisherigen Modus zuerst schließen")
            execution.stop.clear()
            save(conn, command_id, True)
            save(conn, "bot_mode", mode)
            save(conn, "bot_symbol", body.symbol)
            save(conn, "bot_strategy", body.strategy)
            save(conn, "bot_status", "Aktiv")
            save(conn, "worker_ok", False)
            event(conn, "bot_start", {"mode": mode, "symbol": body.symbol, "strategy": body.strategy})
        return status()

    return app
