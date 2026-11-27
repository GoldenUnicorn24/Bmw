package com.stark.bitcoinlottery;
import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public class MainActivity extends Activity {
 LinearLayout layout;EditText wallet,host,port;Spinner cores;TextView status,rate,total,best,shares,temp,odds,performance;
 Button start,stop;Miner miner;long lastHash,lastTime;double smoothRate;boolean throttled;final Handler ui=new Handler(Looper.getMainLooper());
 static final int BG=0xff10151e, FG=0xffedf2fa, ACC=0xffffad35;
 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 TextView label(String t,int size,int color){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));layout.addView(v);return v;}
 EditText input(String hint,String initial){EditText e=new EditText(this);e.setSingleLine();e.setTextColor(FG);e.setHintTextColor(0xff9caabf);e.setHint(hint);e.setText(initial);layout.addView(e);return e;}
 @Override public void onCreate(Bundle b){super.onCreate(b);
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
  ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(dp(20),dp(34),dp(20),dp(24));scroll.addView(layout);setContentView(scroll);
  TextView h=label("₿  BITCOIN LOTTERY",28,ACC);h.setTypeface(null,Typeface.BOLD);
  label("SOLO MINING  •  SHA-256d  •  v0.2",13,0xffaebed4);
  label("Echte Hashes, kein simuliertes Guthaben. Ein Blockfund ist extrem unwahrscheinlich. Nur bei geöffneter App.",15,FG);
  wallet=input("Deine öffentliche BTC-Empfangsadresse",getPreferences(0).getString("wallet",""));wallet.setInputType(1|128);
  host=input("Solo-Stratum-Host","stratum.ckpool.org");port=input("Port","3333");port.setInputType(2);
  label("CPU-Threads (mehr = mehr Wärme)",14,FG);
  cores=new Spinner(this);int max=Math.max(1,Math.min(7,Runtime.getRuntime().availableProcessors()));String[] values=new String[max];for(int i=0;i<max;i++)values[i]=(i+1)+(i==0?" (sparsam)":i==max-1?" (Maximum)":"");ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);cores.setAdapter(adapter);layout.addView(cores);
  start=new Button(this);start.setText("Mining starten");layout.addView(start);
  stop=new Button(this);stop.setText("Mining stoppen");stop.setEnabled(false);layout.addView(stop);
  status=label("Bereit",16,ACC);rate=label("0 H/s",32,FG);rate.setTypeface(null,Typeface.BOLD);
  total=label("Hashes: 0",16,FG);best=label("Bester Hash: —",14,FG);shares=label("Shares: 0 angenommen / 0 abgelehnt",14,FG);
  performance=label("CPU-Threads: —",14,FG);temp=label("Akkutemperatur: —",14,FG);odds=label("Chance in 24 h: —",14,FG);
  label("Nur öffentliche Adresse eingeben. Keine Seed-Phrase, kein privater Schlüssel. Stratum V1 TCP ist unverschlüsselt. Pool muss Solo-Auszahlungen unterstützen. Keine Kauf- oder Einzahlungsfunktion.",13,0xffaebed4);
  start.setOnClickListener(v->confirmStart());stop.setOnClickListener(v->halt("Manuell gestoppt"));ui.post(tick);
 }
 void confirmStart(){
  String w=wallet.getText().toString().trim(),h=host.getText().toString().trim();int p;
  if(!w.matches("(?i)(bc1[ac-hj-np-z02-9]{11,87}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})")||h.isEmpty()||!h.matches("[a-zA-Z0-9.:-]+")){status.setText("Öffentliche BTC-Adresse / Host prüfen.");return;}
  try{p=Integer.parseInt(port.getText().toString());if(p<1||p>65535)throw new Exception();}catch(Exception e){status.setText("Ungültiger Port");return;}
  if(batteryTemp()<0||batteryTemp()>=42||hot()){status.setText("Temperatur nicht sicher messbar oder zu hoch.");return;}
  new AlertDialog.Builder(this).setTitle("Echtes Solo-Mining starten?").setMessage("Kein garantierter Gewinn. CPU-Last, Stromverbrauch und Akkuverschleiß. Nur auf deinem Gerät. Pool kann extrem langsame Miner trennen.").setNegativeButton("Abbrechen",null).setPositiveButton("Start",(d,i)->begin(w,h,p)).show();
 }
 void begin(String w,String h,int p){
  if(miner!=null&&miner.running)return;
  getPreferences(0).edit().putString("wallet",w).apply();
  lastHash=0;lastTime=SystemClock.elapsedRealtime();smoothRate=0;throttled=false;cores.setEnabled(false);wallet.setEnabled(false);host.setEnabled(false);port.setEnabled(false);
  miner=new Miner(h,p,w+".phone",cores.getSelectedItemPosition()+1,s->ui.post(()->status.setText(s)));
  miner.start();start.setEnabled(false);stop.setEnabled(true);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 }
 float batteryTemp(){Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(i==null||!i.hasExtra(BatteryManager.EXTRA_TEMPERATURE))return -1;return i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-10)/10f;}
 int thermal(){PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);return Build.VERSION.SDK_INT>=29?pm.getCurrentThermalStatus():0;}
 boolean hot(){return thermal()>=PowerManager.THERMAL_STATUS_MODERATE;}
 void unlockControls(){cores.setEnabled(true);wallet.setEnabled(true);host.setEnabled(true);port.setEnabled(true);}
 void halt(String why){if(miner!=null)miner.stop();status.setText(why);start.setEnabled(true);stop.setEnabled(false);unlockControls();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
 final Runnable tick=new Runnable(){public void run(){
  float t=batteryTemp();temp.setText(String.format(Locale.GERMANY,"Akkutemperatur: %s",t<0?"unbekannt":String.format(Locale.GERMANY,"%.1f °C",t)));
  Miner m=miner;if(m!=null){
   if(m.running&&(t<0||t>=42||hot()))halt("Sicherheitsstopp: Temperatur / Thermallast.");
   if(m.running){
    if(t>=39||thermal()>=1)throttled=true;
    else if(t<=37&&thermal()==0)throttled=false;
    m.activeThreads=throttled?Math.max(1,m.threads/2):m.threads;
   }
   performance.setText("CPU-Threads: "+(m.running?m.activeThreads:0)+"/"+m.threads+(m.running&&throttled?" · Kühlmodus":"")+" · Neuverbindungen: "+m.reconnects.get());
   long now=SystemClock.elapsedRealtime(),n=m.hashes.get();double instantaneous=(n-lastHash)*1000.0/Math.max(1,now-lastTime);lastHash=n;lastTime=now;
   smoothRate=m.running&&m.authorized&&m.job.get()!=null?(smoothRate==0?instantaneous:0.25*instantaneous+0.75*smoothRate):0;double r=smoothRate;
   rate.setText(String.format(Locale.GERMANY,"%,.0f H/s",m.running?r:0));total.setText(String.format(Locale.GERMANY,"Hashes: %,d",n));
   best.setText(n==0?"Bester Hash: —":"Bester Hash: "+String.format(Locale.ROOT,"%064x",m.best.get()));
   shares.setText("Shares: "+m.accepted.get()+" angenommen / "+m.rejected.get()+" abgelehnt");
   if(m.running&&m.networkTarget!=null&&r>0){double one=(m.networkTarget.doubleValue()+1)/Math.scalb(1.0,256);double p=-Math.expm1(r*86400*Math.log1p(-one));odds.setText(String.format(Locale.GERMANY,"Geschätzte Chance / 24 h: %.3e %%",p*100));}else odds.setText("Chance in 24 h: —");
   if(!m.running){start.setEnabled(true);stop.setEnabled(false);unlockControls();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
  }
  ui.postDelayed(this,1000);
 }};
 @Override protected void onStop(){super.onStop();if(miner!=null&&miner.running)halt("Gestoppt: App verlassen.");}
 @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);if(miner!=null)miner.stop();super.onDestroy();}
}
