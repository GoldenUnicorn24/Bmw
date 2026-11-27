# Bitcoin Lottery 0.2
Native Android experimental Bitcoin SHA-256d solo miner. No cloud hashing, fake balances, keys or payments. It connects to a user-selected Stratum V1 pool, constructs real headers, searches nonces and submits valid shares/block candidates. Default endpoint: stratum.ckpool.org:3333. Use your own public BTC receiving address; never enter a seed or private key. Solo CKPool may not report or may disconnect extremely low-hashrate workers. No payout unless a valid block is found and accepted. Verify pool fee, payout terms and blockchain independently. Plain TCP is not authenticated/encrypted; use trusted networks only.

Manual opt-in, reusable hash buffers and cached best target, adaptive thread throttling from 39°C or light thermal status, 1 CPU thread by default, 7 max (limited to Android-reported available processors). Stops when app is no longer visible, battery temperature >=42°C or Android thermal status moderate or higher. These controls do not guarantee battery safety; stop if hot. No wake lock or background service. Not intended for profit.

## Build
GitHub Actions workflow Bitcoin Lottery Android runs Gradle unit tests (including Bitcoin genesis block test), builds a debug APK and publishes it as an artifact if successful. Android Studio: open this branch as a standalone Gradle project, JDK 17, Android SDK 35, Gradle 8.9. This branch is intentionally isolated from the existing Bmw trading project; main is unchanged.

## Limitations
Experimental source; live-pool acceptance and on-device performance not yet verified. Auto-reconnect with exponential backoff; no encrypted Stratum, independent block-template verification, GPU or ASIC support. The pool must be trusted to pay your address. Hashrate and probability are estimates from actual local work, not a balance.
