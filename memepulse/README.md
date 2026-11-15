# MemePulse AI v0.3 — Pro Trader Android (Beta)

Independent native Android app plus protected Node trading gateway, stored in a separate folder of the BMW repository. Existing BMW app remains untouched.

## Neu in v0.3

- Neuer nativer **KI-Bot-Tab** mit getrennten Aktionen für PAPER und Echtgeld, Grok-Status, Bot-Ereignissen und Not-Aus.
- **KI-Paper-Autotrading** mit Echtkursen, Grok + RugCheck-Freigabe, Positions-/Tageslimit und regelbasierten Exits. Ohne verbundenes Grok keine automatischen Käufe.
- **RugCheck-Sicherheitskorrektur**: Roh-Risikowert und vollständige Mint-/Freeze-Berechtigungen statt fraglicher normalisierter Score-Richtung.
- Serielle Swap-Ausführung und Handelsstopp bei unklaren/inkonsistenten Blockchain-Fills.
- Docker-Backend nur an localhost, verbesserte Tests und separate Android-APK v0.3.
- **[Deutsche Schritt-für-Schritt-Einrichtung](SETUP-DE.md)**.

**Stand:** Quellcode + Debug-Build. Keine Server-Instanz oder Wallet wurde im Chat eingerichtet; ohne eigene Grok- und Wallet-API-Verbindung läuft noch kein Echtgeldhandel. Code ist nicht auditiert, kein garantierter Stop Loss oder Profit.

## Features
- Native Android UI: Jetpack Compose, dark trading terminal, Android 8.0+
- Solana market radar: DEX Screener promoted token feeds, liquidity, 1h momentum, volume, heuristic market score.
- Persistent paper portfolio: equity chart, buy/sell, approximate 0.8% per-side fee, win rate, journal.
- Optional Grok AI signal review via the xAI Responses API (requires own key).
- Server-side RugCheck token summary, market quality gates and explicit mint allowlist before real BUY.
- Jupiter Swap API V2: signed real-world buy/sell transactions, separate backend hot wallet, ephemeral order preview and explicit confirmation.
- Optional *unattended* trading only after server-side and app-side approval, hard daily spending cap, per-trade cap, limited order count, 30-minute session, STOP file and kill-switch.
- GitHub Actions builds a separate debug APK without replacing the BMW application.

## Android APK
GitHub -> Actions -> "MemePulse AI - Android APK" -> latest successful run -> Artifacts -> MemePulseAI-Android-v0.3-debug-APK. Download the artifact archive and extract its APK.

To build locally: JDK 17, Android SDK platform 35, Gradle 8.12.
cd memepulse/android
gradle :app:assembleDebug

## Backend (Node 22+)
cd memepulse/backend
cp .env.example .env
npm install
npm test
npm start

Generate an unpredictable API_BEARER_TOKEN of >=32 characters. The backend binds 127.0.0.1 by default. Android connections require a proper TLS HTTPS reverse proxy, authorization and infrastructure hardening. Do not expose the HTTP server itself to the open internet.

Initial config is **PAPER / LIVE DISABLED**. To prepare a real swap you must also configure:
- SOLANA_PRIVATE_KEY_B58: newly generated and separately funded low-balance hot wallet, in server secrets ONLY.
- JUPITER_API_KEY: from the Jupiter developer portal.
- SOLANA_RPC_URL: reliable HTTPS RPC.
- TRADING_ALLOWLIST: *individually reviewed* Solana mint addresses.
- ENABLE_LIVE_TRADES=YES_I_ACCEPT_REAL_LOSSES
- MAX_TRADE_SOL=0.01; MAX_DAILY_SPEND_SOL=0.05; MIN_SOL_RESERVE=0.02; MAX_ORDERS_PER_DAY=3.

These are trade exposure caps, **not a daily stop-loss**. No private key or seed phrase belongs in a prompt, frontend, GitHub repository, or screenshots. Never use your primary wallet.

Start Grok assistance by setting XAI_API_KEY and optionally XAI_MODEL. The agent only returns BUY or HOLD; fixed guards independently accept or reject.

Unattended live trades additionally require ALLOW_AUTONOMOUS_LIVE=YES_I_ACCEPT_AUTONOMOUS_LOSSES and an explicit in-app activation dialog. The server forgets activation on restart and stops after 30 minutes. A STOP file in the backend working directory blocks future transactions. A broadcast on-chain swap cannot be reversed.

## Interfaces (all except /health require Bearer auth)
GET /health, GET /api/status, GET /api/market, GET /api/wallet, GET /api/trades
POST /api/ai/signal, POST /api/orders/prepare, POST /api/orders/execute
POST /api/agent/start, POST /api/agent/stop

## Limits and known issues
This is an un-audited development beta and does **not** guarantee profitability or execution safety. The promoted-token feed is not a representative sample of the market. The market score is NOT a security certification. RugCheck may miss fraud or have stale data. Real positions are not fully valued in the app. No independently verified wallet drawdown monitor, onchain transaction instruction verification, mobile notifications, or production key management exists yet. Paper valuation may be stale when a held token is absent from the active feed. The app does not conduct background scans on the phone. For production use: formal security audit, end-to-end test transactions, more robust monitoring, externally managed signer, and safe deployment are necessary.

If an execution response is UNKNOWN, do not retry blindly; reconcile the wallet and transaction on Solana first.

Docs:
- https://developers.jup.ag/docs/swap
- https://docs.dexscreener.com/api/reference
- https://docs.x.ai/developers
- https://api.rugcheck.xyz/swagger/index.html
