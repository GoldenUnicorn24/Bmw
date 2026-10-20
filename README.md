# Executive Black v0.7 – Tap Companion (Samsung / Google Wallet)

Standalone BMW-inspired card presentation with a **genuine Android wallet-role check** (Android 15+, API 35), instant full-screen card on launch, and an optional Samsung Quick Settings tile **Executive Card**. Existing artwork `app/src/main/assets/card-front.jpg` unchanged.

**Important:** Executive Black is a presentation/companion app; **not** a payment wallet. Contactless payment remains fully handled by Google Wallet and the issuer. Android/Google may overlay payment authentication or card UI, and this app cannot suppress it. It does not receive the Mastercard token, verify a successful transaction, or call a Google payment SDK.

## What's new
- Opening the app immediately displays the Executive Black full-screen card. Close to access settings and NFC diagnostics.
- Integrated local check of the Android 15+ default wallet role: whether Google Wallet is selected, another holder, none or unknown.
- Does NOT automatically launch Google Wallet from the presentation view: Android's **default wallet** continues handling real payments via the OS.
- Optional **Executive Card** Quick Settings tile: edit the notification shade's Quick Settings tiles, add the tile, then tap to launch the card. Device must be unlocked when required.
- Demo HCE component is disabled by default to avoid accidental NFC-test mode during real payment. In NFC LAB, manually enable only for two-device testing, then disable again. The proprietary `F00102424D57` demo AID never imitates Mastercard.
- NFC Reader mode is stopped when presentation mode opens to avoid blocking phone card emulation.

## Test
1. Install this APK on an unlocked Android NFC phone; existing Google Wallet + Revolut card should remain installed and set as the default wallet.
2. Open Executive Black: card appears full screen automatically. Close this view to inspect the standard wallet diagnosis.
3. Optionally add **Executive Card** in the Samsung Quick Settings customization menu.
4. At a payment terminal the **real payment is via Google Wallet**; system UI may cover BMW Executive and normal authentication may be required. **Only Google Wallet or Revolut can confirm whether a payment succeeded.**
5. Use NFC LAB only with a second NFC-enabled phone, never as a payment test.

Never store card number, CVV, bank login, or payment tokens in this public repository or app. Independent wallet integration requires issuer and payment-network approval.
