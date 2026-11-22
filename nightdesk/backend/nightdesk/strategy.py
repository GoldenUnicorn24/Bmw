"""Explainable strategies. Indicator seeds and rules also used by the local Android engine."""
from dataclasses import dataclass
from statistics import mean


@dataclass(frozen=True)
class Parameters:
    momentum: float = .01
    volume_multiplier: float = 1.5
    rsi_floor: float = 52
    rsi_ceiling: float = 68
    oversold: float = 32
    exit_rsi: float = 55
    min_atr: float = .003
    max_atr: float = .025
    window: int = 20


STRATEGIES = {
    "trend": Parameters(), "momentum": Parameters(), "reversion": Parameters(),
    "breakout": Parameters(), "meme": Parameters(momentum=.008, volume_multiplier=1.3),
}


@dataclass(frozen=True)
class Candle:
    time: int
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class Signal:
    direction: int
    reason: str
    atr: float
    regime: str


def parse(rows, interval):
    import math
    candles = [Candle(int(row[0]), *(float(row[i]) for i in (1, 2, 3, 4, 6))) for row in rows]
    for i, c in enumerate(candles):
        if (not all(math.isfinite(x) for x in (c.open, c.high, c.low, c.close, c.volume))
                or not 0 < c.low <= min(c.open, c.close) <= max(c.open, c.close) <= c.high
                or c.volume < 0):
            raise ValueError("Ungültige OHLCV-Kerze")
        if i and (c.time <= candles[i - 1].time or (c.time - candles[i - 1].time) % (interval * 60)):
            raise ValueError("Ungültige OHLCV-Zeitachse")
    return candles


def ema(values, period):
    series = [values[0]]
    multiplier = 2 / (period + 1)
    for x in values[1:]:
        series.append(series[-1] + multiplier * (x - series[-1]))
    return series


def evaluate(candles, name, params=None):
    if len(candles) < 200:
        return Signal(0, "Mindestens 200 abgeschlossene Kerzen erforderlich", 0, "Unbekannt")
    p = params or STRATEGIES[name]
    closes = [c.close for c in candles]
    last = closes[-1]
    e9, e21, e50, e200 = (ema(closes, n)[-1] for n in (9, 21, 50, 200))
    deltas = [b - a for a, b in zip(closes[-15:-1], closes[-14:])]
    gains, losses = sum(max(0, x) for x in deltas), -sum(min(0, x) for x in deltas)
    rsi = 100 - 100 / (1 + gains / losses) if losses else (100 if gains else 50)
    macd = [a - b for a, b in zip(ema(closes, 12), ema(closes, 26))]
    hist = macd[-1] - ema(macd, 9)[-1]
    atr = mean(max(c.high - c.low, abs(c.high - prev.close), abs(c.low - prev.close))
               for prev, c in zip(candles[-15:-1], candles[-14:]))
    volatility = atr / last
    rising = e50 > e200
    if volatility > .04:
        regime = "Volatil"
    elif abs(e50 / e200 - 1) < .005:
        regime = "Seitwärts"
    else:
        regime = "Aufwärtstrend" if rising else "Abwärtstrend"
    momentum = last / closes[-13] - 1
    previous = candles[-p.window - 1:-1]
    multiple = p.volume_multiplier * mean(c.volume for c in previous)
    if name == "trend":
        buy, sell, rule = (rising and e9 > e21 and last > e9 and p.rsi_floor <= rsi <= p.rsi_ceiling,
                           e9 < e21, "EMA 9/21 und EMA 50/200")
    elif name == "momentum":
        buy, sell, rule = (rising and momentum > p.momentum and hist > 0, momentum < 0 and hist < 0,
                           "12-Kerzen-Momentum und MACD")
    elif name == "reversion":
        buy, sell, rule = (regime == "Seitwärts" and rsi < p.oversold and last < e21 - atr,
                           rsi > p.exit_rsi or last >= e21, "RSI und ATR-Abstand zum EMA 21")
    elif name == "breakout":
        buy, sell, rule = (rising and last > max(c.high for c in previous) and candles[-1].volume > multiple,
                           last < min(c.low for c in previous) or last < e21, "20-Kerzen-Kanal und Volumen")
    else:
        buy, sell, rule = (rising and p.min_atr <= volatility <= p.max_atr and momentum > p.momentum and candles[-1].volume > multiple,
                           volatility > .04 or e9 < e21, "ATR-Filter, Momentum und Volumen")
    direction = int(buy) - int(sell)
    side = {-1: "Verkauf", 0: "Warten", 1: "Kauf"}[direction]
    return Signal(direction, f"{side} · {rule} · {regime}", atr, regime)
