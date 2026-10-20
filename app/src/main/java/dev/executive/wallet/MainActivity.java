package dev.executive.wallet;

import android.app.Activity;
import android.app.role.RoleManager;
import android.os.Build;
import android.content.ComponentName;
import android.os.Bundle;
import android.graphics.Color;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Executive Black, user-interface and proprietary NFC LAB.
 * Genuine payments are exclusively handled by the system's default wallet.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
  WebView web;
  NfcAdapter adapter;
  volatile boolean readerMode = false;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(Color.rgb(5,6,8));
    getWindow().setNavigationBarColor(Color.rgb(5,6,8));
    adapter=NfcAdapter.getDefaultAdapter(this);
    web=new WebView(this);
    WebSettings s=web.getSettings();
    s.setJavaScriptEnabled(true);s.setAllowFileAccess(true);s.setAllowContentAccess(false);
    s.setAllowUniversalAccessFromFileURLs(false);
    s.setAllowFileAccessFromFileURLs(false);
    web.setWebViewClient(new WebViewClient(){
      @Override public void onPageFinished(WebView v,String url) {
        if (url!=null && url.startsWith("file:///android_asset/"))
          v.evaluateJavascript("stage(true);refreshPayment();", null);
      }
      @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r){
        return !r.getUrl().toString().startsWith("file:///android_asset/");
      }
    });
    web.setWebChromeClient(new WebChromeClient());
    web.addJavascriptInterface(new Bridge(),"NativeWallet");
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  @Override protected void onResume(){
    super.onResume();
    if(web!=null) web.post(()->web.evaluateJavascript("status();if(window.updateLab)updateLab();if(window.refreshPayment)refreshPayment();",null));
  }

  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);
    setIntent(intent);
    if(web!=null)web.post(()->web.evaluateJavascript("stage(true);refreshPayment();",null));
  }

  @Override protected void onPause(){
    if(readerMode && adapter!=null) {
      try{adapter.disableReaderMode(this);}catch(Exception ignored){}
      readerMode=false;
    }
    super.onPause();
  }

  private void setReader(boolean on) {
    if(adapter==null || !adapter.isEnabled()){readerMode=false;return;}
    try{
      if(on){
        adapter.enableReaderMode(this,this,
            NfcAdapter.FLAG_READER_NFC_A|NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null);
        readerMode=true;
      }else {
        adapter.disableReaderMode(this);
        readerMode=false;
      }
    }catch(Exception e){
      readerMode=false;
      getSharedPreferences("demo_hce",MODE_PRIVATE).edit()
          .putString("last_reader","Reader konnte nicht gestartet werden: "+e.getClass().getSimpleName()).apply();
    }
  }

  @Override public void onTagDiscovered(Tag tag) {
    String message;
    IsoDep iso = IsoDep.get(tag);
    if(iso==null) message="Kein ISO-DEP-Gerät erkannt. Zweites Android-Handy erforderlich.";
    else {
      try {
        iso.connect();iso.setTimeout(4000);
        byte[] aid=DemoCardService.AID;
        byte[] apdu=new byte[6+aid.length];
        apdu[0]=0; apdu[1]=(byte)0xA4; apdu[2]=0x04; apdu[3]=0; apdu[4]=(byte)aid.length;
        System.arraycopy(aid,0,apdu,5,aid.length);
        apdu[apdu.length-1]=0;
        byte[] response=iso.transceive(apdu);
        byte[] prefix="BMW-HCE-DEMO".getBytes(StandardCharsets.US_ASCII);
        boolean ok=response.length==prefix.length+2
          && Arrays.equals(Arrays.copyOf(response,prefix.length),prefix)
          && response[response.length-2]==(byte)0x90
          && response[response.length-1]==0;
        message=ok?"NFC-Test erfolgreich: zweites Smartphone antwortet. KEINE Bezahlung."
                 :"Keine BMW-Testkarte erkannt (anderer NFC-Tag oder keine Test-App).";
      }catch(Exception e){message="NFC-Test fehlgeschlagen: "+e.getClass().getSimpleName();}
      finally{try{iso.close();}catch(Exception ignored){}}
    }
    getSharedPreferences("demo_hce",MODE_PRIVATE).edit()
      .putString("last_reader",message)
      .putLong("last_reader_time",System.currentTimeMillis()).apply();
  }

  private boolean isLabEnabled(){
    PackageManager pm=getPackageManager();
    int mode=pm.getComponentEnabledSetting(new ComponentName(this,DemoCardService.class));
    // The demo component is disabled by default in the manifest.
    return mode==PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
  }

  class Bridge {
    @JavascriptInterface public String state(){
      JSONObject o=new JSONObject();
      try{
        NfcAdapter a=NfcAdapter.getDefaultAdapter(MainActivity.this);
        o.put("supported",a!=null);o.put("enabled",a!=null&&a.isEnabled());
        KeyguardManager km=(KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE);
        o.put("secure",km!=null&&km.isDeviceSecure());
      }catch(Exception ignored){}
      return o.toString();
    }

    /** The phone's actual payment wallet remains independent of this display app. */
    @JavascriptInterface public String paymentState(){
      JSONObject o=new JSONObject();
      try {
        o.put("nfcOn",adapter!=null && adapter.isEnabled());
        o.put("labEnabled",isLabEnabled());
        o.put("secureLock",((KeyguardManager)getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure());
        o.put("googleInstalled",getPackageManager().getLaunchIntentForPackage("com.google.android.apps.walletnfcrel")!=null);
        // Android exposes only whether *our own app* holds the Wallet role.
        // It does not provide a public API to identify another app's holder.
        boolean bmwHasWalletRole=false;
        if(Build.VERSION.SDK_INT>=35){
          RoleManager role=(RoleManager)getSystemService(Context.ROLE_SERVICE);
          if(role!=null && role.isRoleAvailable(RoleManager.ROLE_WALLET))
            bmwHasWalletRole=role.isRoleHeld(RoleManager.ROLE_WALLET);
        }
        o.put("bmwIsWalletDefault",bmwHasWalletRole);
        o.put("walletDefault","unknown");
      }catch(Exception ignored){}
      return o.toString();
    }

    @JavascriptInterface public void setLabEnabled(boolean enabled){
      runOnUiThread(()->{
        if(readerMode)setReader(false);
        try{
          getPackageManager().setComponentEnabledSetting(
            new ComponentName(MainActivity.this,DemoCardService.class),
            enabled?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
        }catch(Exception ignored){}
        if(web!=null)web.evaluateJavascript("refreshPayment();updateLab();drawDemo();",null);
      });
    }

    @JavascriptInterface public String diagnostics(){
      JSONObject o=new JSONObject();
      try{
        android.content.SharedPreferences p=getSharedPreferences("demo_hce",MODE_PRIVATE);
        o.put("supported",adapter!=null);
        o.put("nfcOn",adapter!=null&&adapter.isEnabled());
        o.put("hceSupported",getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION));
        o.put("readerMode",readerMode);
        o.put("labEnabled",isLabEnabled());
        o.put("lastCardTap",p.getLong("last_tap",0));
        o.put("tapCount",p.getInt("tap_count",0));
        o.put("lastReader",p.getString("last_reader","Noch kein Reader-Test"));
        o.put("lastReaderTime",p.getLong("last_reader_time",0));
        o.put("testAid",DemoCardService.AID_HEX);
      }catch(Exception ignored){}
      return o.toString();
    }

    @JavascriptInterface public boolean logicSelfTest(){
      byte[] aid=DemoCardService.AID;
      byte[] apdu=new byte[6+aid.length];
      apdu[0]=0;apdu[1]=(byte)0xA4;apdu[2]=4;apdu[3]=0;apdu[4]=(byte)aid.length;
      System.arraycopy(aid,0,apdu,5,aid.length);
      byte[] reply=DemoCardService.respond(apdu);
      return new String(reply,0,reply.length-2,StandardCharsets.US_ASCII).equals("BMW-HCE-DEMO")
        && reply[reply.length-2]==(byte)0x90 && reply[reply.length-1]==0;
    }

    @JavascriptInterface public void setReaderMode(boolean enabled){
      runOnUiThread(()->setReader(enabled));
    }

    @JavascriptInterface public void setPresentation(boolean on){
      runOnUiThread(()->{
        if(on){
          if(readerMode)setReader(false); // Reader mode blocks normal NFC card emulation.
          getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }else {
          getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          getWindow().getDecorView().setSystemUiVisibility(0);
        }
      });
    }

    /** Open the official Revolut app; card provisioning stays inside the issuer app. */
    @JavascriptInterface public void openRevolut(){
      runOnUiThread(()->{
        String pkg="com.revolut.revolut";
        Intent installed=getPackageManager().getLaunchIntentForPackage(pkg);
        try{
          if(installed!=null) startActivity(installed);
          else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id="+pkg)));
        }catch(Exception ignored){}
      });
    }
    @JavascriptInterface public void openWallet(){
      runOnUiThread(()->{
        String pkg="com.google.android.apps.walletnfcrel";
        Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        try{
          if(i!=null)startActivity(i);
          else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("market://details?id="+pkg)));
        }catch(Exception ignored){}
      });
    }

    @JavascriptInterface public void openPaymentSettings(){
      runOnUiThread(()->{
        try{startActivity(new Intent("android.settings.NFC_PAYMENT_SETTINGS"));}
        catch(Exception e){
          try{startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));}
          catch(Exception ignored){}
        }
      });
    }
  }

  @Override protected void onDestroy(){
    if(readerMode) setReader(false);
    if(web!=null){web.destroy();web=null;}
    super.onDestroy();
  }
}