# MemePulse v0.8 – echtes Meme-Coin-Training und Echtgeld-Sicherheitsstand

## Der aktuelle Sachstand

MemePulse hat einen eigenständigen Node.js/Solana-Tradingserver. **Echte Transaktionen sind nach Installation weiterhin AUS.** Die neu trainierte KI ist ein kleines, auf historischen Daten trainiertes Forschungsmodell und **darf weiterhin keine Echtgeldorder freigeben**. Der vorhandene separate Quant-Autopilot ist regelbasiert und wird nur nach ausdrücklicher Freigabe aktiv.

**Diese Beta ist NICHT offiziell produktions-/auditbereit.** Es gibt weder einen vom Benutzer eingerichteten gehosteten Server, noch echte Mainnet-Testtransaktionen, noch einen unabhängigen Sicherheitsbericht. Kein echtes Geld einzahlen.

## Verbesserte Meme-Coin-KI (v0.8)

- Die Coin-Auswahl muss gleichzeitig in der CoinGecko-Kategorie **solana-meme-coins** erscheinen und einer gültigen Solana-Mint-Adresse zugeordnet sein.
- Statt nur 9 erfassbaren Coins lassen sich jetzt bis zu 40 verifizierte Coins pro Forschungsrunde betrachten. Der Standard ist 24; in GitHub Actions werden bis zu 25 angefragt.
- Abgerufene OHLCV-Daten stammen von GeckoTerminal; die Pipeline kann mehrere historische 5-Minuten-Seiten abrufen. Fehlende Kerzen werden NICHT interpoliert.
- Features verwenden **ausschließlich vergangene Kurse und Volumen**.
- Trainingsziel ist nun eine simulierte Position über höchstens **120 Minuten**, mit Stop-Loss −12 %, Take-Profit +25 % und **pauschal angenommenen 3 % Roundtrip-Kosten**.
- Falls innerhalb derselben 5-Minuten-Kerze sowohl Stop als auch Ziel berührt werden, gilt konservativ der Verluststopp zuerst. Öffnet eine Kerze unter dem Stopp, wird nur der schlechtere Eröffnungspreis angenommen.
- Das Modell ist eine regularisierte logistische Regression v2 mit festem Schwellenwert; kein trainiertes Sprachmodell und keine zuverlässige Renditeprognose.
- Prüfung auf spätere Zeiträume mit 120-Minuten-Sperrzone und auf zuvor ungesehene Coins. **productionAllowed=false** unabhängig von der historischen Quote.
- Die Labels sind Simulationen, KEINE echten ausführbaren Jupiter-Trades. Rug Pulls, MEV, Insiderverkäufe, Honeypots und eine unvollständige historische Auswahl bleiben wesentliche Risiken.

### Selbst mit echten öffentlichen Daten trainieren

Im Backend mit Node.js 22+ und Internet:

    cd memepulse/backend
    npm install
    npm test
    MEME_COIN_LIMIT=25 MEME_CANDLE_PAGES=2 npm run train:meme

Berichte: research-output/meme-coins/training-report.json und market-source-metadata.json. Das Research-Workflow-Artefakt auf GitHub enthält die aus der Quelle abgeleiteten aggregierten Testergebnisse. **Kein API- oder Wallet-Secret ist erforderlich.**

Quelle: https://www.coingecko.com/en/categories/solana-meme-coins und https://api.geckoterminal.com/docs/index.html

Wenn nur wenige echte Pools vorliegen, bleibt der Status SHADOW_ONLY/INSUFFICIENT_DATA. Eine positive Schwelle darf nicht nachträglich anhand des Testsets optimiert werden.

## Zusätzliche Echtgeld-Sicherheit: Jupiter-Transaktionsprüfung

Früher prüfte die signierende Wallet vor allem, ob ihr eigener Schlüssel in der externen Jupiter-Transaktion auftaucht. **Das allein reicht nicht.**

