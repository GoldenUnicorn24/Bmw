# NightDesk Trade 2.1 — Kraken Direct

## Ohne eigenen Server: nur Kraken API-Key + Secret

1. APK öffnen → **Einstellungen → Kraken · direkte API-Verbindung**.
2. Kraken-API-Key und **API-Secret** in der App eingeben. NightDesk sendet eine signierte Anfrage an `https://api.kraken.com/0/private/Balance` und speichert die Zugangsdaten erst nach erfolgreicher Antwort. Die lokale Speicherung verwendet Android Keystore AES-GCM. Kein NightDesk-Backend, keine zusätzliche Domain und kein separates Zugangstoken erforderlich.
3. Für Kontostand und Prüfung sind mindestens **Query funds** und **Query open orders & trades** erforderlich; für manuelle Echtgeldorders auch **Create & modify orders** und **Cancel & close orders**. **Niemals Withdraw, Deposit, Transfer, Earn** oder andere Kontoübertragungsrechte aktivieren. Bei API-spezifischer 2FA kann eine gesonderte OTP-Behandlung nötig werden; die direkte Version unterstützt keine API-OTP-Eingabe.
4. Auf dem Papier-Testbuild kannst du die Verbindung und Kontostandsabfrage testen; **Echtgeldorders sind dort gesperrt**. Für Echtgeld benötigt NightDesk einen privat signierten Release-Build (siehe unten). Damit kannst du in **Einstellungen** zur Echtgeldansicht wechseln und unter **Trading** eine Kraken-Marktorder mit Vorschau und zusätzlicher Bestätigung abschicken.
5. Fällt die Verbindung während einer Live-Order aus, blockiert die App neue Echtgeldorders. Prüfe in **Kraken Pro** offene **und** abgeschlossene Orders und bestätige erst dann die manuelle Klärung in den Einstellungen. Es erfolgt **niemals** ein ungesicherter automatischer Wiederholungsversuch.

**Handelsgrenzen:** Spot-EUR-Paare, maximale manuelle Ordergröße 250 €, nur Marktorders, Prüfung von Kraken-Mindestmenge, Spread ≤ 1 %, 24h-Volumen ≥ 20.000 €, bestes sichtbares Geld-/Briefvolumen, Preisverschlechterung gegenüber Vorschau und verfügbarem Guthaben. Gebühren und Ausführung können tatsächlich von der Schätzung abweichen. Alle Live-Ausführungen benötigen eine aktive manuelle Bestätigung; **kein autonomer Live-Bot** wird nur durch Hinterlegen der Schlüssel aktiviert. Eine Android-App bietet beim Schließen/Absturz keine garantierte Stop-Loss-Überwachung.

## Meme Radar, Whale Watch und Handelsgrenzen

Die App nutzt für den Scanner ausschließlich echte Kraken-Daten: `AssetPairs` (handelbare EUR-Paare), `Depth` (25 beste Orderbuchstufen), `Trades` (historische Kraken-Ausführungen) und `Ticker/OHLC` (Volatilität, Liquidität und Kurse). Der Scanner zeigt Orderbuchvolumen, aggressives Kaufverhältnis und große beobachtete Trades. **Dies sind keine On-Chain-Wale oder Wallet-Bestände**. Für Solana-Mint-Adressen gibt es nun zusätzlich den **DEX SCOUT**: Er liest DEX Screener (`token-pairs/v1/solana/{mint}`) und die öffentlichen Solana-RPC-Endpunkte `getAccountInfo`, `getTokenSupply`, `getTokenLargestAccounts`. Er zeigt DEX-Liquidität, 24h-Volumen, Handelstransaktionen, Mint- und Freeze-Berechtigungen und den Anteil der größten zehn **Token-Konten** (nicht zehn eindeutig verschiedene Menschen/Wallets). Bei RPC-Sperren stehen nicht verifizierte Angaben ausdrücklich auf **Unbekannt**. **LP-Lock-Status kann aus diesen Daten nicht sicher bestimmt werden und bleibt unverifiziert.** Der Scanner ist read-only, er kann keine DEX-Käufe ausführen und sendet **keine Kraken-Schlüssel** an DEX Screener oder Solana. Social-Media-Sentiment-Daten werden weiterhin nicht vorgetäuscht. Token ohne Kraken-Listing können nicht mit dem Kraken-Key gehandelt werden; ein DEX-Sniper erfordert grundsätzlich eine separate Wallet, Infrastruktur und on-chain Prüfungen.

## Privater Release-Build

Der öffentliche GitHub-Actions-Testbuild ist für die Echtgeldfunktion bewusst gesperrt. Für die private Echtgeld-APK werden die vorhandenen vier GitHub Actions Secrets (`NIGHTDESK_KEYSTORE_BASE64`, `NIGHTDESK_STORE_PASSWORD`, `NIGHTDESK_KEY_ALIAS`, `NIGHTDESK_KEY_PASSWORD`) gebraucht. Damit ist keine zweite App-Verbindung oder Server-Hosting nötig, aber ein sicherer, dauerhaft kontrollierter Release-Signaturschlüssel. Nicht denselben Debug-Schlüssel eines öffentlichen GitHub-Runners zur Echtgeldfreigabe verwenden. Der reale Handelsweg ist mangels Nutzer-API-Key und echtem Echtgeldtest nicht vollständig End-to-End abgenommen.

