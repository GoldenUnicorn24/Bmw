# MemePulse AI v0.8 – Meme-Coin-Training und Echtgeld-Sicherheitsprüfung

## Stand, 15. November 2026

Der Backend-Quellcode besitzt einen manuellen Solana-Jupiter-Swap-Pfad. Allerdings gibt es **keine tatsächlich verbundene Mainnet-Trading-Wallet, keinen von uns bereitgestellten sicheren Produktionsserver, keinen ausgeführten echten End-to-End-Swap und keinen unabhängigen Security-Audit**. Eine private Wallet und API-Keys werden nicht mit dem öffentlichen Repository ausgeliefert.

Die v0.7-Meme-Forschung hatte nur 3 verifizierte Pools, 130 Beispiele und zwei negative Signale in der zeitlichen Testgruppe. Das Forschungsmodell blieb SHADOW_ONLY. Keine rentable Strategie ist nachgewiesen.

## Mehr überprüfbare Meme-Coin-Trainingsdaten

Die v0.8-Forschung arbeitet mit CoinGecko-Meme-Kategorie und explizit verifizierter Solana-Mint-Adresse. Als Marktdatenquelle dient GeckoTerminal.
- Mehr Kandidaten aus der Solana-Meme-Kategorie, über mehrere Kategorieseiten.
- Bis zu 3 historische 5-Minuten-Kerzenseiten je DEX-Pool und bis zu 2 alternative Pools pro Coin.
- Die tatsächlich verifizierte Meme-Mint-Adresse wird explizit als OHLCV-Token mitgegeben, damit nicht der SOL- oder USDC-Quote-Token anstelle des Meme-Coins trainiert wird.
- Keine künstlich aufgefüllten Kurslücken; das beste Historienfenster wird anhand der Anzahl korrekt zusammenhängender, labelbarer Beobachtungen gewählt.
- Zeitliche Train-/Testtrennung mit Embargo, getrennte ungesehene Mint-Adressen, konservative 2%-Friktionsannahme.
- Keine Lockerung der Mindestanzahl an Beispielen, profitablen Out-of-Sample-Signalen oder unabhängigen Tests.

Reproduktion mit Node.js 22+ auf deinem eigenen Rechner:

    cd memepulse/backend
    npm install
    npm test
    MEME_COIN_LIMIT=16 MEME_CANDLE_PAGES=3 MEME_POOL_TRIES=2 npm run train:meme

Datenherkunft: https://www.coingecko.com/en/categories/solana-meme-coins und https://api.geckoterminal.com/docs/index.html

Ergebnisse liegen nach dem Training in research-output/meme-coins/training-report.json sowie market-source-metadata.json. Die öffentlichen GitHub-Actions-Jobs stellen Berichte als Artefakte bereit. Ein neuer guter Trainingsscore autorisiert niemals Echtgeldhandel. Das trainierte Statistikmodell bleibt von der regelbasierten Live-Engine getrennt.

## Zusätzliche Echtgeld-Ausführungsprüfungen

Das Backend nutzt weiterhin Jupiter Swap API v2 order/execute:
https://developers.jup.ag/docs/swap/order-and-execute

Vor jeder realen Ausführung:
1. Die Mainnet-Genesiskennung wird über das angebundene RPC überprüft.
2. Die Jupiter-v0-Transaktion muss exakt eine Signatur besitzen und die eigene Wallet als Gebührenzahler verwenden. Unerwartete Programm-IDs, SystemTransfers an fremde Empfänger und Token-Close-Transfers an fremde Empfänger werden abgelehnt. Nur gedeckeltes SOL-Wrapping in die eigene deterministisch bestimmte WSOL-ATA ist erlaubt.
3. Die signierte Transaktion muss bei Solana RPC mit sigVerify=true erfolgreich simuliert werden. Eine fehlgeschlagene Simulation oder veraltete Quote blockiert das Senden.
4. Die Order wird als SUBMITTED ins persistente Ledger geschrieben, BEVOR Jupiter sie ausführen darf.
5. Die Antwort von Jupiter muss dieselbe Signatur wie die lokal signierte Transaktion enthalten und strenge Checks für Inputmenge, Output und Slippage bestehen.
6. Eine unabhängige RPC-Abfrage muss die signierte Blockchain-Transaktion bestätigt sehen, ohne Ausführungsfehler und mit passenden Änderungen im Wallet-Bestand.
7. Bei fehlenden Belegen lautet der Zustand UNKNOWN: kein automatischer Retry, keine weitere neue Signatur bis zur manuellen Blockchain-Prüfung. Alte SUCCESS-Einträge ohne validierten RPC-Beleg blockieren ebenfalls weitere Orders.

Diese Checks verhindern nicht jede mögliche bösartige CPI-Programminstruktion im Jupiter-/DEX-Routing. Für einen Produktionsbetrieb ist eine unabhängige Transaktions- und Serverprüfung nötig. Manche legitime Jupiter-Routen werden wegen der konservativen Regeln bewusst abgelehnt.

## Öffentlicher Mainnet-Test ohne Gelder

    cd memepulse/backend
    npm run smoke:mainnet:readonly

GitHub-Actions-Lauf 43746963786 hat tatsächlich erfolgreich geprüft:
- Solana RPC getGenesisHash entspricht der Mainnet-Genesis
- Jupiter Swap API v2 gab eine echte SOL/USDC-Quote zurück, ohne angegebene taker-Wallet und ohne signierbare Transaktion.

Dies beweist keine tatsächliche Blockchain-Ausführung. Kein Geld wurde transferiert; ein privater Wallet-Schlüssel wurde dafür nicht verwendet.

Zusätzliche Node.js-Unit-Tests simulieren signierte v0-Swaps und RPC-Quittungen. Diese sind automatisierte Softwaretests, keine echten Trades.

## Was für echte Gelder noch zu tun ist

1. Eigener gehärteter HTTPS-Tradingserver, separate minimal gefüllte Solana-Hot-Wallet und sichere lokale Secrets. Kein Hauptwallet verwenden.
2. Jupiter API-Key, zuverlässiger Solana-Mainnet RPC, authentifizierte mobile Verbindung und individuelle erlaubte Mint-Adressen.
3. Nachweis einer sicheren manuellen Rücküberweisung in eine zweite Wallet. Die bestehende beschränkte Withdrawal-API ist standardmäßig deaktiviert und in der Android-App noch nicht integriert.
4. Test der echten Route mit einem minimalen, ausdrücklich autorisierten Betrag unter unabhängiger Aufsicht, inklusive Wallet-Änderungen, Slippage, Wiederherstellung nach UNKNOWN und Not-Aus.
5. Externe Sicherheitsüberprüfung aller DEX-Programminstruktionen, Serververwahrung und API-Authentifizierung.

Die entsprechenden Serverfreigaben sind standardmäßig AUS (ENABLE_LIVE_TRADES=NO, ALLOW_AUTONOMOUS_LIVE=NO). Der getrennte Auto-Bot benötigt eine weitere ausdrückliche Freigabe und läuft nur für eine begrenzte Sitzung, keine garantierten Stop-Loss-Orders nach deren Ablauf.

WICHTIG: Auch das neue Training kann keine Profitabilität garantieren, eine hohe Trefferquote auf OHLCV wäre kein Beweis für ausführbare Gewinne, und selbst mit Limits sind Rug Pull und Totalverlust möglich. **Bis zum erfolgreichen externen Audit und echten minimalen End-to-End-Test kein Echtgeld einzahlen.**

Der gesamte Entwicklungscode liegt unter feature/memepulse-v08-live-safety-and-meme-data; die BMW-Haupt-App bleibt unverändert.
