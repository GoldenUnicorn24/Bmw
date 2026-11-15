# MemePulse AI v0.4 – Android installieren und handeln

**Keine Grok- oder sonstige KI-Modell-API erforderlich.** Die integrierte Quant Engine 1.0 ist ein von uns programmiertes, transparentes Regelwerk und kein trainiertes neuronales Modell. Es garantiert keine Gewinne. Die App enthält keine vorkonfigurierte Wallet und keinen laufenden Server.

## 1. Android APK installieren
1. Öffne https://github.com/GoldenUnicorn24/Bmw/actions
2. Wähle den Workflow **MemePulse AI - Android APK**.
3. Öffne den neuesten erfolgreichen Lauf vom Branch **feature/memepulse-v04-native-ai**.
4. Unter **Artifacts** lade **MemePulseAI-Android-v0.4-debug-APK** herunter. ZIP entpacken und die APK installieren.
5. Beim ersten Start ist **Simulation AUS**. Du siehst den echten SOLANA-Markt. Ist noch keine Wallet verbunden, steht dort bewusst kein erfundenes Guthaben.
6. Unter **Radar → Live-Kurse abrufen** aktualisierst du die Daten. Wähle einen Coin und tippe **Signal prüfen**. Die App analysiert direkt auf dem Handy 5m-/1h-/6h-Trends, Kaufdruck, Liquidität, Volumen sowie den verfügbaren RugCheck-Bericht.

Ein positives Signal ist keine Kauf- oder Gewinngarantie. Die angezeigte Signalstärke ist **keine** statistische Erfolgswahrscheinlichkeit. Der DEX-Screener-Feed enthält auch beworbene Tokens.

## 2. Simulation: NUR unter Einstellungen aktivieren
1. **Einstellungen → Simulationsmodus** einschalten. Der Schalter ist beim ersten Start **AUS**.
2. Jetzt erscheint das fiktive Testkonto mit 1.000 USD Startkapital, Paper-Portfolio und Paper-Historie.
3. **KI-Bot → Paper-Bot starten**. Keine Wallet, kein xAI-Schlüssel und kein Grok nötig.
4. Der Bot nutzt aktuelle reale Kursdaten und simuliert Käufe nach internem Quant-Signal und RugCheck (wenn RugCheck nicht verfügbar ist, kauft er nicht). Maximal 1 % simuliertes Guthaben je Kauf, drei Käufe pro Tag, drei gleichzeitig offene Positionen. Kursbasierte Ausstiegsregeln bei −12 % bzw. +25 %.
5. Bei **Einstellungen → Simulationsmodus AUS** stoppt der Paper-Bot, und die App zeigt wieder nur die reale Oberfläche. Die Einstellung bleibt gespeichert.

Die Simulation läuft nur solange die App aktiv ist. Preise, Slippage und Verfügbarkeit echter Swaps sind nicht garantiert.

## 3. Echtgeld: Eigener Server, eigene Wallet
Die Echtgeld-Oberfläche ist standardmäßig sichtbar, **aber der Server sperrt Transaktionen bis zur ausdrücklichen Freigabe**.

Benötigt wird:
- Ein eigener sicher betriebener Server (Node.js 22+ oder Docker) und ein HTTPS Reverse Proxy mit VPN/IP-Zugriffsgrenze.
- Eine **neue separate Solana-Hot-Wallet** mit sehr wenig Testkapital, niemals deine Haupt-Wallet.
- Jupiter API-Key: https://developers.jup.ag/portal
- Solana HTTPS RPC-Anbieter.
- Ein zufällig generierter API_BEARER_TOKEN mit mindestens 32 Zeichen.
- Eine von dir geprüfte Token-Mint-Allowlist (TRADING_ALLOWLIST). Nur diese Token darf der Bot real kaufen.

Auf dem Server:

    cd memepulse/backend
    cp .env.example .env
    # Die private .env konfigurieren, niemals in GitHub hochladen
    npm install
    npm test
    npm start

Oder:

    docker compose up -d --build

