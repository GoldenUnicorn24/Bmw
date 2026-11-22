from dataclasses import dataclass

from .market import D, ZERO


@dataclass
class Position:
    quantity: D = ZERO
    basis: D = ZERO

    @property
    def average(self):
        return self.basis / self.quantity if self.quantity else ZERO


def positions(rows):
    """Replay fills in execution order using moving average cost."""
    held = {}
    for row in rows:
        position = held.setdefault(row["symbol"], Position())
        volume = D(row["filled"])
        if not volume:
            continue
        cost, fee = D(row["cost"]), D(row["fee"])
        if row["side"] == "buy":
            position.quantity += volume
            position.basis += cost + fee
        elif position.quantity:
            sold = min(position.quantity, volume)
            position.basis -= sold * position.average
            position.quantity -= sold
            if not position.quantity:
                position.basis = ZERO
    return {symbol: position for symbol, position in held.items() if position.quantity}


def statistics(rows):
    held = {}
    outcomes = []
    fees = ZERO
    for row in rows:
        position = held.setdefault(row["symbol"], Position())
        quantity, cost, fee = (D(row[key]) for key in ("filled", "cost", "fee"))
        fees += fee
        if row["side"] == "buy":
            position.quantity += quantity
            position.basis += cost + fee
        elif position.quantity and quantity:
            matched = min(position.quantity, quantity)
            basis = matched * position.average
            outcome = (cost - fee) * matched / quantity - basis
            position.quantity -= matched
            position.basis = position.basis - basis if position.quantity else ZERO
            outcomes.append((row["symbol"], outcome))
    gains = [value for _, value in outcomes if value > ZERO]
    losses = [value for _, value in outcomes if value < ZERO]
    by_symbol = {symbol: str(sum((value for name, value in outcomes if name == symbol), ZERO))
                 for symbol in {name for name, _ in outcomes}}
    return {
        "realized": str(sum((value for _, value in outcomes), ZERO)),
        "fees": str(fees),
        "wins": len(gains),
        "trades": len(outcomes),
        "avg_gain": str(sum(gains) / len(gains)) if gains else None,
        "avg_loss": str(sum(losses) / len(losses)) if losses else None,
        "by_symbol": by_symbol,
        "positions": {name: {"quantity": str(position.quantity), "basis": str(position.basis)}
                      for name, position in held.items() if position.quantity},
    }
