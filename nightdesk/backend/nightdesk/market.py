from dataclasses import dataclass
from decimal import Decimal
from time import monotonic

from .kraken import ExchangeError

D = Decimal
ZERO = D(0)


def decimal(value):
    result = D(str(value))
    if not result.is_finite():
        raise ValueError("Ungültiger Zahlenwert")
    return result


@dataclass(frozen=True)
class Pair:
    symbol: str
    code: str
    base: str
    quote: str
    quantity_decimals: int
    tick: Decimal
    minimum: Decimal
    minimum_cost: Decimal


@dataclass(frozen=True)
class Quote:
    bid: Decimal
    ask: Decimal
    last: Decimal
    volume: Decimal
    bid_quantity: Decimal
    ask_quantity: Decimal
    received: float

    def validate(self):
        if (monotonic() - self.received > 25 or not ZERO < self.bid <= self.ask or self.last <= ZERO
                or min(self.bid_quantity, self.ask_quantity) <= ZERO):
            raise ValueError("Kursdaten fehlen, sind veraltet oder ungültig")
        if (self.ask - self.bid) / self.last > D(".01") or self.volume < D(20000):
            raise ValueError("Spread oder Liquidität außerhalb der Grenzen")


class MarketData:
    def __init__(self, client):
        self.client = client
        self.cached = {}
        self.updated = float("-inf")

    def pairs(self):
        if monotonic() - self.updated > 300:
            result = self.client.call("AssetPairs")
            self.cached = {
                item["wsname"].replace("XBT/", "BTC/").replace("XDG/", "DOGE/"): Pair(
                    item["wsname"].replace("XBT/", "BTC/").replace("XDG/", "DOGE/"), symbol, item["base"], item["quote"],
                    item["lot_decimals"], decimal(item["tick_size"]),
                    decimal(item["ordermin"]), decimal(item["costmin"]))
                for symbol, item in result.items()
                if item.get("wsname", "").endswith("/EUR") and item.get("status") == "online"
            }
            self.updated = monotonic()
        return self.cached

    def pair(self, symbol):
        pair = self.pairs().get(symbol)
        if pair is None:
            raise ValueError("Kein aktives Kraken-EUR-Paar")
        return pair

    def quote(self, pair):
        started = monotonic()
        result = self.client.call("Ticker", {"pair": pair.code})
        if len(result) != 1:
            raise ExchangeError("Kraken-Kurs fehlt")
        row = next(iter(result.values()))
        return Quote(decimal(row["b"][0]), decimal(row["a"][0]), decimal(row["c"][0]),
                     decimal(row["v"][1]) * decimal(row["p"][1]), decimal(row["b"][2]),
                     decimal(row["a"][2]), started)

    def candles(self, pair, interval):
        result = self.client.call("OHLC", {"pair": pair.code, "interval": interval})
        series = [rows for key, rows in result.items() if key != "last"]
        if len(series) != 1:
            raise ExchangeError("Kraken-Kerzen fehlen")
        return series[0][:-1]
