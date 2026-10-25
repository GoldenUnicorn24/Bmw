package dev.executive.wallet;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quickaccesswallet.QuickAccessWalletService;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;

/**
 * Executive Black – shows the BMW card full screen while the phone's default
 * payment wallet (Google Wallet with the user's bank card) pays in the background.
 * This app never stores card data, never emulates a card and never sees a payment.
 */
public class MainActivity extends Activity {
  static final String GOOGLE_WALLET = "com.google.android.apps.walletnfcrel";
  static final String REVOLUT = "com.revolut.revolut";
  /** Known HCE payment components of Google Wallet (best effort, varies by version). */
  static final String[] GOOGLE_HCE = {
    "com.google.android.gms/com.google.android.gms.tapandpay.hce.service.TpHceService",
    "com.google.android.apps.walletnfcrel/com.google.android.gms.tapandpay.hce.service.TpHceService"
  };

  WebView web;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(Color.rgb(5,6,8));
    getWindow().setNavigationBarColor(Color.rgb(5,6,8));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    web = new WebView(this);
    web.setBackgroundColor(Color.rgb(5,6,8));
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(true);
    s.setAllowContentAccess(false);
    s.setAllowFileAccessFromFileURLs(false);
    s.setAllowUniversalAccessFromFileURLs(false);
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        return !r.getUrl().toString().startsWith("file:///android_asset/");
      }
    });
    web.addJavascriptInterface(new Bridge(), "Executive");
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  @Override protected void onResume() {
    super.onResume();
    immersive();
    if (web != null) web.post(() -> web.evaluateJavascript("window.refresh&&refresh()", null));
  }

  @Override public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (focus) immersive();
  }

  private void immersive() {
    if (isInMultiWindowMode()) { getWindow().getDecorView().setSystemUiVisibility(0); return; }
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
      | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  private boolean installed(String pkg) {
    try { getPackageManager().getPackageInfo(pkg, 0); return true; }
    catch (PackageManager.NameNotFoundException e) { return false; }
  }

  /** "yes" = Google Wallet confirmed as default, "no" = something else is, "unknown" = not readable. */
  private String googleDefault(NfcAdapter a) {
    if (a == null) return "unknown";
    try {
      CardEmulation ce = CardEmulation.getInstance(a);
      for (String c : GOOGLE_HCE) {
        ComponentName cn = ComponentName.unflattenFromString(c);
        if (cn != null && ce.isDefaultServiceForCategory(cn, CardEmulation.CATEGORY_PAYMENT)) return "yes";
      }
    } catch (Exception ignored) { }
    return "unknown";
  }

  private void start(Intent i, String fallbackPkg) {
    try { startActivity(i); }
    catch (Exception e) {
      if (fallbackPkg != null) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + fallbackPkg))); return; }
        catch (Exception ignored) { }
      }
      Toast.makeText(this, "Konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show();
    }
  }

  class Bridge {
    @JavascriptInterface public String state() {
      JSONObject o = new JSONObject();
      try {
        NfcAdapter a = NfcAdapter.getDefaultAdapter(MainActivity.this);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        o.put("nfcSupported", a != null);
        o.put("nfcOn", a != null && a.isEnabled());
        o.put("hce", getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION));
        o.put("secure", km != null && km.isDeviceSecure());
        o.put("wallet", installed(GOOGLE_WALLET));
        o.put("revolut", installed(REVOLUT));
        o.put("walletDefault", googleDefault(a));
        o.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
      } catch (Exception ignored) { }
      return o.toString();
    }

    @JavascriptInterface public void openWallet() {
      runOnUiThread(() -> {
        Intent i = new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET).setPackage(GOOGLE_WALLET);
        if (i.resolveActivity(getPackageManager()) == null) i = getPackageManager().getLaunchIntentForPackage(GOOGLE_WALLET);
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + GOOGLE_WALLET));
        start(i, GOOGLE_WALLET);
      });
    }

    @JavascriptInterface public void openRevolut() {
      runOnUiThread(() -> {
        Intent i = getPackageManager().getLaunchIntentForPackage(REVOLUT);
        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + REVOLUT));
        start(i, REVOLUT);
      });
    }

    @JavascriptInterface public void openNfcSettings() {
      runOnUiThread(() -> start(new Intent(Settings.ACTION_NFC_SETTINGS), null));
    }

    @JavascriptInterface public void openPaymentSettings() {
      runOnUiThread(() -> {
        try { startActivity(new Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS)); }
        catch (Exception e) { start(new Intent(Settings.ACTION_NFC_SETTINGS), null); }
      });
    }

    @JavascriptInterface public void openSecuritySettings() {
      runOnUiThread(() -> {
        try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
        catch (Exception e) { start(new Intent(Settings.ACTION_SETTINGS), null); }
      });
    }

    @JavascriptInterface public void haptic() {
      runOnUiThread(() -> { if (web != null) web.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); });
    }
  }

  @Override protected void onDestroy() {
    if (web != null) { web.destroy(); web = null; }
    super.onDestroy();
  }
}
