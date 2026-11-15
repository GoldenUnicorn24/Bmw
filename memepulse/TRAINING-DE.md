# MemePulse AI v0.6 – Training & Backtest-Labor

## Was wird trainiert?
Zwei klar getrennte Systeme:

- Der vorhandene Quant Autopilot 2 ist eine konservative, regelbasierte Strategie mit serverseitigen Risikolimits.
- Der OHLCV Research Learner v1 ist ein kleines regularisiertes logistisches Statistikmodell. Es nutzt 5-Minuten-Kerzen echter Solana-Pools und bleibt AUSSCHLIESSLICH Forschungsmodell. Seine Ergebnisse können keine Echtgeldorder freigeben.

Beides ist keine generative Super-KI und keine nachgewiesen gewinnbringende Strategie.

## Datenherkunft
- Historische öffentliche 5-Minuten OHLCV aus der GeckoTerminal API: https://api.geckoterminal.com/docs/index.html
- Anfangsauswahl: Solana-Pools mit mindestens 60.000 USD Liquiditätsreserve, 50.000 USD Volumen pro Tag und 24 Stunden Alter.
- Maximal 1.800 Kerzen pro aktuellem Poolabruf; spätere Trainingsrunden speichern bis 5.000 Kerzen pro Pool und bis zu 40 verschiedene Pools in einem GitHub-Cache.
- Die API begrenzt kostenlose Anfragen. Abstände und wiederholte Versuche bei HTTP 429 sind eingebaut.
- 10 normalisierte Merkmale aus vergangenen Preisen, Volumen, Kursspannen und Trendstabilität.
- Ziel: Kurs nach einer Stunde abzüglich pauschal angenommener 2 Prozent Handelsfriktion. Dies sind keine echten Jupiter-Ausführungskurse.

ACHTUNG: Aktuell sichtbare Pools haben einen massiven Survivorship-Bias. Rug Pulls, Freeze Authorities, fehlgeschlagene Swaps, Steuern, MEV und Slippage können nicht aus den Kerzen rekonstruiert werden.

## Schutz vor übertriebenen KI-Ergebnissen
1. Ein Signal bei Zeitpunkt T benutzt ausschließlich bereits beobachtete Kerzen.
2. Labels beziehen sich auf 60 Minuten in der Zukunft und werden strikt separat zugewiesen.
3. Chronologischer späterer Testblock mit einer 60-Minuten-Sperrzone.
4. Zweiter Test auf durch Pooladresse deterministisch ausgewählten ungesehenen Pools.
5. Trainings-Standardisierung nur auf Trainingsbeispielen. Keine nachträgliche Schwellenoptimierung anhand der Testdaten.
6. Statistik: Brier-Score, Anzahl echter Signale, Treffer nach geschätzten Kosten und Immer-Kaufen-Baseline.
7. Auch ein gutes Modell bleibt productionAllowed=false. Ein Bericht schaltet niemals Handel frei.

## Erste überprüfte Trainingsrunde, 15. November 2026
GitHub Actions Run 43728233461: 8 Solana-Pools, insgesamt 434 gelabelte Beispiele; davon Training 283, späterer Test 114, Test auf ungesehenen Pools 37. Bei der festen konservativen Schwelle: **0 Kaufsignale**. Status SHADOW_ONLY. Keine nachgewiesene Profitabilität. Weitere Datenabrufe wurden wegen HTTP 429 begrenzt.

Die neueren Läufe verwenden größere Historien und Daten-Cache. Ihre tatsächlichen Ergebnisse müssen gesondert bewertet werden.

## Jetzt selbst auf echten Daten trainieren
Im Verzeichnis memepulse/backend mit Node.js 22 oder neuer:

    npm test
    npm run train:research

Mit Internetzugang lädt der Trainingsjob öffentliche Pools. Er hat keinen Zugriff auf deine Wallet und nimmt keine Transaktionen vor.

Er erstellt im Verzeichnis research-output:
- dataset.json: historische abgerufene 5-Minuten-Kerzen mit Poolbezug
- training-report.json: Modellparameter, Anzahl und Qualität der Tests, Provenienz und Warnungen
- README.txt: Datensatz-Herkunft

Auf GitHub Actions werden diese Ergebnisse als ZIP-Artefakte bereitgestellt.

## Geplante Wiederholung
Im Hauptbranch liegt .github/workflows/memepulse-market-research.yml. GitHub Actions führt montags, mittwochs und samstags um 03:20 UTC eine Forschungsrunde aus. Sie nutzt den separaten Training-Branch. Das ist KEINE Live-Trading-Automation; keine Wallet-Secrets, keine echten Swaps, keine Änderungen an produktiven Modellen. Github und Datenanbieter können die Zeitplanung oder Abrufe begrenzen.

## In der App Trainingsergebnisse anzeigen
1. GitHub Actions Forschungsartefakt herunterladen.
2. training-report.json auf den eigenen sicheren Backend-Server kopieren.
3. Dort RESEARCH_REPORT_PATH auf den absoluten Dateipfad setzen und den Server neu starten.
4. Android-App -> KI-Bot -> Training Lab -> Trainingsbericht laden.

Die App zeigt die Forschung separat von realen Wallet-/Handelsdaten an. Kein Trainingsbericht kann eine Echtgeldorder autorisieren.

## Noch nicht produktionsbereit
Es gibt weiterhin keine unabhängig geprüfte Live-Transaktionssignatur, kein abgeschlossenes Ende-zu-Ende-Testing echter Jupiter-Swaps, keinen nachgewiesenen positiven Backtest und keine integrierte Auszahlungsfunktion. Daher kein echtes Geld einzahlen. Ein sicherer Rückweg für Gelder und unabhängiges Audit wären vor jeder Freigabe erforderlich.
