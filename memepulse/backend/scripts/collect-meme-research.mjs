#!/usr/bin/env node
// Research-only: classify Solana meme tokens by CoinGecko's explicit
// "solana-meme-coins" category AND independently verified Solana contract mint.
// Never label every promoted token as a meme coin just from its name/symbol.
import fs from 'node:fs';
import path from 'node:path';
import {parseCandles,makeExamples} from '../research/features.mjs';
import {trainResearch} from '../research/learner.mjs';
const CG='https://api.coingecko.com/api/v3';
const GT='https://api.geckoterminal.com/api/v2';
const DIR=process.env.MEME_OUTPUT_DIR||'./research-output/meme-coins';
const MAX=Math.min(40,Math.max(1,Number(process.env.MEME_COIN_LIMIT||24)));
const MAX_CANDLE_PAGES=Math.min(3,Math.max(1,Number(process.env.MEME_CANDLE_PAGES||2)));
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
fs.mkdirSync(DIR,{recursive:true});
let lastReq=0;
async function jsonGet(url){
 for(let i=0;i<3;i++){
  await sleep(Math.max(0,6500-(Date.now()-lastReq)));
  lastReq=Date.now();
  const ac=new AbortController(),timer=setTimeout(()=>ac.abort(),17000);
  try{
   const response=await fetch(url,{headers:{accept:'application/json',
    'user-agent':'MemePulsePublicResearch/0.7'},signal:ac.signal});
   if(!response.ok){
    if([429,500,502,503,504].includes(response.status)&&i<2){
      const h=Number(response.headers.get('retry-after'));
      await sleep(Number.isFinite(h)&&h>0?Math.min(h*1000,60000):10000*(i+1));continue;
    }
    throw Error('HTTP '+response.status+' '+new URL(url).pathname);
   }
   return await response.json();
  }finally{clearTimeout(timer);}
 }
 throw Error('Provider response unavailable');
}
const provenance={assetClass:'SOLANA_MEME_COINS',source:'CoinGecko Solana Meme category + GeckoTerminal',
 classificationUrl:'https://www.coingecko.com/en/categories/solana-meme-coins',
 ohlcvUrl:'https://api.geckoterminal.com/docs/index.html',
 collectedAt:new Date().toISOString(),classificationAsOf:new Date().toISOString(),
 warning:'Membership determined today, historical retrospective labels have selection and survivorship bias'};
const failures=[],history=[],examples=[];
let listing=new Map();
try {
 const list=await jsonGet(CG+'/coins/list?include_platform=true');
 if(Array.isArray(list))for(const row of list){
   const mint=String(row.platforms?.solana||'');
   if(typeof row.id==='string'&&/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(mint))
     listing.set(row.id,mint);
 }
 console.log('Verified Solana contract IDs in CoinGecko list:',listing.size);
}catch(e){failures.push({stage:'COIN_PLATFORM_LIST',error:String(e.message).slice(0,120)});}
let results=[];
try{
 const data=await jsonGet(CG+'/coins/markets?vs_currency=usd&category=solana-meme-coins&order=market_cap_desc&per_page=100&page=1');
 if(!Array.isArray(data))throw Error('CoinGecko returned no category array');
 results=data.filter(x=>typeof x.id==='string'&&/^[a-z0-9-]{2,120}$/.test(x.id)).slice(0,MAX);
}catch(e){failures.push({stage:'CATEGORY_DISCOVERY',message:String(e.message).slice(0,160)});}
for(const coin of results){
 try{
   // An official platforms-list lookup avoids rate-limited per-token calls.
   // If unavailable, verify an individual CoinGecko coin detail as fallback.
   let mint=listing.get(coin.id)||'';
   if(!mint){
     const info=await jsonGet(CG+'/coins/'+encodeURIComponent(coin.id)+
       '?localization=false&tickers=false&market_data=false&community_data=false&developer_data=false&sparkline=false');
     mint=String(info.platforms?.solana||info.detail_platforms?.solana?.contract_address||'');
   }
   if(!/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(mint))
      throw Error('Verified Solana contract missing; classification withheld');
   const token=await jsonGet(GT+'/networks/solana/tokens/'+encodeURIComponent(mint)+'/pools');
   const pools=(token.data||[]).map(x=>({address:x.attributes?.address,
     liq:Number(x.attributes?.reserve_in_usd),volume24:Number(x.attributes?.volume_usd?.h24)}))
      .filter(x=>/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(x.address)&&Number.isFinite(x.liq)&&x.liq>=60000)
      .sort((a,b)=>b.liq-a.liq);
   if(!pools.length)throw Error('No pool passes historical training liquidity filter');
   const selected=pools[0];
   const source=GT+'/networks/solana/pools/'+selected.address+
    '/ohlcv/minute?aggregate=5&currency=usd&limit=900';
   const ohlcv=await jsonGet(source);
   const raw=ohlcv.data?.attributes?.ohlcv_list||[];
   let candles=parseCandles(raw);
   // Extend genuinely observed history backward; do not fabricate missing intervals.
   for(let page=1;page<MAX_CANDLE_PAGES&&candles.length>=300;page++){
     try{
       const before=candles[0].time-1;
       const extra=await jsonGet(GT+'/networks/solana/pools/'+selected.address+
         '/ohlcv/minute?aggregate=5&currency=usd&limit=900&before_timestamp='+before);
       const previous=parseCandles(extra.data?.attributes?.ohlcv_list||[]);
       if(!previous.length)break;
       const merged=parseCandles([...candles,...previous].map(x=>
         [x.time,x.open,x.high,x.low,x.close,x.volume]));
       if(merged.length===candles.length)break;
       candles=merged;
     }catch(err){failures.push({coinId:coin.id,stage:'OLDER_CANDLES',
       error:String(err.message).slice(0,110)});break;}
   }
   const ex=makeExamples(mint,candles.map(b=>[b.time,b.open,b.high,b.low,b.close,b.volume]));
   if(ex.length<12)throw Error('Insufficient continuous 5m history');
   if(history.some(x=>x.meta.mint===mint))throw Error('Duplicate coin mint: no extra training weight');
   const meta={coinId:coin.id,coinName:String(coin.name||'').slice(0,70),
     mint,geckoPool:selected.address,category:'solana-meme-coins',liquidityAtCollection:selected.liq,
     classificationSource:provenance.classificationUrl,ohlcvSource:source};
   history.push({meta,candles});
   examples.push(...ex);
   console.log('MEME',meta.coinName,'pool',selected.address.slice(0,10),
     'candles',candles.length,'examples',ex.length);
 }catch(e){failures.push({coinId:coin.id,error:String(e.message).slice(0,145)});}
}
const report=trainResearch(examples,{...provenance,requestedCoins:results.length,
 verifiedMemePools:history.length,trainingExamples:examples.length,failures});
report.assetClass='SOLANA_MEME_COINS';
report.liveTradingAllowed=false;
report.categoryVerification='CoinGecko solana-meme-coins + onchain Solana mint';
fs.writeFileSync(path.join(DIR,'training-report.json'),JSON.stringify(report,null,2));
fs.writeFileSync(path.join(DIR,'market-source-metadata.json'),
 JSON.stringify({provenance,coins:history.map(x=>x.meta),failures},null,2));
// No raw candles or symbol-to-price distribution. Avoid redistributing provider market data.
console.log('MEME RESEARCH RESULT',JSON.stringify({status:report.status,verified:history.length,
 samples:examples.length,counts:report.counts,metrics:report.metrics,failures:failures.slice(0,4)}));
