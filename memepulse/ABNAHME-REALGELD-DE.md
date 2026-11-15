# MemePulse AI v0.9 – Abnahmecheckliste für echten Solana-Handel

**Status:** technische Entwickler-Beta. Eine erfolgreiche APK, 45+ Tests und ein positiver Simulationslauf sind KEIN Nachweis eines sicheren Echtgeld-Handelssystems.

## 1. Was jetzt im Produkt ist

- Eigene Kotlin-Android-App, Code im Ordner memepulse/android; das separate BMW-Projekt bleibt unverändert.
- Quant Autopilot 2 ohne Grok, definierte Token-Freigabeliste, aufeinanderfolgende echte DEX-Kursbeobachtungen, RugCheck und konservative Positionsgrößen.
- Eigenständig trainiertes Meme-Coin-Forschungsmodell v2 mit getrennten Zeit-/Pool-Holdouts und konservativen 2h-Loss-/Take-Profit-Kerzenlabels; **keine automatische Echtgeldfreigabe**.
- Ein Backend mit Jupiter-Transaktionsvorbereitung, expliziter Signierprüfung, lokalem Signaturabgleich, unabhängiger Solana-RPC-Bestätigung und dauerhaftem Tradejournal.
- **Keine automatische Freigabe von fremden DEX-Programmen oder Address Lookup Tables**. Jeder externe Programmcode erfordert unabhängige Prüfung; aktuell werden unreviewte Routen verweigert.
- Manuelle eingeschränkte SOL-Auszahlung **nur** an die vorher serverseitig festgelegte eigene Wallet, mit zweitem Token und separatem Bestätigungsdialog in der Android-App. Standard AUS.
- App → Einstellungen → **Sicherheitscheck**: eigens ermittelter Backend-Status, exakter Mainnet-Genesis-Abgleich, Wallet/API/Allowlist/STOP-Prüfung und sichtbare offene Punkte. Die Punkte „echte Kauf-/Verkaufs-/Auszahlungsprüfung“ und „unabhängiger Audit“ bleiben bewusst NICHT bestanden.
- Journal-Einträge werden atomar mit Dateisynchronisation und **ohne pauschales Abschneiden** gespeichert. Ein altes UNKNOWN bleibt erhalten und stoppt weitere riskante Transaktionen.

## 2. Keine erfundenen Gewinne / neue Datenergebnisse

Die im GitHub-Lauf 43747458501 beendete letzte verifizierte Meme-Forschung:
- 6 verifizierte Meme-Pools und 528 echte aus historischen Candles berechnete Beispiele.
- 356 Trainingsfälle, 143 spätere Zeitpunkte, 21 bisher ungesehene Pools.
- Keine Kaufsignale oberhalb der konservativen Forschungsschwelle.
- Modell bleibt SHADOW_ONLY. Es gibt KEIN nachgewiesenes positives Handelsmodell.

GitHub: https://github.com/GoldenUnicorn24/Bmw/actions/runs/43747458501

## 3. Installation

1. GitHub öffnen: https://github.com/GoldenUnicorn24/Bmw/actions
2. Workflow **MemePulse AI - Android APK** auswählen.
3. Den neuesten **erfolgreichen** Lauf auf Branch **feature/memepulse-v09-readiness** auswählen.
4. Unter Artifacts **MemePulseAI-Android-v0.9-debug-APK** herunterladen und das Archiv entpacken. Die APK auf deinem Android-Handy installieren.
5. **Simulationsmodus** ist ab Werk AUS. Nur Einstellungen → Simulationsmodus kann ihn aktivieren.

Der Build wird erst als v0.9-APK bezeichnet, wenn GitHub Actions die Android-Kompilierung erfolgreich abgeschlossen hat.

## 4. Einmalige Einrichtung – derzeit NICHT ohne Nutzerzugang möglich

Ein sicherer Nutzerbetrieb braucht:

- Eigener Linux-/Docker-Server, HTTPS über VPN / IP-Allowlist und sicher konfigurierten Reverse Proxy. Keine offen im Internet zugängliche API.
- Eine **neu angelegte, nur dafür bestimmte Hot-Wallet** mit sehr wenig Geld. Private Schlüssel bleiben ausschließlich im sicheren Secret Store des Servers; **niemals in diesem Chat oder in GitHub hochladen**.
- Solana-Mainnet-RPC und gültiger Jupiter API-Key.
- Ein zufälliges mindestens 32 Zeichen langes API-Bearer-Token.
- Explizit geprüfte Token-Mint-Adressen (TRADING_ALLOWLIST).
- Signaturprogramm-IDs dürfen **nur nach echtem Code-/Instruktionsaudit** in SWAP_ALLOWED_PROGRAM_IDS eingetragen werden.
- Vorher erlaubte Auszahlungsziel-Wallet und ein unabhängig generierter, mindestens 32 Zeichen langer zweiter Auszahlungs-Code. Ohne diese Daten ist der Rücküberweisungsweg nicht aktiviert.
- Ein off-chain verschlüsseltes Backup und getesteter Restore des Journals; nur eine aktive Serverinstanz.

