import base64
import hashlib
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from nightdesk.api import make_app
from nightdesk.kraken import Kraken, Rejected, Uncertain
from nightdesk.live import REQUIRED
from nightdesk.market import D
from nightdesk.risk import mark_equity
from nightdesk.settings import Config
from nightdesk.storage import Store, read

TOKEN = "u" * 48
LIVE_CODE = "l" * 48
BOT_CODE = "b" * 48
AUTH = {"Authorization": f"Bearer {TOKEN}"}


def digest(word):
    return hashlib.sha256(word.encode()).hexdigest()


class Exchange:
    key = "test-key"
    permission_list = list(REQUIRED)
    invalid = False
    timeout = False
    spread = "100.1"
    book = "8"
    cash = "1000"

    def __init__(self):
        self.calls = []
        self.opened = {}
        self.finished = {}
        self.trades = {}

    def call(self, endpoint, data=None):
        if endpoint == "AssetPairs":
            return {"XXBTZEUR": {"wsname": "XBT/EUR", "status": "online", "base": "XXBT",
                                "quote": "ZEUR", "lot_decimals": 6, "tick_size": "0.1",
                                "ordermin": "0.00006", "costmin": "0.45"}}
        if endpoint == "Ticker":
            return {"XXBTZEUR": {"a": [self.spread, "1", self.book], "b": ["100", "1", self.book],
                                "c": ["100", "1"], "v": ["100", "500"], "p": ["100", "100"]}}
        if endpoint == "SystemStatus":
            return {"status": "online"}
        raise AssertionError(endpoint)

    def private(self, endpoint, params=None):
        self.calls.append((endpoint, params))
        if self.invalid:
            raise Rejected("EAPI:Invalid key")
        if endpoint == "GetApiKeyInfo":
            return {"apiKey": self.key, "permissions": self.permission_list}
        if endpoint == "OpenOrders":
            return {"open": self.opened.copy()}
        if endpoint == "ClosedOrders":
            return {"closed": self.finished.copy(), "count": len(self.finished)}
        if endpoint == "BalanceEx":
            return {"ZEUR": {"balance": self.cash, "hold_trade": "0"},
                    "XXBT": {"balance": "0.8", "hold_trade": "0"}}
        if endpoint == "AddOrder":
            if self.timeout:
                raise Uncertain("Zeitüberschreitung")
            self.opened["ORDER-1"] = self.row(params["cl_ord_id"])
            return {"txid": ["ORDER-1"]}
        if endpoint == "QueryOrders":
            order = params["txid"]
            return {order: self.opened.get(order) or self.finished.get(order)}
        if endpoint == "QueryTrades":
            return {txid: self.trades[txid] for txid in params["txid"].split(",")}
        if endpoint == "CancelOrder":
            order = params["txid"]
            self.finished[order] = self.opened.pop(order) | {"status": "canceled"}
            return {"count": 1}
        raise AssertionError(endpoint)

    @staticmethod
    def row(clid, **updates):
        return {"cl_ord_id": clid, "status": "open", "vol_exec": "0", "cost": "0", "fee": "0"} | updates

    def executions(self):
        return [args for endpoint, args in self.calls if endpoint == "AddOrder"]


@pytest.fixture
def scenario(tmp_path):
    token_path = tmp_path / "token.sha256"
    token_path.write_text(digest(TOKEN))
    cfg = Config(str(tmp_path / "nightdesk.sqlite"), str(token_path), True, digest(LIVE_CODE), digest(BOT_CODE))
    exchange = Exchange()
    app = make_app(cfg, exchange, exchange, run_worker=False)
    with TestClient(app, headers=AUTH) as client:
        yield cfg, exchange, client


def draft(**fields):
    return {"id": str(uuid4()), "symbol": "BTC/EUR", "side": "buy", "quantity": "0.5"} | fields


def post(client, path, payload=None):
    return client.post(f"/v1/{path}", json=payload if payload is not None else {})


def enable(client):
    start = post(client, "live/prepare")
    assert start.status_code == 200, start.json()
    confirmation = post(client, "live/confirm", start.json() | {"code": LIVE_CODE})
    assert confirmation.status_code == 200, confirmation.json()
    return start.json()


def reviewed(client, order=None):
    order = order or draft()
    result = post(client, "live/preview", order)
    assert result.status_code == 200, result.json()
    return order | {"ticket": result.json()["ticket"]}


