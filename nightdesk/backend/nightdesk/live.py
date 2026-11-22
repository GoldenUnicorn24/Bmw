from datetime import datetime, timedelta, timezone
import hmac

from .kraken import Uncertain
from .market import D, ZERO, decimal

REQUIRED = frozenset(("query-funds", "query-open-trades", "query-closed-trades", "modify-trades", "close-trades"))
PENDING = ("sending", "unknown", "open", "pending", "cancel_requested")


class LiveBroker:
    def __init__(self, client):
        self.client = client

    def permission_check(self):
        info = self.client.private("GetApiKeyInfo")
        permissions = info.get("permissions")
        actual_key = info.get("apiKey")
        if not isinstance(actual_key, str) or not hmac.compare_digest(actual_key, self.client.key):
            raise ValueError("Kraken-Schlüsselidentität konnte nicht geprüft werden")
        if not isinstance(permissions, list) or any(not isinstance(p, str) for p in permissions) or set(permissions) != REQUIRED:
            raise ValueError("Kraken-Schlüssel braucht genau fünf Handelsrechte. Zusatzrechte sind gesperrt.")

    def balances(self):
        data = self.client.private("BalanceEx")
        balances, available = {}, {}
        for asset, item in data.items():
            total = decimal(item["balance"])
            if total < ZERO or decimal(item.get("credit_used", 0)):
                raise ValueError("Negatives Guthaben oder Kraken-Kreditnutzung erkannt")
            balances[asset] = total
            available[asset] = max(ZERO, total - decimal(item["hold_trade"]))
        return balances, available

    def open(self):
        return self.client.private("OpenOrders", {"trades": "true"})["open"]

    def closed(self, offset=0, start=None):
        params = {"trades": "true", "ofs": offset}
        if start is not None:
            params["start"] = start
        result = self.client.private("ClosedOrders", params)
        return result["closed"], result["count"]

    def place(self, order, pair):
        params = {
            "pair": pair.code, "type": order["side"], "ordertype": order["kind"],
            "volume": order["quantity"], "cl_ord_id": order["id"], "oflags": "fciq",
            "deadline": (datetime.now(timezone.utc) + timedelta(seconds=12)).isoformat(timespec="milliseconds"),
        }
        if order["limit_price"] is not None:
            params["price"] = order["limit_price"]
        result = self.client.private("AddOrder", params)
        txids = result.get("txid")
        if not isinstance(txids, list) or len(txids) != 1 or not isinstance(txids[0], str) or not txids[0]:
            raise Uncertain("Kraken hat keine eindeutige Order-ID zurückgegeben")
        return txids[0]

    def query(self, txid):
        return self.client.private("QueryOrders", {"txid": txid, "trades": "true"}).get(txid)

    def find(self, clid, started, opened):
        matches = [(txid, info) for txid, info in opened.items() if info.get("cl_ord_id") == clid]
        if matches:
            return matches[0] if len(matches) == 1 else (None, None)
        # Bound each scan. Missing evidence leaves the order blocked.
        for offset in range(0, 250, 50):
            closed, total = self.closed(offset, max(0, started - 60))
            matches.extend((txid, info) for txid, info in closed.items() if info.get("cl_ord_id") == clid)
            if offset + len(closed) >= total:
                return matches[0] if len(matches) == 1 else (None, None)
            if not closed:
                break
        return None, None

    def fills(self, txid, order):
        expected = decimal(order["vol_exec"])
        ids = order.get("trades") or []
        if not expected:
            return []
        if not ids or len(set(ids)) != len(ids):
            raise Uncertain("Kraken meldet Ausführung ohne eindeutige Trade-IDs")
        entries = []
        for offset in range(0, len(ids), 20):
            part = ids[offset:offset + 20]
            data = self.client.private("QueryTrades", {"txid": ",".join(part), "trades": "false"})
            for trade_id in part:
                row = data[trade_id]
                if row["ordertxid"] != txid:
                    raise Uncertain("Trade gehört zu einer anderen Kraken-Order")
                volume, cost, fee = (decimal(row[key]) for key in ("vol", "cost", "fee"))
                if volume <= ZERO or cost <= ZERO or fee < ZERO:
                    raise Uncertain("Kraken-Ausführung enthält ungültige Werte")
                entries.append((trade_id, volume, cost, fee, int(row["time"])))
        if (sum(row[1] for row in entries) != expected or sum(row[2] for row in entries) != decimal(order["cost"])
                or sum(row[3] for row in entries) != decimal(order["fee"])):
            raise Uncertain("Summe der Einzelausführungen stimmt mit Kraken-Order noch nicht überein")
        return entries

    def cancel(self, txid):
        self.client.private("CancelOrder", {"txid": txid})