Beispiel mit Docker (nach sicherem Setzen der .env):

    cd memepulse/backend
    cp .env.example .env
    npm install
    npm test
    docker compose up -d --build

Docker stellt Port 8787 standardmäßig nur an 127.0.0.1 des Hosts bereit. Für das Smartphone sicheren HTTPS-Zugang einrichten.

Die Desktop-/Cloud-Servereinrichtung ist bisher **nicht durchgeführt**. Auf keinen Fall Geld an Adressen senden, die du nicht unabhängig als deine eigene kontrollierte Wallet verifiziert hast.

## 5. Prüfplan vor einer Echtgeldfreigabe – NOCH OFFEN

Die folgende Abnahme muss ein unabhängiger Fachmann gegen eine abgesicherte Umgebung durchführen:

- [ ] Abhängigkeiten, Transaktionsparser und jedes freigegebene Jupiter-/DEX-Programm auf schädliche Instruktionskonten, ALTs, Token-2022-Erweiterungen, Close Authorities, Transfer Fees und Signaturrechte prüfen.
- [ ] Sicheres Secret-Management, HTTPS/VPN, IP-Allowlist, Logging ohne Private Keys, Server-Patching und Backups überprüfen.
- [ ] Integrationsprüfung in einer eigens isolierten Wallet ohne fremde Gelder mit reproduzierbarer Ordervorschau und direkt gegengeprüftem on-chain Signed Message Hash.
- [ ] Einen **echten** SOL→Token-Kauf mit winzigem, bewusst riskiertem Testbetrag ausführen; tatsächliche Input-/Output Tokenbeträge, Gebühren, Slippage und Wallet-Balances unabhängig überprüfen.
- [ ] Einen **echten** Token→SOL-Verkauf testen; SOL-Reserve, Token-Abrechnung, Fehler- und Timeout-Szenarien unabhängig überprüfen.
- [ ] Manuelle SOL-Auszahlung an die eigene freigegebene Zielwallet separat testen; Empfänger und Betrag im Blockexplorer und in beiden Wallets abgleichen.
- [ ] Beim absichtlichen RPC-/Jupiter-Ausfall und einem unklaren Swap verifizieren, dass keine automatische Wiederholung und kein neuer Kauf erfolgt.
- [ ] Serverneustart mit SUBMITTED-/UNKNOWN-Handelsjournal, Papier-Test, Exit-only-Lauf und STOP-Datei testen; keine unbekannten Trades überspringen.
- [ ] Trading- und Auszahlungssoftware extern auditieren lassen und echte Ergebnisse dokumentieren.
- [ ] Erst danach im Betrieb bewusst LIVE-Flags freigeben. Das trainierte Forschungsmodell weiterhin nicht automatisch in den Echtgeldhandel aufnehmen.

**Live-Flags bleiben ab Werk AUS.** Auch die eigens geprüften On-Chain-Protokolle und Stop-Loss-Regeln können bei Meme-Coins keinen Totalverlust ausschließen.

## 6. Bedienung nach sicherer Einrichtung

1. Einstellungen → Backend verbinden → HTTPS-URL + API-Token.
2. Einstellungen → Sicherheitscheck aktualisieren; fehlende Punkte beheben.
3. Einstellungen → Auszahlungsberechtigung prüfen; dann nur bei Bedarf SOL-Betrag und den **separaten** Auszahlungs-Freigabecode eingeben. Der Code wird in der Android-App nicht dauerhaft gespeichert.
4. App zeigt eine separate Vorschau mit vollständiger, vom Server vorab festgelegter Zieladresse. Erst nach gezielter Bestätigung wird überhaupt eine signierte Transaktion angefragt.
5. Autopilot bleibt separat opt-in und endet nach dem festgelegten Zeitfenster; nach Ablauf und bei Serverausfällen besteht kein garantierter automatischer Verlustschutz.

## 7. Was ich ohne deine eigenen Zugänge nicht übernehmen konnte

- Aufsetzen und Absichern **deines** Servers und des Secrets-Stores.
- Erzeugen oder Übernehmen privater Wallet-Schlüssel; du solltest solche Schlüssel niemals an einen Chatbot weitergeben.
- Kaufen, Verkaufen und Rücküberweisen mit tatsächlichem SOL auf Mainnet.
- Die unabhängige Prüfung durch einen externen Auditor.
- Training eines nachweislich profitablen Modells: bisher fehlen genügend unabhängige bestätigte Signale.

**Folgerung:** MemePulse v0.9 ist ein fortgeschrittener Entwicklungsstand mit Bedienung, Risikoprüfungen und Tests. Es ist noch kein sicherer Service, bei dem man einfach Geld einzahlt und ihn unbeaufsichtigt handeln lässt.