def test_signature_matches_independent_binary_digest():
    path = "/0/private/AddOrder"
    nonce = "1616492376594"
    body = "nonce=1616492376594&ordertype=limit&pair=XBTUSD&price=37500&type=buy&volume=1.25"
    secret = base64.b64encode(b"independent-test-secret").decode()
    expected = "wVzEMGDhA6Exm3qCROHPyYBZDFVE/WACjYbIzD7CgTw336dM/CfsJsIgko+sTeCu3exMoTcfApZo11uRocUDPw=="
    assert Kraken.signature(path, nonce, body, secret) == expected


def test_paper_is_default_separate_and_persistent(scenario):
    cfg, exchange, client = scenario
    assert client.get("/v1/status").json()["live_enabled"] is False
    order = draft(quantity="1.5", limit="110")
    exchange.book = "1"
    response = post(client, "paper/order", order)
    assert response.status_code == 200, response.json()
    row = response.json()
    assert row["status"] == "partial_canceled"
    assert D(row["filled"]) == D("1")
    assert D(row["fee"]) == D("0.4008004")
    assert not exchange.executions()
    assert post(client, "paper/order", order).json() == row
    assert post(client, "paper/order", order | {"quantity": "1"}).status_code == 409
    with Store(cfg.path).connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM fills").fetchone()[0] == 1
        assert conn.execute("SELECT mode FROM orders").fetchone()[0] == "paper"
    assert D(client.get("/v1/paper/account").json()["cash"]) == D("899.3990996")


def test_live_requires_auth_confirmation_permissions_and_distinct_automation_code(scenario):
    cfg, exchange, client = scenario
    assert client.get("/v1/status", headers={"Authorization": "Bearer fake"}).status_code == 401
    assert post(client, "live/preview", draft()).status_code == 409
    exchange.invalid = True
    failed = post(client, "live/prepare")
    assert failed.status_code == 409 and "Invalid key" in failed.json()["detail"]
    exchange.invalid = False
    exchange.permission_list = [*REQUIRED, "withdraw-funds"]
    assert post(client, "live/prepare").status_code == 409
    exchange.permission_list = list(REQUIRED)
    challenge = enable(client)
    assert post(client, "live/confirm", challenge | {"code": LIVE_CODE}).status_code == 403
    cmd = {"id": str(uuid4()), "command": "start_live", "code": LIVE_CODE}
    assert post(client, "bot", cmd).status_code == 403
    assert post(client, "bot", cmd | {"code": BOT_CODE}).status_code == 200
    assert post(client, "bot", {"id": str(uuid4()), "command": "stop"}).status_code == 200
    assert post(client, "bot", cmd | {"code": BOT_CODE}).status_code == 409
    Path(cfg.token_hash_file).write_text(digest("x" * 48))
    assert client.get("/v1/status").status_code == 401


def test_limit_submission_partial_execution_and_cancellation(scenario):
    _, exchange, client = scenario
    enable(client)
    order = reviewed(client, draft(limit="99.9"))
    response = post(client, "live/order", order)
    assert response.status_code == 200, response.json()
    assert response.json()["exchange_id"] == "ORDER-1"
    sent, = exchange.executions()
    assert sent["ordertype"] == "limit" and sent["price"] == "99.9"
    assert sent["cl_ord_id"] == order["id"] and "leverage" not in sent
    exchange.opened["ORDER-1"] |= {"vol_exec": "0.1", "cost": "9.99", "fee": "0.04", "trades": ["TRADE-1"]}
    exchange.trades["TRADE-1"] = {"ordertxid": "ORDER-1", "vol": "0.1", "cost": "9.99", "fee": "0.04",
                                   "time": time.time()}
    account = client.get("/v1/live/account")
    assert account.status_code == 200, account.json()
    synced, = account.json()["orders"]
    assert synced["filled"] == "0.1" and synced["fee"] == "0.04" and synced["cancelable"]
    assert post(client, "live/cancel", {"id": order["id"]}).json()["status"] == "cancel_requested"
    final, = client.get("/v1/live/account").json()["orders"]
    assert final["status"] == "canceled" and final["filled"] == "0.1" and not final["cancelable"]


def test_timeout_unknown_persists_never_retries_and_reconciles_by_client_id(scenario):
    cfg, exchange, client = scenario
    enable(client)
    received = reviewed(client)
    exchange.timeout = True
    assert post(client, "live/order", received).json()["status"] == "unknown"
    assert post(client, "live/order", received).json()["status"] == "unknown"
    assert post(client, "live/order", reviewed(client)).status_code == 409
    assert len(exchange.executions()) == 1
    with Store(cfg.path).connection() as conn:
        assert read(conn, "bot_status") == "Pausiert"
    exchange.finished["KRAKEN-RECOVERED"] = exchange.row(received["id"], status="expired")
    account = client.get("/v1/live/account")
    assert account.status_code == 200, account.json()
    recovered, = account.json()["orders"]
    assert recovered["status"] == "expired" and recovered["exchange_id"] == "KRAKEN-RECOVERED"


