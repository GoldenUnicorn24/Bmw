# Serverbetrieb und Kraken-Verbindung

## Backend hinter HTTPS starten

Benötigt werden ein Linux-Server mit Docker Compose, eine Domain mit DNS-Eintrag auf diesen Server und erreichbare Ports 80/443 für Caddy. Der Backend-Port 8000 bleibt im Docker-Netz. Der Dienst ist für einen Betreiber, einen laufenden Serverprozess und ein separates Kraken-Spotkonto ausgelegt.

```sh
cd nightdesk/backend
cp .env.example .env
# NIGHTDESK_DOMAIN in .env auf die eigene Domain setzen. NIGHTDESK_LIVE=NO lassen.
umask 077
mkdir -p secrets operator
python3 - <<'PY'
from hashlib import sha256
from pathlib import Path
from secrets import token_urlsafe

for name in ("token", "live", "bot"):
    value = token_urlsafe(48)
    with Path(f"operator/{name}").open("x") as output:
        output.write(value + "\n")
    with Path(f"secrets/{name}.sha256").open("x") as output:
        output.write(sha256(value.encode()).hexdigest() + "\n")

for name in ("kraken_key", "kraken_secret"):
    Path(f"secrets/{name}").touch(exist_ok=False)
PY
sudo chgrp -R 10001 secrets
chmod 0750 secrets
chmod 0640 secrets/*
docker compose config -q
docker compose up -d --build
docker compose ps
```

`operator/` enthält drei zufällige Werte, die du lokal in einem Passwortmanager sichern kannst. Der Dienst liest den SHA-256-Hash des Tokens bei jeder Anfrage. Verwende ein eigenes Token für NightDesk. Verbinde die App unter **Einstellungen → Backend · HTTPS** mit `https://deine-domain` und dem Wert aus `operator/token`. `Backend verbunden` bestätigt eine erfolgreich authentifizierte Statusanfrage. Für einen Test ohne Kraken-Konto kannst du den Server-Papierbot starten.

Setze die Limits in `.env` und wende sie über `docker compose up -d` an. Standardmäßig betragen das angenommene Risiko 1 %, die Zahl der Bot-Positionen 3 und das Tagesverlustlimit 3 %. Der Standard für den Gesamtdrawdown ist 10 %, für neue Echtgeldorders 250 € je Order und für Echtgeldkäufe 500 € je UTC-Tag. Die Risikobewertung verwendet den ersten erfolgreich beobachteten Kontowert des UTC-Tags und den höchsten beobachteten Wert. Ein- und Ausgänge verändern diesen Messwert. Eine eigene Spot-Wallet mit EUR und direkt bewertbaren Spotbeständen hält die Berechnungen nachvollziehbar.

## Kraken-API einrichten

