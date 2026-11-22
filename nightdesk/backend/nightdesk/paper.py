from .accounting import positions
from .market import D, ZERO

FEE = D(".004")
SLIPPAGE = D(".001")


class PaperBroker:
    def __init__(self, store, start):
        self.store = store
        self.start = start

    def account(self, conn):
        rows = list(conn.execute(
            """SELECT orders.symbol, orders.side, fills.volume AS filled, fills.cost, fills.fee
               FROM fills JOIN orders ON orders.id=fills.order_id WHERE orders.mode='paper'
               ORDER BY fills.at,fills.rowid"""
        ))
        cash = self.start + sum((
            (D(row["cost"]) if row["side"] == "sell" else -D(row["cost"])) - D(row["fee"])
            for row in rows), ZERO)
        return cash, positions(rows)

    def submit(self, conn, order, quote, limit):
        cash, held = self.account(conn)
        quantity = D(order["quantity"])
        buy = order["side"] == "buy"
        price = quote.ask * (1 + SLIPPAGE) if buy else quote.bid * (1 - SLIPPAGE)
        if limit is not None and (price > limit if buy else price < limit):
            raise ValueError("Server-Papierbroker simuliert nur sofort handelbare Limits")
        fill = min(quantity, quote.ask_quantity if buy else quote.bid_quantity)
        cost, fee = fill * price, fill * price * FEE
        if buy and cash < cost + fee:
            raise ValueError("Simuliertes Guthaben reicht nicht")
        if not buy and (order["symbol"] not in held or held[order["symbol"]].quantity < fill):
            raise ValueError("Simulierte Position reicht nicht")
        status = "filled" if fill == quantity else "partial_canceled"
        conn.execute("UPDATE orders SET status=?,filled=?,cost=?,fee=? WHERE id=?",
                     (status, str(fill), str(cost), str(fee), order["id"]))
        conn.execute("INSERT INTO fills VALUES (?,?,?,?,?,unixepoch())",
                     (order["id"], order["id"], str(fill), str(cost), str(fee)))
