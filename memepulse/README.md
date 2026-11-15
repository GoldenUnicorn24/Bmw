# MemePulse AI v0.4 — Native Android Trading Terminal

Eine eigenständige Android-App und Node.js-Solana-Trading-Backend im separaten Feature-Branch des BMW-Repositories. Das vorhandene BMW-Projekt unter /app bleibt unverändert.

**Vollständige Anleitung auf Deutsch: [SETUP-DE.md](SETUP-DE.md)**

## Ohne Grok und ohne externe Sprachmodell-API

MemePulse nutzt die selbst programmierte **Quant Engine 1.0**:
- 5m-/1h-/6h-Momentum und Kauf-/Verkaufsdruck
- Liquidität, 1h-/24h-Handelsvolumen, Token-Alter und FDV/Liquidität
- Transparente Scores und HOLD/BUY-Erklärungen
- Separate vollständige RugCheck-Prüfung vor positivem Order-Signal; bei Fehlern keine Käufe
- Deterministische Risikokontrollen, unabhängig vom Quant-Score

Dies ist ein **regelbasiertes** Trading-Modell und **kein** trainiertes neuronales Netz. Es hat keine nachgewiesene Gewinnerwartung.

## Android
- Kotlin + Jetpack Compose, Android 8+
- Native Dark-Mode-Oberfläche mit Dashboard, Solana-Radar, Bot, Trades, Einstellungen
- **Normaler Echtgeld-Modus bei Erststart**; ohne Server und Wallet eindeutig als gesperrt markiert
- **Simulation standardmäßig AUS**; kann ausschließlich in den Einstellungen eingeschaltet werden
- Getrennte reale Wallet-Daten und simulierte Guthaben/Positionen, keine Vermischung
- On-device Quant-Signale ohne API-Schlüssel
- Optionaler eigener Paper-Bot: echte Kursdaten, fiktive Orders, lokale Strategie und RugCheck
- Manuelle Echtgeld-Swap-Vorschau mit expliziter Bestätigung

## Server
- Node.js >=22, REST API, Auth-Bearer Token, nur localhost gebunden
- Solana Jupiter Swap API V2, getrennte Server-Hot-Wallet
- Mint-Allowlist, per-Trade und tägliche Einsatzlimits, Positionslimit, RugCheck, Sperre auf unklaren Transaktionen
- Vollautomatischer **echter** Bot separat opt-in für maximal 30 Minuten, serverseitig zunächst deaktiviert
- Nach Ablauf läuft KEINE automatische Exit-Überwachung weiter
- Docker-Beispiel, reproduzierbare Risikotests, Android GitHub Actions Debug APK

## Android Build
GitHub Actions -> **MemePulse AI - Android APK** -> neuester erfolgreicher Lauf auf
**feature/memepulse-v04-native-ai** -> **Artifacts** -> MemePulseAI-Android-v0.4-debug-APK.

Lokal (Android SDK 35, Gradle 8.12, JDK 17):

    cd memepulse/android
    gradle :app:assembleDebug

## Backend Test

    cd memepulse/backend
    npm install
    npm test
    cp .env.example .env
    npm start

Ohne sichere Einrichtung bleiben Echtgeld-Orders deaktiviert.

## Stand und Warnhinweise
Technische Entwickler-Beta, kein externer Audit, keine vollständigen E2E-Blockchain-Tests; realer Handel kann zu Totalverlust führen. Script- und UI-Kompilierung beweisen keine Handelssicherheit. Signierte Jupiter-Transaktionen sollten vor produktivem Einsatz unabhängig verifiziert werden. Keine echte Gewinnstrategie nachgewiesen. Token-Risikoscores und Stop-Loss-Grenzen können einen Rug Pull, Liquiditätsausfall oder Kursrutsch nicht verhindern.

- Jupiter: https://developers.jup.ag/docs/swap/order-and-execute
- DEX Screener: https://docs.dexscreener.com/api/reference
- RugCheck: https://api.rugcheck.xyz/swagger/index.html