Jetzt prüft der Server vor Ordervorschau UND vor tatsächlicher Signatur unter anderem:
- Transaktion im Solana-v0-Format, angemessene Paketgröße, einziger erforderlicher Signer und unveränderte Gebührenzahler-Wallet.
- Ausführbare Programme aus einer **vom Betreiber vorher überprüften Allowlist**, nicht nur irgendwelche Account-Schlüssel. Dynamisch aus ALT-Tabellen referenzierte, unbekannte Programme werden abgelehnt.
- Keine beliebigen nativen SOL-Transfers: Ein Transfer vom Wallet muss zum abgeleiteten WSOL-Associated-Token-Account gehen und unterhalb des freigegebenen Einsatzes liegen.
- Keine SPL-Token-Instruktionen zur Erteilung unbekannter Authorities, Mint-Erzeugung oder unkontrollierten Token-Ausgabe.
- Kein übertriebenes Compute-Budget, keine unerwartet hohen Netzwerkprioritätsgebühren.
- Für erfolgreiches Signing muss die von Jupiter zurückgegebene Signatur zur **lokal erzeugten Signatur** passen.
- Ein „Success“ von Jupiter genügt nicht allein: Die Signatur muss anschließend unabhängig vom eigenen Solana-RPC als bestätigt/finalisiert gemeldet werden. Tatsächliche Input- und Outputbeträge müssen vollständig vorliegen und die Limits erfüllen.
- Bei unklarer Transaktion wird der Ledger-Eintrag auf **UNKNOWN** gesetzt und weiteres Trading gesperrt, bis der Nutzer die Blockchain und Wallet manuell abgeglichen hat.

**Wichtige Einschränkung:** Auch das ist KEIN vollständiger Audit beliebiger Smart Contracts oder aller Jupiter-Routen. Ein fremdes Swap-Programm kann weitere Konten und Programmaufrufe beeinflussen; deshalb muss jedes zugelassene Programm unabhängig geprüft werden. Strenge Filter können valide Swaps ebenfalls blockieren.

### Start einer echten Serverkonfiguration – noch nicht freigeben

Die vorhandenen .env-Variablen bleiben erforderlich: API_BEARER_TOKEN, SOLANA_PRIVATE_KEY_B58 (nur in sicherem Server-Secret-Store), JUPITER_API_KEY, SOLANA_RPC_URL, TRADING_ALLOWLIST, MAX_TRADE_SOL, MAX_DAILY_SPEND_SOL, STOP_FILE.

Neu ist:

    SWAP_ALLOWED_PROGRAM_IDS=

**Leer = kein Echtgeldserverstart**, falls ENABLE_LIVE_TRADES aktiv ist. Nur nach einer unabhängigen Prüfung der konkreten Jupiter-Routen wird eine kommaseparierte Liste der tatsächlich geprüften Programmadressen eingetragen. Ein ungeprüftes Copy-and-Paste einer beliebigen Programmliste ist keine Sicherheitsfreigabe.

Weiterhin gelten nur nach ausdrücklich autorisierten Handelsfreigaben:

    ENABLE_LIVE_TRADES=YES_I_ACCEPT_REAL_LOSSES
    ALLOW_AUTONOMOUS_LIVE=YES_I_ACCEPT_AUTONOMOUS_LOSSES

Ohne eigene Infrastruktur und geprüfte Schlüssel ist diese Konfiguration nicht nutzbar. Kein Versprechen auf einen „nur einzahlen“-Dienst oder Gewinn.

## Welche Tests wurden ergänzt?

1. Unit-Tests mit gefälschten v0-Swap-Paketen: erlaubte Programmliste, zusätzliche Signer, fremde SOL-Transferziele, gefährliche Token-Instruktionen, ungewöhnliche Prioritätsgebühren.
2. Integrationstest mit einem vollständig synthetischen Solana-RPC und Jupiter-Netzwerk. Keine Verbindungen zu einer echten Blockchain. Prüft den Orderfluss bis zum dauerhaften Ledger, unbekannte/ausgetauschte Signaturen und Sperren unbekannter Programme.
3. Historische Meme-Strategie-Tests: Stop vor Take-Profit, Gaps unter dem Stop, Umgang mit unvollständigen Kerzen, Zukunftsdaten dürfen keine aktuellen Modellmerkmale beeinflussen.

**Noch offen:** ein realer Mainnet-Test mit minimalem Einsatz, Quoten-/Transaktionsinstruktionsprüfung für echte Jupiter-Routen, realer Sell und Auszahlung, Infrastrukturhärtung, unabhängiger Audit, Überwachung und Wiederherstellung nach einem Ausfall.

## App installieren

GitHub Actions → MemePulse AI - Android APK → letzter erfolgreicher Lauf auf dem Branch feature/memepulse-v08-meme-live-hardening → Artifacts → MemePulseAI-Android-v0.8-debug-APK.

Die Simulation bleibt weiterhin standardmäßig AUS und ist nur in Einstellungen aktivierbar. Das Forschungsmodell darf keine automatischen Echtgeldorders erteilen.

**Bis alle End-to-End- und Sicherheitsprüfungen bestanden sind, bitte kein Geld einzahlen.**