Der Standardserver bindet an 127.0.0.1:8787, Docker veröffentlicht ebenfalls nur localhost. **Nicht ungeschützt ins Internet stellen.** Für das Smartphone HTTPS hinter Reverse Proxy oder VPN einrichten.

Serverseitig private Variablen:

    API_BEARER_TOKEN=<zufällige Zeichenfolge mit mindestens 32 Zeichen>
    SOLANA_PRIVATE_KEY_B58=<Private Key deiner separaten Hot Wallet>
    SOLANA_RPC_URL=https://<dein-RPC-Anbieter>
    JUPITER_API_KEY=<dein Jupiter-Key>
    TRADING_ALLOWLIST=<manuell geprüfte Mint-Adresse(n)>
    MAX_TRADE_SOL=0.01
    MAX_DAILY_SPEND_SOL=0.05
    MIN_SOL_RESERVE=0.02
    MAX_ORDERS_PER_DAY=3
    ENABLE_LIVE_TRADES=YES_I_ACCEPT_REAL_LOSSES

Das ist ein Konfigurationsbeispiel. **Private Wallet-Keys nie im Chat, in Screenshots, TikToks, Claude oder GitHub eintragen.** Die Beta ist noch nicht unabhängig auditiert. Vor einer echten Transaktion ist ein vollständiger Sicherheits- und End-to-End-Test notwendig. Falsche Konfiguration, manipulierte Swaps und Serverausfälle können Totalverlust verursachen.

Android: **Einstellungen → Backend verbinden**. Deine HTTPS-Adresse und den Bearer-Token eingeben, **Verbindung prüfen**. Der Token bleibt nur im Arbeitsspeicher. Im Radar einen Token wählen → **REAL Vorschau** → Quote prüfen → echten Trade ausdrücklich bestätigen.

## 4. Die eigene Engine automatisch Echtgeld handeln lassen

Das Feature ist standardmäßig serverseitig AUS. Zur gesonderten Freigabe auf deinem Server:

    ALLOW_AUTONOMOUS_LIVE=YES_I_ACCEPT_AUTONOMOUS_LOSSES

Danach in der App im **KI-Bot** die Echtgeld-Automatik ausdrücklich für **30 Minuten** starten. Die Engine analysiert ausschließlich die festgelegten erlaubten Mint-Adressen, prüft Risikolimits und RugCheck und führt bei positivem Quant-Signal Jupiter-Swaps aus. Kein Grok-Key und kein sonstiger KI-Key nötig.

**WICHTIGE GRENZE:** Nach Ablauf von 30 Minuten überwacht der Bot offene Echtgeldpositionen **nicht mehr**. Auch während der Sitzung sind Stop-Loss-Regeln nicht garantiert; Blockchain- oder Server-Ausfälle, Slippage, geringe Liquidität und Rug Pulls können Verluste bis 100 % verursachen. Bisher gibt es keinen nachgewiesenen profitablen Backtest, keinen externen Sicherheits-Audit und keine bestätigten echten End-to-End-Trades. Bitte nicht mit nennenswerten Beträgen betreiben.

Not-Aus über **KI-Bot → NOT-AUS**, zusätzlich serverseitig STOP-Datei anlegen (in Docker: /app/private/STOP) oder Server abschalten. Bereits gesendete Swaps sind nicht rückgängig zu machen.

## 5. Fehlersuche

- **Keine Kurse:** Internet prüfen, Radar-Kurse neu laden. Ohne echte Marktdaten keine Engine-Käufe.
- **HOLD:** Sicherheits- oder Marktschwellen nicht erfüllt. Ein HOLD absichtlich nicht übersteuern.
- **Wallet leer:** Server/Wallet ist noch nicht verbunden. App allein kann keine Blockchain-Transaktionen signieren.
- **Auto-Bot gesperrt:** Live-Freigabe, Server-Allowlist, Limits oder Sicherheitsstopp prüfen.
- **UNKNOWN/SUBMITTED:** Blockchainstatus und Wallet manuell abgleichen, nie blind erneut ausführen.

Quellcode im Feature-Branch: https://github.com/GoldenUnicorn24/Bmw/tree/feature/memepulse-v04-native-ai/memepulse
