from dataclasses import dataclass
from pathlib import Path
import hashlib
import hmac
import os
import re

from .market import D


def secret(name):
    filename = os.getenv(name + "_FILE")
    value = Path(filename).read_text().strip() if filename else os.getenv(name, "").strip()
    return value


def good_token(value):
    return bool(re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value))


def valid_hash(value, digest):
    return good_token(value) and hmac.compare_digest(hashlib.sha256(value.encode()).hexdigest(), digest)


@dataclass(frozen=True)
class Config:
    path: str
    token_hash_file: str
    live: bool = False
    live_code_hash: str = ""
    bot_code_hash: str = ""
    max_order: D = D(250)
    max_daily: D = D(500)
    risk: D = D(".01")
    max_positions: int = 3
    daily_loss: D = D(".03")
    drawdown: D = D(".10")
    paper_start: D = D(1000)

    @classmethod
    def from_env(cls):
        config = cls(
            os.getenv("NIGHTDESK_DB", "/data/nightdesk.sqlite"),
            os.environ["NIGHTDESK_TOKEN_HASH_FILE"],
            os.getenv("NIGHTDESK_LIVE") == "YES",
            secret("NIGHTDESK_LIVE_CODE_HASH"), secret("NIGHTDESK_BOT_CODE_HASH"),
            D(os.getenv("NIGHTDESK_MAX_ORDER_EUR", "250")),
            D(os.getenv("NIGHTDESK_MAX_DAILY_EUR", "500")),
            D(os.getenv("NIGHTDESK_RISK", ".01")),
            int(os.getenv("NIGHTDESK_MAX_POSITIONS", "3")),
            D(os.getenv("NIGHTDESK_DAILY_LOSS", ".03")),
            D(os.getenv("NIGHTDESK_DRAWDOWN", ".10")),
            D(os.getenv("NIGHTDESK_PAPER_START", "1000")),
        )
        if config.live and (not config.live_code_hash or not config.bot_code_hash
                            or config.live_code_hash == config.bot_code_hash):
            raise ValueError("Separate Freigabecodes für Echtgeld und Automatik erforderlich")
        if (not 0 < config.risk <= D(".01") or not 0 < config.daily_loss <= D(".03")
                or not config.daily_loss <= config.drawdown <= D(".30")
                or not 1 <= config.max_positions <= 3 or not 1 <= config.max_order <= config.max_daily <= 1000000
                or not 1 <= config.paper_start <= 1000000):
            raise ValueError("Ungültige Risikokonfiguration")
        return config

    def authenticate(self, token):
        try:
            return valid_hash(token, Path(self.token_hash_file).read_text().strip())
        except OSError:
            return False
