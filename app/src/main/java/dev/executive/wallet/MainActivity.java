package dev.executive.wallet;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.WindowManager;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
public class MainActivity extends Activity {
  WebView web;
  @Override public void onCreate(Bundle b) {super.onCreate(b);
    getWindow().setStatusBarColor(Color.rgb(5,6,8));
    getWindow().setNavigationBarColor(Color.rgb(5,6,8));
    web=new WebView(this); WebSettings s=web.getSettings();
    s.setJavaScriptEnabled(true);s.setAllowFileAccess(true);s.setAllowContentAccess(false);
    s.setAllowUniversalAccessFromFileURLs(false);s.setAllowFileAccessFromFileURLs(false);
    web.setWebViewClient(new WebViewClient(){ @Override public boolean shouldOverrideUrlLoading(WebView v,android.webkit.WebResourceRequest r){return !r.getUrl().toString().startsWith("file:///android_asset/");}});
    web.setWebChromeClient(new WebChromeClient());
    web.addJavascriptInterface(new Bridge(),"NativeWallet");
    setContentView(web);web.loadUrl("file:///android_asset/index.html");
  }
  @Override protected void onResume(){super.onResume(); if(web!=null)web.post(()->web.evaluateJavascript("status()",null));}
  class Bridge {
    @JavascriptInterface public String state(){JSONObject o=new JSONObject();try{NfcAdapter a=NfcAdapter.getDefaultAdapter(MainActivity.this);o.put("supported",a!=null);o.put("enabled",a!=null&&a.isEnabled());KeyguardManager km=(KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);o.put("secure",km!=null&&km.isDeviceSecure());}catch(Exception e){}return o.toString();}
    @JavascriptInterface public void setPresentation(boolean on){runOnUiThread(()->{if(on){getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}else{getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(0);}});}
    @JavascriptInterface public void openWallet(){runOnUiThread(()->{String pkg="com.google.android.apps.walletnfcrel";Intent i=getPackageManager().getLaunchIntentForPackage(pkg);try{if(i!=null)startActivity(i);else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+pkg)));}catch(Exception e){}});}
    @JavascriptInterface public void openPaymentSettings(){runOnUiThread(()->{try{startActivity(new Intent("android.settings.NFC_PAYMENT_SETTINGS"));}catch(Exception e){try{startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));}catch(Exception ignored){}}});}
  }
  @Override protected void onDestroy(){if(web!=null){web.destroy();web=null;}super.onDestroy();}
}