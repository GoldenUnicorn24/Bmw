# MEMEPULSE AI v0.3 — Android + AI Solana Trading (Beta)

**Status:** installable Android debug build via GitHub Actions; source is security-unverified. This software can lose the entire amount allocated to the trading wallet, especially on illiquid meme coins. An LLM signal is not a performance guarantee.

The project lives exclusively under `memepulse/` on branch `feature/memepulse-ai-android`. Your Executive Black BMW app under `app/` and your BMW `main` branch are not replaced.

## Included

- Native Android Kotlin/Jetpack Compose dashboard with redesigned AI command center, DEX token radar, risk overview, wallet balances and trades activity.
- Paper trading: 1% maximum buy size, three-position limit, simulated fills with approximate 0.8% per-side fees, local P&L and visual performance graph.
- Optional **rule-based paper automation** (not Grok), using fresh observed DEX Screener prices, automatic simulated entries and stop-loss / take-profit exits.
- Real DEX token data from DEX Screener. **The selected feed includes sponsored/promoted tokens** and is not a representative ranking.
- Server-side Grok 4.7 signals, RugCheck risk screening and user-approved mint allowlist.
- Real Solana Jupiter Swap API v2 order and execution, with a dedicated backend signer and two-step approval in Android.
- Optional **Grok autonomous real BUYs**: must be actively enabled by both the server operator and the Android user; lasts up to 30 minutes; up to 2 tracked positions by default; only user-approved mints.
- Auto EXIT for Grok-owned positions when market mark is about **-12% or +25% vs entry** (not guaranteed executed price; only checked periodically; if liquidity collapses, sell may fail).
- Dedicated daily max buy spend, per-order max, order count, approximate realized SOL loss halt, 24-hour same-token cooldown, STOP file and activity log.
- Native on-device review and approval of individual tokens from the live radar; high RugCheck risk blocks the allowlist.
- Search any Solana token by full mint address, even when absent from the promoted feed.
- Server API journal retains successful, failed and unknown swaps; unresolved unknown transactions stop further automatic trading.
- HTTPS Caddy / isolated Docker Compose example for self-hosted service.
- GitHub Actions APK artifact, Node JavaScript syntax checks and regression tests.

## 1. Android installation

GitHub -> Actions -> **MemePulse AI - Android APK** -> latest **successful** run -> **Artifacts** -> `MemePulseAI-Android-v0.3-debug-APK`.

Unzip the downloaded artifact. The APK installs alongside Executive Black because it has its own package name `de.memepulse.ai`. Enable installation from the browser or file manager when Android asks.

The app starts in **PAPER** mode. In **Radar**, tap "Live-Kurse abrufen". Real DEX prices are separate from demo data. Under System activate the **rule-based Paper Bot** to run simulated entries/exits while the app is open. No server, account or wallet is required for this simulated mode.

## 2. Deploy the optional real-trading server

The Android app is **not** a private-key wallet and cannot autonomously trade from the phone. Autonomous swaps execute on your own always-on Node server. You need a VPS you control, DNS name, HTTPS and your own API keys.

Minimum: Docker + Compose, a DNS name pointing at your VPS, ports 80/443 accessible.

On the server:

```bash
cd memepulse
cp .env.example .env                    # compose domain, edit DNS name
cp backend/.env.example backend/.env    # server configuration and secrets
openssl rand -hex 32                   # paste OUTPUT into backend/.env API_BEARER_TOKEN
mkdir -p backend/private
sudo chown 10001:10001 backend/private
chmod 700 backend/private
chmod 600 backend/.env
docker compose up -d --build
```

Set `MEMEPULSE_DOMAIN` in `memepulse/.env`. Caddy obtains a TLS certificate for the configured public domain. Caddy reverse proxies only API traffic; the server itself is private on the Compose network.

**Never paste mnemonic phrases or private keys into ChatGPT, Claude, public GitHub, mobile UI fields or videos.** The app only requests an HTTPS endpoint and long randomly generated API bearer token.

A dedicated low-balance **hot wallet** is required for live swaps; do not use a main holdings wallet. The server wallet private key is stored as a secret in the server's `backend/.env`, which is not in Git and must be protected. Managed secrets / HSM signing would be preferable for serious use.

Required live config in `backend/.env`:

```
JUPITER_API_KEY=obtain_from_jupiter_developer_portal
SOLANA_RPC_URL=https://your-reliable-https-solana-rpc
SOLANA_PRIVATE_KEY_B58=ONLY_A_SEPARATELY_CREATED_TEST_WALLET_KEY
ENABLE_LIVE_TRADES=YES_I_ACCEPT_REAL_LOSSES
# Grok AI
XAI_API_KEY=obtain_from_xai_console
XAI_MODEL=grok-4.7
# Optional 30-min autonomous execution:
ALLOW_AUTONOMOUS_LIVE=YES_I_ACCEPT_AUTONOMOUS_LOSSES
```

