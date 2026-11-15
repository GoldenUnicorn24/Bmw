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
const MAX=Math.min(32,Math.max(1,Number(process.env.MEME_COIN_LIMIT||15)));
const PAGES=Math.min(4,Math.max(1,Number(process.env.MEME_CANDLE_PAGES||3)));
const POOL_TRIES=Math.min(3,Math.max(1,Number(process.env.MEME_POOL_TRIES||2)));
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
    'user-agent':'MemePulsePublicResearch/0.8'},signal:ac.signal});
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
let results=[];
for(let page=1;page<=2;page++){
 try{
  const data=await jsonGet(CG+'/coins/markets?vs_currency=usd&category=solana-meme-coins'+
    '&order=market_cap_desc&per_page=60&page='+page);
  if(!Array.isArray(data))throw Error('CoinGecko returned no category array');
  results.push(...data.filter(x=>typeof x.id==='string'&&/^[a-z0-9-]{2,120}$/.test(x.id)));
  if(data.length<50)break;
 }catch(e){
   failures.push({stage:'CATEGORY_DISCOVERY',page,message:String(e.message).slice(0,160)});
   if(!results.length)break;
 }
}
results=[...new Map(results.map(x=>[x.id,x])).values()].slice(0,MAX);
console.log('Category records selected:',results.length,'pages of history per pool:',PAGES);
for(const coin of results){
 try{
   // Only a matching named Solana contract from CoinGecko is acceptable.
   const info=await jsonGet(CG+'/coins/'+encodeURIComponent(coin.id)+
     '?localization=false&tickers=false&market_data=false&community_data=false&developer_data=false&sparkline=false');
   const mint=String(info.platforms?.solana||info.detail_platforms?.solana?.contract_address||'');
   if(!/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(mint))
      throw Error('Verified Solana contract missing; classification withheld');
   const token=await jsonGet(GT+'/networks/solana/tokens/'+encodeURIComponent(mint)+'/pools');
   const pools=(token.data||[]).map(x=>({address:x.attributes?.address,
     liq:Number(x.attributes?.reserve_in_usd),volume24:Number(x.attributes?.volume_usd?.h24)}))
      .filter(x=>/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(x.address)&&Number.isFinite(x.liq)&&x.liq>=60000)
      .sort((a,b)=>b.liq-a.liq);
   if(!pools.length)throw Error('No pool passes historical training liquidity filter');
   // A pool can quote the meme token rather than list it as the base token.
   // The explicit token=<verified mint> parameter is mandatory; otherwise
   // the training labels might silently become SOL/USDC prices.
   const candidates=[];
   for(const selected of pools.slice(0,POOL_TRIES)){
     let combined=[],cursor=null,historyPages=0;
     const source=GT+'/networks/solana/pools/'+selected.address+
       '/ohlcv/minute?aggregate=5&currency=usd&limit=900&token='+encodeURIComponent(mint);
     try{
       for(let page=0;page<PAGES;page++){
         const query=cursor===null?source:source+'&before_timestamp='+cursor;
         const response=await jsonGet(query);
         const raw=response.data?.attributes?.ohlcv_list||[];
         const part=parseCandles(raw);
         if(!part.length)break;
         combined=parseCandles([...combined.map(x=>[x.time,x.open,x.high,x.low,x.close,x.volume]),
           ...raw]);
         historyPages++;
         const oldest=part[0].time;
         if(cursor!==null&&oldest>=cursor)break;
         cursor=oldest-1;
         if(part.length<100)break;
       }
       const ex=makeExamples(mint,combined.map(b=>[b.time,b.open,b.high,b.low,b.close,b.volume]));
       candidates.push({selected,source,candles:combined,examples:ex,historyPages});
     }catch(e){
       failures.push({coinId:coin.id,pool:selected.address,
         stage:'POOL_PRICE_HISTORY',error:String(e.message).slice(0,100)});
     }
   }
   candidates.sort((a,b)=>b.examples.length-a.examples.length||b.selected.liq-a.selected.liq);
   const best=candidates[0];
   if(!best||best.examples.length<12)
      throw Error('No verified-mint 5m price series with sufficient continuous labelled history');
   const meta={coinId:coin.id,coinName:String(coin.name||'').slice(0,70),
     mint,geckoPool:best.selected.address,category:'solana-meme-coins',
     liquidityAtCollection:best.selected.liq,
     classificationSource:provenance.classificationUrl,
     ohlcvSource:best.source,coinMintUsedAsPriceToken:true,
     candlePages:best.historyPages,rawCandleCount:best.candles.length,
     labelCount:best.examples.length};
   history.push({meta,candles:best.candles});
   examples.push(...best.examples);
   console.log('MEME',meta.coinName,'pool',meta.geckoPool.slice(0,10),
     'candles',best.candles.length,'labels',best.examples.length);
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
