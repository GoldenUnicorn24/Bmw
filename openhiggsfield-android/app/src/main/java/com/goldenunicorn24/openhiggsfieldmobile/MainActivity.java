package com.goldenunicorn24.openhiggsfieldmobile;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String URL = "https://www.openhiggsfield.ai/";
    private final int BACKGROUND = Color.rgb(16,17,17);
    private final int LIME = Color.rgb(209,254,23);
    private final int WHITE = Color.rgb(240,242,237);
    private final int MUTED = Color.rgb(164,168,160);
    private static final int PICK = 9321;
    private WebView web;
    private ProgressBar progress;
    private LinearLayout error;
    private ValueCallback<Uri[]> pending;
    private boolean pageFailed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        LinearLayout base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);
        base.setBackgroundColor(BACKGROUND);
        setContentView(base);
        if(Build.VERSION.SDK_INT>=30) base.setOnApplyWindowInsetsListener((v,i)->{
            android.graphics.Insets s=i.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0,s.top,0,s.bottom);
            return i;
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.rgb(24,26,25));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14),0,dp(8),0);
        base.addView(bar,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView logo = new TextView(this);
        logo.setText("H");
        logo.setTextSize(22);
        logo.setTextColor(BACKGROUND);
        logo.setTypeface(null,Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(bg(LIME));
        bar.addView(logo,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout captions=new LinearLayout(this);
        captions.setOrientation(LinearLayout.VERTICAL);
        captions.setPadding(dp(10),0,0,0);
        bar.addView(captions,new LinearLayout.LayoutParams(0,-2,1f));
        TextView name=new TextView(this);
        name.setText("OpenHiggsfield");
        name.setTextColor(WHITE);
        name.setTypeface(null,Typeface.BOLD);
        name.setTextSize(15);
        captions.addView(name);
        TextView sub=new TextView(this);
        sub.setText("Mobile Studio · inoffiziell");
        sub.setTextColor(MUTED);
        sub.setTextSize(10);
        captions.addView(sub);
        TextView refresh=nav("↻","Neu laden");
        refresh.setOnClickListener(v->reload());
        bar.addView(refresh,new LinearLayout.LayoutParams(dp(43),dp(43)));
        TextView browser=nav("↗","Im Browser öffnen");
        browser.setOnClickListener(v->external(Uri.parse(URL)));
        bar.addView(browser,new LinearLayout.LayoutParams(dp(43),dp(43)));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(LIME));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BACKGROUND));
        progress.setVisibility(View.GONE);
        base.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        FrameLayout frame=new FrameLayout(this);
        base.addView(frame,new LinearLayout.LayoutParams(-1,0,1f));
        web=new WebView(this);
        web.setBackgroundColor(BACKGROUND);
        frame.addView(web,new FrameLayout.LayoutParams(-1,-1));
        error=createError();
        frame.addView(error,new FrameLayout.LayoutParams(-1,-1));
        error.setVisibility(View.GONE);
        initWeb();
        if(saved!=null) web.restoreState(saved); else web.loadUrl(URL);
    }
    private TextView nav(String glyph,String desc){
        TextView t=new TextView(this);
        t.setText(glyph); t.setTextSize(27); t.setTextColor(WHITE);
        t.setGravity(Gravity.CENTER); t.setContentDescription(desc);
        t.setClickable(true); t.setFocusable(true);
        return t;
    }
    private GradientDrawable bg(int color){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(10)); return d;
    }
    private int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private boolean trusted(String host){
        if(host==null)return false;
        String h=host.toLowerCase(Locale.ROOT);
        return h.equals("www.openhiggsfield.ai")||h.equals("openhiggsfield.ai");
    }
    private void initWeb(){
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if(Build.VERSION.SDK_INT>=26)s.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                if(!req.isForMainFrame())return false;
                Uri u=req.getUrl(); String scheme=u.getScheme();
                if("https".equalsIgnoreCase(scheme)&&trusted(u.getHost()))return false;
                if("about".equalsIgnoreCase(scheme)||"blob".equalsIgnoreCase(scheme))return false;
                if("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)
                   ||"tel".equalsIgnoreCase(scheme)||"mailto".equalsIgnoreCase(scheme))external(u);
                return true;
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){
                pageFailed=false;error.setVisibility(View.GONE);
            }
            @Override public void onPageFinished(WebView v,String url){
                CookieManager.getInstance().flush();
                if(!pageFailed)error.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest req,WebResourceError e){
                if(req.isForMainFrame())fail();
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest req,WebResourceResponse res){
                if(req.isForMainFrame()&&res.getStatusCode()>=400)fail();
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int n){
                progress.setProgress(n);
                progress.setVisibility(n>=100?View.GONE:View.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> cb,FileChooserParams p){
                if(pending!=null)pending.onReceiveValue(null);
                pending=cb;
                try{
                    Intent i=new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    ArrayList<String> types=new ArrayList<>();
                    for(String accept:p.getAcceptTypes())if(accept!=null)
                        for(String value:accept.split(","))if(value.trim().contains("/"))types.add(value.trim());
                    if(!types.isEmpty())i.putExtra(Intent.EXTRA_MIME_TYPES,types.toArray(new String[0]));
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,p.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(i,"Medien auswählen"),PICK);
                    return true;
                }catch(Exception e){pending=null;cb.onReceiveValue(null);return false;}
            }
        });
        web.setDownloadListener((url,ua,disposition,mime,length)->{
            try{
                Uri uri=Uri.parse(url);
                if(!"https".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("HTTPS only");
                String file=URLUtil.guessFileName(url,disposition,mime);
                file=file.replaceAll("[\\\\/:*?\"<>|]","_");
                DownloadManager.Request req=new DownloadManager.Request(uri);
                req.setTitle(file);
                req.setDescription("OpenHiggsfield Mobile");
                if(mime!=null&&!mime.isEmpty())req.setMimeType(mime);
                if(ua!=null)req.addRequestHeader("User-Agent",ua);
                String cookies=CookieManager.getInstance().getCookie(url);
                if(cookies!=null&&!cookies.isEmpty())req.addRequestHeader("Cookie",cookies);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,file);
                ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this,"Download gestartet",Toast.LENGTH_SHORT).show();
            }catch(Exception e){Toast.makeText(this,"Download fehlgeschlagen",Toast.LENGTH_LONG).show();}
        });
    }
    private void fail(){pageFailed=true;progress.setVisibility(View.GONE);error.setVisibility(View.VISIBLE);}
    private void reload(){pageFailed=false;error.setVisibility(View.GONE);if(web.getUrl()==null)web.loadUrl(URL);else web.reload();}
    private LinearLayout createError(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BACKGROUND);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(25),dp(25),dp(25),dp(25));
        TextView heading=new TextView(this);
        heading.setText("Studio nicht erreichbar");
        heading.setTextColor(WHITE);
        heading.setTextSize(20);
        heading.setTypeface(null,Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading);
        TextView details=new TextView(this);
        details.setText("Prüfe deine Internetverbindung und versuche es erneut.");
        details.setTextColor(MUTED);
        details.setTextSize(14);
        details.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(12),0,dp(20));
        box.addView(details,lp);
        Button retry=new Button(this);
        retry.setText("Neu laden");
        retry.setAllCaps(false);
        retry.setTextColor(BACKGROUND);
        retry.setBackground(bg(LIME));
        retry.setOnClickListener(v->reload());
        box.addView(retry,new LinearLayout.LayoutParams(dp(200),dp(50)));
        Button ext=new Button(this);
        ext.setText("Im Browser öffnen");
        ext.setTextColor(WHITE);
        ext.setAllCaps(false);
        ext.setBackground(bg(Color.rgb(24,26,25)));
        LinearLayout.LayoutParams elp=new LinearLayout.LayoutParams(dp(200),dp(50));
        elp.topMargin=dp(10);
        box.addView(ext,elp);
        ext.setOnClickListener(v->external(Uri.parse(URL)));
        return box;
    }
    private void external(Uri uri){
        try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}
        catch(Exception ex){Toast.makeText(this,"Kein Browser verfügbar",Toast.LENGTH_SHORT).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK||pending==null)return;
        Uri[] results=null;
        if(result==RESULT_OK&&data!=null){
            ClipData clip=data.getClipData();
            if(clip!=null&&clip.getItemCount()>0){
                results=new Uri[clip.getItemCount()];
                for(int i=0;i<clip.getItemCount();i++)results[i]=clip.getItemAt(i).getUri();
            }else if(data.getData()!=null)results=new Uri[]{data.getData()};
        }
        pending.onReceiveValue(results);
        pending=null;
    }
    @Override protected void onSaveInstanceState(Bundle out){web.saveState(out);super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){
        if(error.getVisibility()==View.VISIBLE)reload();
        else if(web.canGoBack())web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onPause(){super.onPause();web.onPause();}
    @Override protected void onResume(){super.onResume();web.onResume();}
    @Override protected void onDestroy(){
        if(pending!=null)pending.onReceiveValue(null);
        pending=null;
        web.destroy();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
}
