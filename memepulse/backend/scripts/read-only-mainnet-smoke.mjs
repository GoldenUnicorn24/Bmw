#!/usr/bin/env node
// PUBLIC MAINNET READ-ONLY smoke-test. This script can never sign, broadcast,
// transfer, or request a taker-prepared Jupiter transaction.
import fs from 'node:fs';
import path from 'node:path';
import {SOL_MAINNET_GENESIS} from '../withdrawal.mjs';
const OUT=process.env.SMOKE_OUTPUT_DIR||'./research-output/read-only-mainnet';
fs.mkdirSync(OUT,{recursive:true});
const WSOL='So11111111111111111111111111111111111111112';
const USDC='EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const report={at:new Date().toISOString(),tradingEnabled:false,secretKeyUsed:false,
 tested:['Solana mainnet public getGenesisHash','Jupiter v2 quote without taker'],
 checks:{},readyForRealTrading:false,notices:[
 'A read-only quote is NOT a signed swap test',
 'No Jupiter transaction signed or broadcast',
 'Private wallet, successful onchain swap and recovery remain unverified'
 ]};
if(process.env.SOLANA_PRIVATE_KEY_B58||process.env.ENABLE_LIVE_TRADES==='YES_I_ACCEPT_REAL_LOSSES')
 throw Error('Refuse read-only smoke test with trading secrets or live trading enabled');
async function getJson(url,options={}){
 const r=await fetch(url,{...options,signal:AbortSignal.timeout(14000)});
 if(!r.ok)throw Error('HTTP '+r.status+' '+new URL(url).host);
 return await r.json();
}
try{
 const rpc=await getJson('https://api.mainnet-beta.solana.com',{method:'POST',
   headers:{'content-type':'application/json'},body:JSON.stringify({
     jsonrpc:'2.0',id:1,method:'getGenesisHash',params:[]})});
 report.checks.genesis={passed:rpc.result===SOL_MAINNET_GENESIS,
  error:rpc.error?.message||null,matchingMainnet:rpc.result===SOL_MAINNET_GENESIS};
}catch(e){report.checks.genesis={passed:false,error:String(e.message).slice(0,160)};}
try{
 const params=new URLSearchParams({inputMint:WSOL,outputMint:USDC,amount:'10000000'});
 // No taker= quote-only, so API cannot even return a signable transaction.
 const quote=await getJson('https://api.jup.ag/swap/v2/order?'+params);
 const safe=quote?.inputMint===WSOL&&quote?.outputMint===USDC&&
   BigInt(quote.inAmount||'0')===10000000n&&BigInt(quote.outAmount||'0')>0n&&
   !quote.transaction;
 report.checks.jupiterQuote={passed:!!safe,router:String(quote?.router||'').slice(0,30),
  quoteOnly:!quote?.transaction,amountMatch:quote?.inAmount==='10000000',
  outputPresent:!!quote?.outAmount};
}catch(e){report.checks.jupiterQuote={passed:false,error:String(e.message).slice(0,160)};}
report.readOnlyConnectivityPassed=Object.values(report.checks).every(x=>x.passed);
fs.writeFileSync(path.join(OUT,'report.json'),JSON.stringify(report,null,2));
console.log('READ-ONLY MAINNET SMOKE '+JSON.stringify(report));
if(!report.readOnlyConnectivityPassed)process.exitCode=1;
