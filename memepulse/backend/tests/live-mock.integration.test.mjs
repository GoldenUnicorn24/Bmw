import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {Keypair,SystemProgram,ComputeBudgetProgram} from '@solana/web3.js';
import bs58 from 'bs58';

const token='INTEGRATION_TEST_ONLY_AUTH_TOKEN_0123456789';
const preload=fileURLToPath(new URL('./mock-live-network.mjs',import.meta.url));
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function launch({badSignature=false,programs}={}){
 const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'memepulse-fake-live-'));
 const port=17100+crypto.randomInt(1300);
 const wallet=Keypair.generate(),mint=Keypair.generate().publicKey.toBase58();
 const env={...process.env,HOST:'127.0.0.1',PORT:String(port),
   API_BEARER_TOKEN:token,SOLANA_RPC_URL:'https://rpc.synthetic.invalid',
   ENABLE_LIVE_TRADES:'YES_I_ACCEPT_REAL_LOSSES',ALLOW_AUTONOMOUS_LIVE:'NO',
   MEMEPULSE_MOCK_TEST_NETWORK:'CI_FAKE_ONLY',
   SOLANA_PRIVATE_KEY_B58:bs58.encode(wallet.secretKey),
   JUPITER_API_KEY:'FAKE_JUPITER_KEY_NO_LIVE_ACCESS',
   TRADING_ALLOWLIST:mint,MAX_TRADE_SOL:'.02',MAX_DAILY_SPEND_SOL:'.05',
   MAX_ORDERS_PER_DAY:'3',MIN_SOL_RESERVE:'.02',
   SWAP_ALLOWED_PROGRAM_IDS:programs===undefined?
     [SystemProgram.programId.toBase58(),ComputeBudgetProgram.programId.toBase58()].join(','):programs,
   MEMEPULSE_MOCK_BAD_SIG:badSignature?'YES':'NO',
   TRADE_LOG_PATH:path.join(tmp,'trades.json'),
   WITHDRAWAL_LOG_PATH:path.join(tmp,'withdrawals.json'),STOP_FILE:path.join(tmp,'STOP')};
 const proc=spawn(process.execPath,['--import',preload,'server.mjs'],{
   cwd:process.cwd(),env,stdio:['ignore','pipe','pipe']});
 let output='';
 proc.stdout.on('data',b=>{output+=String(b).slice(0,1500)});
 proc.stderr.on('data',b=>{output+=String(b).slice(0,1500)});
 const base='http://127.0.0.1:'+port;
 for(let i=0;i<55;i++){
   if(proc.exitCode!==null){proc.kill();fs.rmSync(tmp,{recursive:true,force:true});
     throw Error('Synthetic server exited: '+output.slice(-1200));}
   try{const status=await fetch(base+'/health');
     if(status.ok)return {base,proc,tmp,mint,output:()=>output};
   }catch{}
   await sleep(100);
 }
 proc.kill();fs.rmSync(tmp,{recursive:true,force:true});
 throw Error('Synthetic server startup timed out '+output);
}
async function call(base,uri,body){
 const resp=await fetch(base+uri,{method:body?'POST':'GET',
   headers:{authorization:'Bearer '+token,'content-type':'application/json'},
   ...(body?{body:JSON.stringify(body)}:{})});
 return {status:resp.status,body:await resp.json()};
}
async function clean(ctx){
 ctx.proc.kill();
 await new Promise(resolve=>{if(ctx.proc.exitCode!==null)return resolve();
   ctx.proc.once('exit',resolve);setTimeout(resolve,500).unref();});
 fs.rmSync(ctx.tmp,{recursive:true,force:true});
}
test('fully synthetic Jupiter v0 buy requires validated wallet signature and independent confirmation',async()=>{
 const ctx=await launch();
 try{
   const prepared=await call(ctx.base,'/api/orders/prepare',{
     side:'BUY',mint:ctx.mint,amountSol:.01});
   assert.equal(prepared.status,200,JSON.stringify(prepared.body));
   assert.ok(prepared.body.previewId);
   assert.equal(prepared.body.inputAtomic,'10000000');
   const executed=await call(ctx.base,'/api/orders/execute',{
     previewId:prepared.body.previewId,confirm:'LIVE TRADE'});
   assert.equal(executed.status,200,JSON.stringify(executed.body));
   assert.equal(executed.body.status,'SUCCESS');
   const journal=await call(ctx.base,'/api/trades');
   assert.equal(journal.body.trades[0].status,'SUCCESS');
   assert.equal(journal.body.trades[0].actualInputAtomic,'10000000');
   const again=await call(ctx.base,'/api/orders/execute',{
     previewId:prepared.body.previewId,confirm:'LIVE TRADE'});
   assert.equal(again.status,400);
 }finally{await clean(ctx);}
});
test('synthetic Jupiter signature substitution yields UNKNOWN and locks further trading',async()=>{
 const ctx=await launch({badSignature:true});
 try{
   const preview=await call(ctx.base,'/api/orders/prepare',{
     side:'BUY',mint:ctx.mint,amountSol:.01});
   assert.equal(preview.status,200,JSON.stringify(preview.body));
   const result=await call(ctx.base,'/api/orders/execute',{
     previewId:preview.body.previewId,confirm:'LIVE TRADE'});
   assert.equal(result.status,400);
   assert.match(result.body.error,/uncertain|ungewiss|Nicht erneut/i);
   const trades=await call(ctx.base,'/api/trades');
   assert.equal(trades.body.trades[0].status,'UNKNOWN');
   const again=await call(ctx.base,'/api/orders/prepare',{
     side:'BUY',mint:ctx.mint,amountSol:.01});
   assert.equal(again.status,400);
 }finally{await clean(ctx);}
});
test('synthetic Jupiter quote with unreviewed program fails before journal/signature',async()=>{
 const ctx=await launch({programs:ComputeBudgetProgram.programId.toBase58()});
 try{
   const preview=await call(ctx.base,'/api/orders/prepare',{
     side:'BUY',mint:ctx.mint,amountSol:.01});
   assert.equal(preview.status,400);
   assert.match(preview.body.error,/Jupiter-Route|nicht geprüft|Ungeprüfte/i);
   const trades=await call(ctx.base,'/api/trades');
   assert.equal(trades.body.trades.length,0);
 }finally{await clean(ctx);}
});
