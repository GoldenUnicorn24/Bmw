# MemePulse AI v0.7 – Meme-Coins + Penny Stocks, getrennt trainieren

## Was ist neu?

**Zwei unabhängige Forschungsmodelle** mit unterschiedlichen Märkten, Datenauflösungen und Testfenstern:
- **Solana-Meme-Coins:** CoinGecko-Kategorie `solana-meme-coins` + unabhängig bestätigte Solana-Mint-Adresse + GeckoTerminal-DEX-Pool. 5-Minuten-Candles, Ziel nach 60 Minuten, pauschal angenommene Handelsfriktion von 2 %. Die bereits vorhandene Solana-Quant-Engine bleibt als eigenständige regelbasierte Handelsstrategie bestehen. Das neue trainierte Modell darf KEINE Echtgeldorder auslösen.
- **US-Penny Stocks:** Stammaktien aus der Nasdaq-Trader-Symboldatei; nur Nasdaq-Capital-Market-Einträge, keine ETFs, Warrants oder Testausgaben. Nur Preisbeobachtungen, bei denen **der historische Einstiegskurs zwischen 0,10 und 5 USD lag**, und mit einem beobachteten Tages-Dollarumsatz von mindestens 500.000 USD. Quelle für tägliche Kurskerzen: Stooq, sofern erreichbar und zugelassen. Ziel nach **zehn Handelstagen**, pauschal 4 % Handelskosten. Ein anderes Modell mit anderen Merkmalen. **Kein US-Aktienbroker** integriert und kein Penny-Stock-Handel aus der App möglich.

Die Kategorie allein beweist weder, dass ein Meme-Coin nicht betrügerisch ist, noch dass sein Pool zu einem früheren Zeitpunkt sicher handelbar war. Eine unter 5 USD notierte Aktie ist nicht automatisch ein attraktives Investment.

## Forschungsdaten beschaffen

Mit Node.js 22 oder neuer im Verzeichnis memepulse/backend:

    npm install
    npm test
    npm run train:meme
    npm run train:penny

Die Skripte rufen öffentliche Marktquellen ab. Kein Solana-Private-Key, keine Bankdaten und kein Broker-Zugang sind dafür erforderlich.

**Meme-Bericht:** research-output/meme-coins/training-report.json und market-source-metadata.json

**Penny-Stock-Bericht:** research-output/penny-stocks/training-report.json und market-source-metadata.json

Wenn API-Aufrufe gesperrt oder historische Daten unvollständig sind, bleibt der Bericht auf **INSUFFICIENT_DATA**. Es werden KEINE fiktiven Kurse ergänzt.

### Datenherkunft / Nutzungsgrenzen

- CoinGecko-Kategorie: https://www.coingecko.com/en/categories/solana-meme-coins
- GeckoTerminal-API: https://api.geckoterminal.com/docs/index.html
- Nasdaq-Trader-Symboldateien: https://nasdaqtrader.com/Trader.aspx?id=SymbolDirDefs
- Stooq: https://stooq.com/q/d/l/?s=aapl.us&i=d

Die Penny-Stock-Pipeline speichert und veröffentlicht **nur zusammengefasste Ergebnisse und Quellenmetadaten**, keine vollständigen proprietären OHLCV-Kursreihen. Providerlimits und Lizenzregeln beachten. Keine Garantie der Vollständigkeit.

## Modelle / Prüfung

Die beiden Modelle werden vollständig getrennt trainiert:
- Solana-Research: zehn 5-Minuten-Kurs-/Volumenmerkmale, regularisierte logistische Regression, 60-Minuten-Horizont, chronologischer Test inklusive Sperrzeit und ungesehene Pools.
- Aktien-Research: zehn **tagesbasierte** Merkmale, unabhängige regularisierte Regression, 10-Handelstage-Horizont, spätere Testperiode mit Sperrzeit und ungesehene Aktiensymbole.

Das Penny-Stock-Modell liefert keine Ausführungsaufträge, US-Marktzeiten, Börsenplatzwahl oder Steuerberechnungen. OTC/Pink-Sheet-Aktien werden derzeit bewusst **nicht** abgedeckt.

Historische Auswahl nur aktuell noch gelisteter Titel verursacht **Survivorship-Bias**; fehlende Delisting-Renditen und Corporate Actions können Ergebnisse erheblich verzerren. Keine der fiktiven Kostenannahmen ist ein beobachteter, tatsächlich ausführbarer Broker-Trade.