**Do not enable live trades until your own end-to-end test, code review and independent key-handling review have been completed.** Test with an expendable balance and validate quote, fee, returned transaction and actual wallet results. No code here has been deployed or test-swapped with real SOL by ChatGPT.

Restart after configuration:

```bash
docker compose up -d --build
docker compose logs -f api
```

In Android -> **System** paste `https://yourdomain.example` and your Bearer token -> tap **Verbindung prüfen**. Once connected, the AI command center shows server, wallet, position and safety status.

## 3. AI controls

1. In **Radar**, load live markets, tap "RugCheck + für KI-Freigabe prüfen" next to a token.
2. The dialog displays the full token mint, liquidity, score and whether it passed the conservative checks. **RugCheck normalized score ranges 0–100; lower means fewer detected risks.** Values above 30 and dangerous risk flags block auto-buy.
3. **Manually approve** a token only if you understand it. The server persists the allowlist and can re-check its conditions before a purchase. Maximum eight approved tokens.
4. In **System** or dashboard, separately activate 30-minute autonomous trading. The server also must allow it.
5. The agent scans approved tokens. Grok may recommend BUY or HOLD; strict deterministic limits can reject any BUY. **The model is not guaranteed accurate and has no trading track record.**
6. During an active session, the server checks documented Grok-owned positions periodically and tries to sell when the configured target or stop-loss mark is crossed.
7. Stop new autonomous trades via the **KI STOP** button. For a persisted and broader **EMERGENCY STOP** blocking all new manual and autonomous live swaps, go to **System -> Sicherheitszentrale -> NOT-AUS**; the pause survives server restarts. Unpause only after reviewing the account and acknowledging the confirmation. In an emergency you can also stop the backend container with `docker compose stop api` (but then even the mobile stop button cannot connect). A submitted onchain transaction cannot be reversed.

### Safety budgets (defaults)

- `MAX_TRADE_SOL=0.01`
- `MAX_DAILY_SPEND_SOL=0.05`
- `MAX_DAILY_REALIZED_LOSS_SOL=0.015`
- `MAX_OPEN_POSITIONS=2`
- `MAX_ORDERS_PER_DAY=3`
- `STOP_LOSS_PCT=12`
- `TAKE_PROFIT_PCT=25`
- `MAX_PRICE_IMPACT_PCT=2`
- `MAX_SLIPPAGE_BPS=100`

Daily budgets are based on UTC and recorded order data. A daily realized loss cap is **not a portfolio drawdown cap**. Stop loss on an illiquid coin can slip or fail. Make independent decisions about possible total losses.

## 4. Testing and building

Backend locally (Node 22+):

```bash
cd memepulse/backend
cp .env.example .env
npm install
npm test
node --check server.mjs
npm start
```

Android (JDK 17, Android SDK 35, Gradle 8.12):

```bash
cd memepulse/android
gradle :app:assembleDebug
```

## API (Bearer auth except /health)

| Method | Path | Function |
| --- | --- | --- |
| GET | /health | Server basic health |
| GET | /api/status | Trading enablement and safe limits |
| GET | /api/market | Promoted market feed |
| GET | /api/portfolio | Tracked live coin ledger and estimated prices |
| GET | /api/wallet | Trading SOL balance |
| GET | /api/trades | Execution ledger |
| GET | /api/agent | Current AI state, recent signals and decisions |
| POST | /api/allowlist/check | RugCheck and liquidity preflight |
| POST | /api/allowlist/add | Explicitly approve token mint |
| POST | /api/allowlist/remove | Remove token mint |
| POST | /api/ai/signal | Ask Grok to analyze checked token |
| POST | /api/orders/prepare | Short-lived Jupiter quote |
| POST | /api/orders/execute | User-confirmed live swap |
| POST | /api/agent/start | 30-minute autonomous mode |
| POST | /api/agent/stop | Stop new autonomous trades |
| POST | /api/emergency/stop | Persistently block all new real swaps |
| POST | /api/emergency/resume | Explicitly permit manual real swaps again (AI stays off) |

## Unresolved production gaps

This is a development beta, not an audited trading platform. Price impact, token risk, stop-loss timing, slippage and quote execution can all diverge. It has no guaranteed exit route, automatic blacklisting of malicious wallets, centralized reconciliation for unclear transactions, automated performance backtesting, price history validation, independent end-to-end Jupiter signer verification, push notifications, institutional key management, or production observability. The JWT-less API bearer token is single-user and must only travel over HTTPS with an unpredictable long token and network hardening. The real position ledger only includes successful swaps executed by this backend and cannot account for external transfers. Test any real trade flow independently before entrusting funds.

See the official documentation:
- https://developers.jup.ag/docs/swap/order-and-execute
- https://docs.x.ai/developers/rest-api-reference/inference/responses
- https://docs.dexscreener.com/api/reference
- https://api.rugcheck.xyz/swagger/index.html
