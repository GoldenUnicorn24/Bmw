# MemePulse AI v0.3 – App installieren und Grok-Trading einrichten

## 1. Android App installieren
1. Github -> GoldenUnicorn24/Bmw -> Actions -> **MemePulse AI - Android APK**.
2. Einen **erfolgreichen** Lauf auf Branch **feature/memepulse-v03-pro-trader** öffnen.
3. Artifacts -> **MemePulseAI-Android-v0.3-debug-APK** herunterladen, ZIP entpacken und APK installieren.
4. Erster Start: **PAPER** und frei erfundene Demo-Coins. Im Radar auf **Live-Kurse abrufen** tippen.
5. Manuelle Paper-Käufe/Verkäufe funktionieren ohne Wallet und ohne Backend.

**Achtung:** Der DEX-Screener-Feed besteht aus bezahlten/optimierten Profilen. Die Liste ist keine objektive Markt-Bestenliste. Es gibt keine Gewinngarantie.

## 2. Grok-Paper-Bot verwenden (ohne Echtgeld)
Der Paper-Bot handelt nur dann automatisch, wenn die xAI-API verbunden ist und eine Token-Prüfung durch den separaten Server erfolgreich war. Ohne Grok gibt es bewusst keine erfundenen "KI-Kaufsignale".

Voraussetzungen: Node 22+ oder Docker, xAI API-Key, HTTPS-Server oder VPN, eigenes starkes API-Bearer-Token von mindestens 32 zufälligen Zeichen.

    cd memepulse/backend
    cp .env.example .env

Trage API_BEARER_TOKEN und XAI_API_KEY in die lokale, private .env ein. Lasse ENABLE_LIVE_TRADES=NO stehen. Für Paper-KI brauchst du **keinen** Wallet-Private-Key.

Mit Docker:

    docker compose up -d --build

Der API-Server lauscht nur an 127.0.0.1 des Hosts. Für Android ist ein sicherer HTTPS-Reverse-Proxy nötig (z.B. Caddy hinter VPN oder mit Zugriffsschutz). **Port 8787 niemals ungeschützt öffentlich freigeben.** Alternativ Node starten:

    npm install
    npm test
    npm start

In der Android-App: **System -> HTTPS-Adresse und API-Token -> Verbindung prüfen**. Danach **Radar -> Live-Kurse -> KI-Bot -> KI-Paper-Bot starten**.

Die Paper-KI läuft nur während die App ausgeführt wird. Neue simulierte Positionen brauchen ein positives Grok-Signal, aktuelle Kurse und serverseitigen RugCheck. Pro Kauf maximal 1% des simulierten Kapitals, bis zu 3 Positionen, Stop-Loss bei -12%, Take-Profit bei +25%, angenommene 0,8% Kosten pro Handelsseite. Paper-Performance ist keine Vorhersage von Echtgeld-Performance.

## 3. Echte Trades (risikoreich, noch nicht auditiert)
Das Backend enthält Jupiter-Swap-Endpunkte für Solana. Echte Trades sind **ab Werk gesperrt**.

Du benötigst gesondert eine neue isolierte, niedrig finanzierte Hot Wallet und nur auf dem Server gespeicherte Secrets, Jupiter API-Key, Solana HTTPS-RPC, explizit geprüfte Mint-Adressen in TRADING_ALLOWLIST und den konfigurierten ENABLE_LIVE_TRADES-Wert YES_I_ACCEPT_REAL_LOSSES.

In der App: System -> Real-Modus, im Radar Coin wählen -> Echtgeld-Vorschau -> jedes manuelle Swap bestätigen.

Der autonome Echtgeld-Bot muss zusätzlich auf dem Server ausdrücklich mit ALLOW_AUTONOMOUS_LIVE=YES_I_ACCEPT_AUTONOMOUS_LOSSES freigeschaltet und in der App für eine 30-Minuten-Sitzung bestätigt werden. Er führt während dieser Sitzung bei freigegebenen Coins nur dann Orders aus, wenn Grok und feste Sicherheits-/Risikoregeln sie zulassen.

**Die Implementierung ist eine Entwickler-Beta, nicht geprüft oder für echtes Kapital freigegeben.** Das System besitzt keine garantierten, dauerhaft auf der Blockchain liegenden Stop-Loss-Orders. Nach Ende der Sitzung erfolgen keine automatischen Verkäufe. Server, RPC, Kurse, RugCheck, Jupiter und Grok können ausfallen. Auch ein kleiner Testbetrag kann vollständig verloren gehen. Keine Konten, Key-Phrasen oder Passwörter in ChatGPT, Claude, Screenshots oder GitHub veröffentlichen.

## 4. Sicherheit und Not-Aus
- Keine privaten Schlüssel im Android-Client. Die App speichert den API-Token nur im Arbeitsspeicher.
- Der HTTP-Server darf **niemals direkt** aus dem offenen Internet erreichbar sein.
- Ein Stop über die App unter **KI-Bot -> NOT-AUS** stoppt weitere KI-Orders. Eine bereits eingereichte Blockchain-Transaktion ist nicht widerrufbar.
- Einen laufenden Container kannst du zusätzlich mit **docker compose stop** anhalten. Bei bereits offenem Tokenbestand schützt das aber nicht vor Kursverlusten.
- UNKNOWN oder SUBMITTED Trades blockieren automatisierte Neukäufe bis zur manuellen Klärung.
- Ein separater GitHub-Zweig schützt die ursprüngliche BMW-Anwendung vor Änderungen.