**Jedes Modell bleibt productionAllowed=false**. Weder positive In-Sample-Scores noch eine Trainingsmeldung „RESEARCH_GATE_PASSED“ schalten Echtgeldhandel frei.

## Android-App

Im KI-Bot → Training Lab zeigt v0.7 beide Marktbereiche getrennt. Dazu muss der eigene Backend-Server laufen. Die App zeigt weder erfundene Penny-Stock-Preise noch einen Aktienhandelsknopf.

Zum Anzeigen von echten Berichten im Backend die .env setzen:

    MEME_RESEARCH_REPORT_PATH=/absoluter/pfad/meme-coins/training-report.json
    PENNY_RESEARCH_REPORT_PATH=/absoluter/pfad/penny-stocks/training-report.json

Bei Docker-Berichten den Ordner /app/private als Speicherort für die Berichte verwenden; in compose.yaml sind die Beispieleinträge für diese beiden Dateipfade enthalten. Der Backend-Endpunkt /api/research/markets erfordert Bearer-Authentifizierung und ist nur lesbar.

## Manuelle Rücküberweisung von SOL – jetzt als streng eingeschränktes Backend-Modul

Echte Auszahlungen sind **standardmäßig deaktiviert**. Das Backend v0.7 enthält einen separaten, manuellen SOL-Transferweg mit:
- Vorab auf dem **Server** fest hinterlegter SOL-Zieladresse, die ein normales, gültiges Wallet sein muss. Die Zieladresse kann nicht während der Auszahlung aus der App ausgetauscht werden.
- Eigenem, mindestens 32 Zeichen langem zweiten Freigabetoken, zusätzlich zum üblichen API-Bearer-Token.
- Zwei Schritten (Vorschau und manuelle Bestätigung), höchstens 60 Sekunden zwischen beiden.
- Mainnet-Genesis-Prüfung, Guthabenreserve, maximalem Überweisungsbetrag und gesondertem, auf Festplatte dauerhaftem Journal.
- Unbekannte Überweisungsergebnisse blockieren weitere Versuche und Live-Orders bis zur manuellen Blockchain-Reconciliation.

Zum Einrichten auf eigenem Server (alle Werte nur lokal):

    ENABLE_WALLET_WITHDRAWALS=YES_I_ACCEPT_RESTRICTED_SOL_WITHDRAWALS
    WITHDRAWAL_DESTINATION_B58=<eigens kontrollierte, unabhängig überprüfte Solana-Adresse>
    WITHDRAWAL_SECOND_FACTOR=<neuer zufälliger geheimer Token, mindestens 32 Zeichen>
    MAX_WITHDRAWAL_SOL=0.25
    WITHDRAWAL_LOG_PATH=./private/withdrawals.json

Authentifizierte GET-Route: /api/wallet/withdrawal/status

POST /api/wallet/withdrawal/prepare: JSON mit `amountSol` und extra Header `x-withdrawal-authorization`.

POST /api/wallet/withdrawal/execute: JSON mit `previewId` und `confirm` = `CONFIRM SOLANA WITHDRAWAL`, plus derselbe zweite Autorisierungs-Header.

**Wichtig:** Diese API ist absichtlich noch **nicht als Ein-Klick-Auszahlung in der Android-App** verbunden. Die JavaScript-Tests simulieren den signierten Transfer und den RPC-Bestätigungsstatus. Ein Test gegen eine echte Mainnet- oder Devnet-Transaktion sowie eine unabhängige Sicherheitsprüfung fehlen noch. Keine Hauptwallet oder nennenswerte Beträge einsetzen.

Der separate Tradingserver hält weiterhin den privaten Schlüssel einer eigenen Hot-Wallet. Solana- und Aktiengelder sind **nicht austauschbar**; für echte Penny-Stock-Käufe wäre ein regulierter, separat verwalteter Brokerage-Zugang erforderlich.

## Status und erforderliche Freigaben

Diese Version ist eine **Entwickler-Beta**, kein nutzbarer „einzahlen und vergessen“-Finanzdienst. Keine fremden Echtgeld-Wallets importieren, keine Secrets im Chat oder in GitHub veröffentlichen.

Für risikobegrenzten Echtgeldbetrieb fehlen weiterhin ein unabhängiges Code-Audit, echte End-to-End-Tests der Jupiter-Swaps und Auszahlungen, eine gehärtete Serverumgebung, Überwachung und nachvollziehbare Nachbehandlung unklarer Transaktionen. Ein Datenmodell allein kann das nicht ersetzen.

**Bis dahin: Kein echtes Geld einzahlen.**
