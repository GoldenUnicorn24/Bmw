package com.stark.bitcoinlottery;
import org.json.*;
import java.net.*;
import java.io.*;
import java.math.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class Miner {
 interface Listener {void event(String s);}
 final AtomicLong hashes=new AtomicLong(), accepted=new AtomicLong(), rejected=new AtomicLong();
 final AtomicReference<BigInteger> best=new AtomicReference<>(Bitcoin.MAX);
 final AtomicInteger ids=new AtomicInteger(10);
 final AtomicReference<Job> job=new AtomicReference<>();
 final ConcurrentHashMap<Integer,Boolean> submissions=new ConcurrentHashMap<>();
 volatile boolean running, authorized;
 volatile Socket socket;
 volatile BufferedWriter writer;
 volatile BigInteger shareTarget=Bitcoin.DIFF1,networkTarget;
 final String user,host; final int port,threads; final Listener listener;
 final ExecutorService exec;
 volatile String extra1; volatile int extraSize;
 Miner(String host,int port,String user,int threads,Listener listener){this.host=host;this.port=port;this.user=user;this.threads=threads;this.listener=listener;exec=Executors.newFixedThreadPool(threads+1);}
 static JSONArray arr(Object... values){JSONArray a=new JSONArray();for(Object o:values)a.put(o);return a;}
 synchronized void send(int id,String method,JSONArray p)throws Exception{
  if(!running||writer==null)throw new IOException("not connected");
  JSONObject m=new JSONObject();m.put("id",id);m.put("method",method);m.put("params",p);
  writer.write(m.toString());writer.newLine();writer.flush();
 }
 void start(){running=true;exec.execute(this::connect);for(int i=0;i<threads;i++)exec.execute(this::mine);}
 void stop(){running=false;job.set(null);try{if(socket!=null)socket.close();}catch(IOException ignored){}exec.shutdownNow();}
 void connect(){
  try{
   listener.event("Verbinde mit "+host);
   Socket s=new Socket();socket=s;if(!running){s.close();return;}
   s.connect(new InetSocketAddress(host,port),12000);s.setSoTimeout(90000);s.setTcpNoDelay(true);
   writer=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
   BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
   send(1,"mining.subscribe",arr("BitcoinLottery/0.1"));
   String line;while(running&&(line=reader.readLine())!=null){if(line.length()>200000)throw new IOException("oversized message");onMessage(new JSONObject(line));}
   if(running)listener.event("Pool-Verbindung geschlossen. Neu starten.");
  }catch(Exception e){if(running)listener.event("Verbindung beendet: "+e.getMessage());}finally{stop();}
 }
 void onMessage(JSONObject m)throws Exception{
  String method=m.optString("method","");JSONArray p=m.optJSONArray("params");
  if(method.equals("mining.set_difficulty")){
   BigDecimal d=new BigDecimal(p.getString(0));if(d.signum()<=0)throw new IOException("difficulty");
   shareTarget=new BigDecimal(Bitcoin.DIFF1).divide(d,0,RoundingMode.DOWN).toBigInteger().min(Bitcoin.MAX);return;
  }
  if(method.equals("mining.notify")){
   if(extra1==null)return;
   Job j=new Job(p,extra1,extraSize,shareTarget);networkTarget=j.network;job.set(j);listener.event("Neuer Blockauftrag: "+j.id);return;
  }
  if(method.equals("mining.set_extranonce")){job.set(null);extra1=p.getString(0);extraSize=p.getInt(1);return;}
  if(method.equals("client.reconnect"))throw new IOException("Pool verlangt Neuverbindung");
  int id=m.optInt("id",-1);
  if(id==1){JSONArray r=m.getJSONArray("result");extra1=r.getString(1);extraSize=r.getInt(2);if(extraSize<1||extraSize>32)throw new IOException("extranonce length");send(2,"mining.authorize",arr(user,"x"));return;}
  if(id==2){if(!m.optBoolean("result",false))throw new IOException("Wallet vom Pool abgelehnt");authorized=true;listener.event("Autorisiert. Warte auf Arbeit / mine.");return;}
  Boolean block=submissions.remove(id);
  if(block!=null){if(m.optBoolean("result",false)){accepted.incrementAndGet();listener.event(block?"Block-Kandidat vom Pool angenommen! Blockchain separat prüfen.":"Share angenommen");}else{rejected.incrementAndGet();listener.event("Share abgelehnt: "+m.optString("error",""));}}
 }
 static final class Job {
  final String id,prev,cb1,cb2,version,bits,time,extra1;final int size;final JSONArray branches;
  final BigInteger network,share; final byte[] submitTarget,blockTarget;
  final AtomicLong counter=new AtomicLong(new SecureRandom().nextLong());
  Job(JSONArray p,String e,int size,BigInteger share)throws Exception{
   id=p.getString(0);prev=p.getString(1);cb1=p.getString(2);cb2=p.getString(3);branches=p.getJSONArray(4);version=p.getString(5);bits=p.getString(6);time=p.getString(7);extra1=e;this.size=size;
   if(size<1||size>32)throw new IOException("extranonce length");
   network=Bitcoin.target(bits);this.share=share;submitTarget=Bitcoin.targetBytes(share.max(network));blockTarget=Bitcoin.targetBytes(network);
  }
  String nextExtra(){long n=counter.getAndIncrement();byte[] b=new byte[size];for(int i=0;i<Math.min(8,size);i++)b[size-1-i]=(byte)(n>>>(8*i));return Bitcoin.hex(b);}
  byte[] makeHeader(MessageDigest md,String extra)throws Exception{
   byte[] root=Bitcoin.sha2(md,Bitcoin.hex(cb1+extra1+extra+cb2));
   for(int i=0;i<branches.length();i++)root=Bitcoin.sha2(md,Bitcoin.cat(root,Bitcoin.hex(branches.getString(i))));
   return Bitcoin.header(version,prev,root,time,bits);
  }
 }
 void mine(){
  try{
   MessageDigest md=MessageDigest.getInstance("SHA-256");Job current=null;String extra=null;byte[] header=null;long nonce=0;int pending=0;
   try{while(running){
    Job j=job.get();if(j==null||!authorized){Thread.sleep(100);continue;}
    if(j!=current||nonce>0xffffffffL){current=j;extra=j.nextExtra();header=j.makeHeader(md,extra);nonce=0;}
    Bitcoin.put32(header,76,nonce);
    byte[] digest=Bitcoin.sha2(md,header);pending++;
    if(pending>=256){hashes.addAndGet(pending);pending=0;}
    if(Bitcoin.cmpHash(digest,Bitcoin.targetBytes(best.get()))<0){
     BigInteger value=new BigInteger(1,Bitcoin.reverse(digest));best.accumulateAndGet(value,BigInteger::min);
    }
    if(Bitcoin.cmpHash(digest,j.submitTarget)<=0&&job.get()==j&&running){
     boolean block=Bitcoin.cmpHash(digest,j.blockTarget)<=0;
     int id=ids.getAndIncrement();submissions.put(id,block);
     send(id,"mining.submit",arr(user,j.id,extra,j.time,String.format(Locale.ROOT,"%08x",nonce)));
     if(block)listener.event("Block-Kandidat gesendet; warte auf Pool-Bestätigung.");
    }
    nonce++;
   }}finally{hashes.addAndGet(pending);}
  }catch(InterruptedException ignored){}catch(Exception e){if(running){listener.event("Mining-Fehler: "+e.getMessage());stop();}}
 }
}
