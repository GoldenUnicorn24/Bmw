from datetime import datetime, timezone
from decimal import ROUND_DOWN

from .market import D, ZERO


def quantity_step(amount, places):
    return amount.quantize(D(1).scaleb(-places), rounding=ROUND_DOWN)


def validate_order(pair, quote, side, quantity, limit, max_notional, allow_partial=False):
    quote.validate()
    if side not in {"buy", "sell"} or quantity < pair.minimum or quantity_step(quantity, pair.quantity_decimals) != quantity:
        raise ValueError("Ungültige Handelsrichtung oder Börsenmenge")
    if limit is not None and (limit <= ZERO or limit % pair.tick):
        raise ValueError("Preis entspricht nicht der Kraken-Tickgröße")
    best = quote.ask if side == "buy" else quote.bid
    price = limit if limit is not None else best
    if quantity * price < pair.minimum_cost:
        raise ValueError("Börsen-Mindestwert unterschritten")
    if quantity * max(price, best) > max_notional:
        raise ValueError("Maximaler Orderwert überschritten")
    if not allow_partial and limit is None and quantity > (quote.ask_quantity if side == "buy" else quote.bid_quantity):
        raise ValueError("Menge über verfügbarer Liquidität zum besten Kurs")


def mark_equity(conn, mode, equity, daily_limit, drawdown_limit):
    if equity <= ZERO:
        raise ValueError("Kein bewertbares Guthaben")
    now = datetime.now(timezone.utc)
    day = now.date().isoformat()
    today = conn.execute("SELECT opening,peak FROM marks WHERE mode=? AND day=?", (mode, day)).fetchone()
    global_peak = max((D(row[0]) for row in conn.execute("SELECT peak FROM marks WHERE mode=?", (mode,))), default=equity)
    opening = D(today["opening"]) if today else equity
    peak = max(equity, D(today["peak"]) if today else equity)
    conn.execute("INSERT INTO marks VALUES (?,?,?,?) ON CONFLICT(mode,day) DO UPDATE SET peak=excluded.peak",
                 (mode, day, str(opening), str(peak)))
    bucket = int(now.timestamp()) // 60 * 60
    conn.execute("INSERT OR IGNORE INTO equity_samples VALUES (?,?,?)", (mode, bucket, str(equity)))
    return (equity <= opening * (1 - daily_limit) or equity <= global_peak * (1 - drawdown_limit))
