"""Kraken Spot REST signing derived from the supplied NightDesk 1.0 client."""
import base64
import hashlib
import hmac
import re
import threading
import time
from urllib.parse import urlencode

import requests


class ExchangeError(ValueError):
    pass


class Rejected(ExchangeError):
    pass


class Uncertain(ExchangeError):
    pass


class Kraken:
    BASE = "https://api.kraken.com"

    def __init__(self, key="", secret="", session=None):
        self.key, self.secret = key.strip(), secret.strip()
        self.session = session or requests.Session()
        self.lock = threading.Lock()
        self.last_nonce = 0

    @staticmethod
    def signature(path, nonce, postdata, secret):
        digest = hashlib.sha256((nonce + postdata).encode()).digest()
        return base64.b64encode(hmac.new(
            base64.b64decode(secret, validate=True), path.encode() + digest, hashlib.sha512
        ).digest()).decode()

    def call(self, endpoint, data=None, private=False):
        path = f"/0/{'private' if private else 'public'}/{endpoint}"
        try:
            if private:
                if not self.key or not self.secret:
                    raise Rejected("Kraken-Zugang fehlt")
                with self.lock:
                    self.last_nonce = max(time.time_ns() // 1000, self.last_nonce + 1)
                    nonce = str(self.last_nonce)
                    body = urlencode({"nonce": nonce, **(data or {})})
                    signature = self.signature(path, nonce, body, self.secret)
                    response = self.session.post(self.BASE + path, data=body, headers={
                        "API-Key": self.key, "API-Sign": signature,
                        "Content-Type": "application/x-www-form-urlencoded",
                    }, timeout=(4, 8), allow_redirects=False)
            else:
                response = self.session.get(self.BASE + path, params=data or {}, timeout=(4, 8), allow_redirects=False)
            if response.status_code != 200:
                raise Uncertain(f"Kraken HTTP {response.status_code}")
            envelope = response.json()
            errors = envelope["error"]
            if not isinstance(errors, list):
                raise Uncertain("Ungültige Kraken-Antwort")
            if errors:
                codes = [str(code) for code in errors]
                safe = [code for code in codes if re.fullmatch(r"[EW][A-Za-z]+:[A-Za-z0-9 -]{1,85}", code)]
                message = ", ".join(safe) if len(safe) == len(codes) else "Kraken meldet einen Fehler"
                if all("duplicate" not in code.lower() and code.startswith(("EOrder:", "EAPI:Invalid key", "EAPI:Invalid signature",
                                         "EAPI:Invalid nonce", "EGeneral:Permission denied",
                                         "EGeneral:Invalid arguments")) for code in codes):
                    raise Rejected(message)
                raise Uncertain(message)
            if not isinstance(envelope["result"], dict):
                raise Uncertain("Ungültiges Kraken-Ergebnis")
            return envelope["result"]
        except ExchangeError:
            raise
        except (requests.RequestException, ValueError, KeyError, TypeError) as exc:
            raise Uncertain("Kraken-Verbindung oder Antwort konnte nicht geprüft werden") from exc

    def private(self, endpoint, data=None):
        return self.call(endpoint, data, private=True)
