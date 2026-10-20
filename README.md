# BMW Executive Black v0.8 – Google Wallet Fenstertest

Private BMW-inspired companion app with native Android Google Wallet launch and experimental **adjacent-window test**.

## Experiment
- The beta button **GOOGLE WALLET DANEBEN TESTEN** starts the **official installed Google Wallet app**, preferring its `QuickAccessWalletService.ACTION_VIEW_WALLET` activity when available; otherwise it uses the normal Google Wallet launcher.
- The Android system receives `FLAG_ACTIVITY_LAUNCH_ADJACENT | FLAG_ACTIVITY_NEW_TASK` and may show the two apps in split-screen. OEM policies, protected wallet UI, device posture and software versions can cause the request to be ignored, resulting in a normal full-screen Google Wallet launch.
- The Executive Black activity supports multi-window resizing; no root or modifications to Google Wallet are needed.
- Original BMW card artwork stays unchanged. Fullscreen mode on initial launch. Optional Samsung Quick Settings **Executive Card** tile remains available.
- Google Wallet remains Android's default tap-to-pay wallet; this app never stores payment cards, payment tokens or card credentials, intercepts payments or claims success.
- Our NFC HCE service remains disabled by default and is strictly an independent two-phone test.

## Limits
This is **not** an SDK integration, Google Wallet UI embedded in a WebView, or independent Mastercard payment. Cross-application *activity embedding* requires the target app's explicit opt-in, and Google Wallet has not been confirmed to permit it. **Split-screen is only a best-effort system window request**. Samsung can show Google Wallet over Executive Black on a real tap; there is no supported API for disabling the Google system/payment overlay.

## Testing
1. Keep Google Wallet with Revolut Mastercard activated as the device's default NFC payment app.
2. Install APK and open Executive Black. The card display appears full-screen.
3. Tap **GOOGLE WALLET DANEBEN TESTEN**. If Samsung respects the request, both apps are shown together; otherwise Google Wallet opens normally.
4. If unavailable, test Samsung's Recent Apps → app icon → **Open in split screen view** and choose the other app.
5. Leave NFC LAB test service off for any real card payment. Google Wallet / Revolut alone can confirm whether a payment was processed.