---


NightDesk ist eine native Android-App für Kraken-Spotmärkte mit EUR-Kurswährung. Das lokale Papierkonto öffnet mit 1.000 virtuellen Euro und erhält Kursdaten direkt von Kraken. Der separat installierbare Python-Dienst führt den Serverbot aus und verwaltet die Verbindung zum echten Börsenkonto. Die App zeigt den aktiven Handelsmodus oben auf jeder Seite.

## APK installieren

1. Öffne [NightDesk Trade 2.0 auf GitHub](https://github.com/GoldenUnicorn24/Bmw/releases/tag/nightdesk-v2.0) oder lade das Artefakt `NightDesk-Trade-2.0-Papier-Testbuild` aus dem [NightDesk-Workflow](https://github.com/GoldenUnicorn24/Bmw/actions/workflows/nightdesk-android.yml).
2. Bei einem Workflow-Artefakt entpacke zuerst das ZIP. Öffne `NightDesk-Trade-2.0.apk` auf dem Android-Gerät. Android kann einmalig die Erlaubnis für die verwendete Installationsquelle verlangen.
3. Starte NightDesk. Der Papier-Testbuild verwendet die Anwendungs-ID `de.nightdesk.trade.paper`. Die mit einem privaten Release-Schlüssel signierte Variante verwendet `de.nightdesk.trade` und bekommt ein eigenes lokales Papierkonto.
4. Alternativ installierst du die APK über `adb install -r NightDesk-Trade-2.0.apk`. Die App unterstützt Android ab API 26, kompiliert gegen API 35 und enthält native Bibliotheken für `arm64-v8a` und Emulatoren. Der Samsung Galaxy S26 Ultra benötigt eine separate Geräteabnahme.

Der Papier-Testbuild hat einen Debugsignaturschlüssel und sperrt sämtliche Kraken-Echtgeldaktionen in der App. Android-Updates benötigen dieselbe Anwendungs-ID und denselben Signaturschlüssel. GitHub-gebaute Papier-Testversionen können einen anderen Debugschlüssel als lokale Builds haben.

## Papiermodus testen

1. Öffne **Dashboard**. Das virtuelle Startguthaben beträgt 1.000 €. Prüfe unter **Märkte**, dass das gewählte Paar zum aktuellen Kraken-Handelsangebot gehört. Preis, Volumen und Spread stammen von Kraken. Ein Gedankenstrich steht für fehlende aktuelle Daten.
2. Wähle beispielsweise **BTC/EUR**. Öffne **Trading**, gib eine Menge oberhalb des angezeigten Börsenminimums ein und fordere eine Ordervorschau an. Erhöhe oder senke die Menge so, dass sie zum virtuellen Kontostand passt.
3. Kontrolliere Preis, voraussichtliche Ausführungsmenge und Gebühr. Bestätige die Papierorder. Bei schlechterem Kurs ist eine neue Vorschau nötig. Der Simulator verarbeitet verfügbare Menge am besten Geld-/Briefkurs und verwirft den unausgeführten Teil. Geschätzte Slippage beträgt 10 Basispunkte, die Gebühr 40 Basispunkte.
4. Prüfe Position und Bargeld im Dashboard, führe einen Teilverkauf aus und kontrolliere die Ausführung unter **Statistik**. Schließe und starte die App erneut. Die Room-Datenbank erhält Guthaben, Ausführungen, Watchlist und bewertete Portfoliopunkte.
5. Unter **Bot** wählst du eine Regel und startest den lokalen Papierbot. Er verwendet abgeschlossene 15-Minuten- und 1-Stunden-Kerzen. Die Erklärung zeigt die ausgewerteten Regeln. Der lokale Bot arbeitet während des laufenden App-Prozesses. Bei Serververbindung kannst du einen separaten Papierbot mit eigenem virtuellen Serverkonto starten.
6. Tippe **Not-Stopp**. Die App hält den lokalen Bot an und fordert bei verbundenem Backend den Serverstopp an. Bestehende Positionen und offene Börsenorders bedürfen separater Entscheidungen.
7. Unter **Einstellungen** änderst du das virtuelle Startkapital über **Papierkonto zurücksetzen**. Die App startet einen neuen Simulationszeitraum und behält das bisherige Ausführungsjournal in der Datenbank.

## Marktregeln und historische Auswertung

| Strategie | Kaufidee | Ausstieg |
| --- | --- | --- |
| Trend Following | EMA 9 über EMA 21, EMA 50 über EMA 200 und RSI im Einstiegsbereich | EMA 9 unter EMA 21 |
| Momentum | 12-Kerzen-Momentum, positives MACD-Histogramm und Aufwärtstrend | Negatives Momentum und negatives MACD-Histogramm |
| Mean Reversion | Seitwärtsphase, niedriger RSI und Abstand unter dem EMA 21 | Rückkehr zum EMA 21 oder RSI-Erholung |
| Breakout | Ausbruch über den vorherigen 20-Kerzen-Kanal bei erhöhtem Volumen | Unter Kanal oder EMA 21 |
| Meme-Volatilitätsfilter | ATR-Band, Positionsmomentum, Trend und erhöhtes Volumen | Extreme ATR oder negativer EMA-Filter |

Die App zeigt unter **Bot → MEME RADAR** für das ausgewählte Kraken-EUR-Paar einen nachprüfbaren Kurs- und Liquiditätscheck, 15-Minuten-ATR, ein 1-%-Risikobudget und eine daraus berechnete Positionsmenge. Die lokale Papierautomatik nutzt denselben Größenrechner mit maximal 30 % verfügbarem Bargeld, 250 € je Kauf und der sichtbaren Markttiefe als zusätzliche Schranken. Die Anzeige ist **kein Kaufsignal**. Ein Stop-Loss bei −3 % oder Take-Profit bei +6 % kann jetzt auch nach einem Kauf in derselben 15-Minuten-Kerze auslösen; sie werden nur vom laufenden Bot überwacht und sind **keine Kraken-Stop-Orders**.

**Grenze gegenüber TikTok-Grok-Bots:** LP-Sperren, Mint-/Freeze-Rechte, Holder-Clusters, Whale-Wallets, Social-Media-Sentiment und der automatische Kauf neu erzeugter DEX-Token können nicht aus Kraken-Ticker/OHLCV-Kursen abgeleitet werden. NightDesk bietet diese Funktionen deshalb nicht als angeblich durchgeführte Prüfung an. Dafür wären zusätzliche, validierte On-Chain-/DEX-Datenquellen und eigenständig abgesicherte Ausführungswege erforderlich. Derzeit bleibt Echtgeld ausschließlich serverseitig und nur nach bewusster Freigabe möglich.

Der Bot verwirft Kursdaten außerhalb der Frischegrenzen und prüft Spread, Mindestvolumen und verfügbare Liquidität. Der Filter für Meme-Coins verwendet dieselben Spotdaten und Regeln wie die übrigen Strategien. Coin-Verfügbarkeit hängt von den aktiven Kraken-EUR-Paaren ab.

Unter **Statistik → Strategien vergleichen** wertet NightDesk echte Kraken-OHLCV-Kerzen aus. Die Auswertung validiert Reihenfolge, Preisbereich und Volumen. Die erste Hälfte dient als Aufwärmhistorie. Das Auswertungsfenster läuft chronologisch in Blöcken von bis zu 100 Kerzen vorwärts. Jedes Signal nutzt ausschließlich bereits abgeschlossene Kerzen. Die simulierte Ausführung erfolgt frühestens zur nächsten Eröffnung. Kauf und Verkauf enthalten Gebühren und Slippage. Buy-and-Hold nutzt dieselben zeitlichen Endpunkte und Kosten. Die Auswertung zeigt Nettoergebnis, Drawdown, Treffer, Profit Factor, Erwartungswert und Ergebnis je Regime. Die Kraken-Schnittstelle begrenzt die verfügbare Historie, und ältere Daten erfordern einen separaten Import. Der Vergleich bewertet fest definierte Regeln. Es gibt kein trainiertes ML-Modell.

## Backend und Kraken

Die [Hosting- und Kraken-Anleitung](BETRIEB.md) beschreibt Docker, HTTPS, Token, API-Schlüssel, zweistufige Freigabe, Serverautomatik, Signierung und Betrieb. Die [Sicherheits- und Abnahmedokumentation](SICHERHEIT.md) beschreibt Zustandsgrenzen und Prüfungen.

## Lokal entwickeln

Voraussetzungen sind JDK 17, Gradle 8.11.1 und die Android-SDK-Plattform 35 mit Build Tools 35.0.0.

```sh
cd nightdesk
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
gradle :app:connectedDebugAndroidTest
```

`connectedDebugAndroidTest` benötigt ein Gerät oder einen laufenden Emulator. Die App liegt nach dem Build unter `app/build/outputs/apk/debug/app-debug.apk`.

```sh
cd nightdesk/backend
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements-test.txt
python -m pytest -q
```

NightDesk liegt in einem eigenständigen Gradle-Projekt unter `nightdesk/`. Der NightDesk-Workflow veröffentlicht die APK ausschließlich unter dem NightDesk-Release. Der Executive-Black-Workflow filtert reine NightDesk-Pushes aus. Von NightDesk 1.0 stammen die Grundstruktur der Kraken-Signierung, Geldrundung und Indikatorideen. MemePulse lieferte Anregungen zur Liquiditäts- und Spread-Prüfung. Sein Solana-/Penny-Stock-Pfad und das Backend verbleiben in ihren jeweiligen Branches.