def test_concurrent_duplicate_and_distinct_orders(scenario):
    _, exchange, client = scenario
    enable(client)
    order = reviewed(client)
    rival = reviewed(client)
    with ThreadPoolExecutor(max_workers=3) as pool:
        responses = list(pool.map(lambda row: post(client, "live/order", row), [order, order, rival]))
    assert len(exchange.executions()) == 1
    assert sorted({r.status_code for r in responses}) == [200, 409]


def test_risk_rejects_bad_tick_large_order_and_daily_loss(scenario):
    _, exchange, client = scenario
    enable(client)
    assert post(client, "live/preview", draft(limit="100.01")).status_code == 409
    assert post(client, "live/preview", draft(quantity="25")).status_code == 409
    cfg, _, _ = scenario
    with Store(cfg.path).write() as conn:
        assert not mark_equity(conn, "live", D(1100), D(".03"), D(".1"))
    exchange.cash = "870"
    response = post(client, "live/order", reviewed(client))
    assert response.status_code == 409 and "Tagesverlust" in response.json()["detail"]
    assert not exchange.executions()


def test_preview_tamper_and_kraken_outage(scenario):
    _, exchange, client = scenario
    enable(client)
    order = reviewed(client)
    assert post(client, "live/order", order | {"quantity": "0.6"}).status_code == 409
    exchange.invalid = True
    response = post(client, "live/order", order)
    assert response.status_code == 409 and "Invalid key" in response.json()["detail"]
    assert not exchange.executions()


def test_emergency_stop_blocks_bot_order_without_touching_positions(scenario):
    _, exchange, client = scenario
    enable(client)
    assert post(client, "bot", {"id": str(uuid4()), "command": "start_live", "code": BOT_CODE}).status_code == 200
    assert post(client, "bot", {"id": str(uuid4()), "command": "stop"}).json()["bot_status"] == "Gestoppt"
    execution = client.app.state.execution
    with pytest.raises(ValueError, match="pausiert oder gestoppt"):
        execution.submit_live(draft(limit=None), strategy="trend")
    assert not exchange.executions()


def test_worker_uses_confirmed_candles_and_deduplicates_paper_orders(scenario, monkeypatch):
    from nightdesk.worker import Worker

    _, exchange, client = scenario
    original = exchange.call

    def data(endpoint, args=None):
        if endpoint != "OHLC":
            return original(endpoint, args)
        step = args["interval"] * 60
        unfinished = int(time.time()) // step * step
        rows = []
        for i in range(241):
            value = 100 * 1.005**i
            rows.append([unfinished - (240 - i) * step, str(value), str(value * 1.002),
                         str(value * .998), str(value), str(value), "100", 10])
        return {"XXBTZEUR": rows, "last": unfinished}

    monkeypatch.setattr(exchange, "call", data)
    command = {"id": str(uuid4()), "command": "start_paper", "strategy": "momentum"}
    assert post(client, "bot", command).is_success
    worker = Worker(client.app.state.execution)
    worker.cycle()
    assert client.get("/v1/status").json()["worker_current"] is True
    account = client.get("/v1/paper/account").json()
    assert len(account["orders"]) == 1
    assert account["orders"][0]["strategy"] == "momentum"
    worker.cycle()
    assert len(client.get("/v1/paper/account").json()["orders"]) == 1
    assert not exchange.executions()
    assert post(client, "bot", {"id": str(uuid4()), "command": "stop"}).is_success
    worker.cycle()
    assert client.get("/v1/status").json()["worker_current"] is False


def test_global_drawdown_is_measured_across_days(scenario):
    cfg, _, _ = scenario
    with Store(cfg.path).write() as conn:
        conn.execute("INSERT INTO marks VALUES ('live','2025-01-01','900','1200')")
        assert mark_equity(conn, "live", D(1000), D(".03"), D(".1"))


def test_server_disable_requires_new_confirmation(scenario):
    _, exchange, client = scenario
    enable(client)
    pending = reviewed(client)
    assert post(client, "live/disable").json() == {"live_enabled": False}
    assert post(client, "live/order", pending).status_code == 409
    assert not exchange.executions()
    assert client.get("/v1/status").json()["bot_status"] == "Gestoppt"