1. Erstelle in Kraken Pro einen neuen API-Schlüssel ausschließlich für dieses Backend. Aktiviere **Query funds**, **Query open orders & trades**, **Query closed orders & trades**, **Create & modify orders** und **Cancel & close orders**. Belasse Auszahlungs-, Einzahlungs-, Transfer-, Earn- und alle anderen Rechte deaktiviert. Binde den Schlüssel nach Möglichkeit an die feste ausgehende Server-IP. [Kraken Pro API-Schlüssel](https://support.kraken.com/articles/how-to-create-an-api-key-on-kraken-pro)
2. Hinterlege Public Key und Private Key über einen lokalen Secret-Manager oder direkt in `secrets/kraken_key` und `secrets/kraken_secret`. Erhalte Gruppen-ID 10001 und Rechte 0640. Der Container liest die Dateien. Bewahre Börsenschlüssel ausschließlich auf dem Server auf. Für einen dauerhaft laufenden Worker unterstützt der Client einen Schlüssel ohne API-spezifischen OTP-Prompt.
3. Setze `NIGHTDESK_LIVE=YES`, halte die unabhängigen Live- und Automatikcodes bereit und starte die Konfiguration über `docker compose up -d`. Verwende für den Echtgeldmodus eine NightDesk-APK mit sicherer Release-Signatur.
4. In **Einstellungen** klickst du **Kraken-Zugriff prüfen**. Das Backend prüft die Identität und die exakte Berechtigungsliste über `GetApiKeyInfo` sowie echte Guthaben und Order-Endpunkte. Gib innerhalb von fünf Minuten den Wert aus `operator/live` ein und bestätige **Echtgeld freischalten**. [Kraken Get API Key Info](https://docs.kraken.com/api-reference/account-data/get-api-key-info)
5. Prüfe unter **Dashboard** die Guthaben und unter **Trading** die Orders. Für Marktorders bleibt das Preislimit leer. Für Limitorders gibst du einen EUR-Preis gemäß Tickgröße ein. Kontrolliere Vorschau und Order-ID, bevor du bestätigst. Der Live-End-to-End-Test mit echten Schlüsselrechten und Orders steht aus.
6. Die automatische Echtgeldfreigabe erfordert zusätzlich den Wert aus `operator/bot`. Wähle Symbol und Strategie unter **Bot**. Der laufende Server übernimmt die Ausführung. Der App-Neustart öffnet die Papieransicht. Der Serverbot ist ein separater Zustand. Verwende **Pause** oder **Not-Stopp**, um ihn zu beenden.

## Not-Stopp, Schlüsselrotation und Wiederherstellung

- Drücke **Not-Stopp** und warte auf die Serverbestätigung. Bereits übermittelte Aufträge können bei Kraken ausgeführt werden. Prüfe offene Orders in Kraken Pro und storniere sie bei Bedarf bewusst. Verwende **Echtgeld serverseitig sperren**, um zusätzlich die Live-Freigabe zu deaktivieren.
- Bei unbekanntem Status vergleiche die gespeicherte Client-ID mit der Kraken-Orderhistorie. Der Worker gleicht offene und abgeschlossene Orders ab. Solange die Identität oder Ausführung ungeklärt bleibt, bleiben neue Echtgeldorders gesperrt. Öffne bei einem Serverausfall Kraken Pro für einzelne Stornierungen und den direkten Kontostand.
- Sperre bei kompromittiertem Gerät den Bot und rotiere das Zugriffstoken auf dem Server. Erzeuge offline einen neuen Zufallswert, ersetze den Hash in `secrets/token.sha256` atomar mit korrektem Gruppenbesitz und verbinde das Gerät erneut. Rotiert werden Börsenschlüssel in Kraken Pro und im Server-Secret-Manager. Wechsle die Freigabecode-Hashes bei einem Neustart der Serverinstanz.
- Sichere das SQLite-Volume mit einem SQLite-Online-Backup oder bei gestopptem Backend. Teste Wiederherstellungen zunächst mit `NIGHTDESK_LIVE=NO`. Für eine kontrollierte Migration die alte Instanz stoppen und danach die Datenbank an genau einer neuen Instanz nutzen. Nach einer Wiederherstellung vor dem Freischalten das Journal gegen die Kraken-Historie prüfen.

## Release-Signierung

Die GitHub-Secrets heißen `NIGHTDESK_KEYSTORE_BASE64`, `NIGHTDESK_STORE_PASSWORD`, `NIGHTDESK_KEY_ALIAS` und `NIGHTDESK_KEY_PASSWORD`. Konfiguriere sie für einen privaten Android-Release-Schlüssel. Der Release-Job stellt den Schlüssel temporär bereit, prüft die APK-Signatur und veröffentlicht die APK unter `nightdesk-v2.0`. Bewahre den Schlüssel für spätere Updates in einem abgesicherten Ort auf. Ohne vollständigen Secrets-Satz veröffentlicht dieser Job einen ausdrücklich bezeichneten Papier-Testbuild. Ein bereits veröffentlichter Papier-Testbuild hängt an diesem Tag. Für eine spätere sicher signierte Distribution muss der Betreiber die Release-Veröffentlichung bewusst neu freigeben.
