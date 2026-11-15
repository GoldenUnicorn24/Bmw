#!/usr/bin/env node
// Public historical data collection. No private wallet, keys, live execution, or fabricated rows.
// Run on GitHub Actions with the provider's public rate limits and attribution.
import fs from 'node:fs';
import path from 'node:path';
import {parseCandles,makeExamples} from '../research/features.mjs';
import {trainResearch} from '../research/learner.mjs';

const BASE='https://api.geckoterminal.com/api/v2';
const DIR=process.env.RESEARCH_OUTPUT_DIR||'./research-output';
const MAX_POOLS=Math.min(25,Math.max(1,Number(process.env.RESEARCH_POOL_LIMIT||20)));
fs.mkdirSync(DIR,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
// Github runners can share a provider/IP quota. Conserve the free API budget.
const REQUEST_SPACING_MS=6100;
let lastApiAttempt=0;
function finite(n){const x=Number(n);return Number.isFinite(x)?x:NaN;}
async function getJson(endpoint){
  const url=BASE+endpoint;
  for(let attempt=0;attempt<4;attempt++){
    await wait(Math.max(0,REQUEST_SPACING_MS-(Date.now()-lastApiAttempt)));
    lastApiAttempt=Date.now();
    const controller=new AbortController();
    const timeout=setTimeout(()=>controller.abort(),12000);
    try{
      const r=await fetch(url,{headers:{accept:'application/json','user-agent':'MemePulseResearch/0.6 (public historical OHLCV only)'},
        signal:controller.signal});
      if(!r.ok){
        if([429,500,502,503,504].includes(r.status)&&attempt<3){
          const hint=Number(r.headers.get('retry-after'));
          const delay=Number.isFinite(hint)&&hint>0?Math.min(hint*1000,60000):(8000*(attempt+1));
          console.error('Provider limit/unavailable',r.status,endpoint.slice(0,48),'retry in',delay,'ms');
          await wait(delay);
          continue;
        }
        throw Error('HTTP '+r.status+' '+endpoint.slice(0,80));
      }
      return await r.json();
    }finally{clearTimeout(timeout);}
  }
  throw Error('Provider retry exhausted');
}
async function discoverPools(){
  const candidates=new Map();
  const sources=['/networks/solana/trending_pools','/networks/solana/trending_pools?page=2',
    '/networks/solana/pools?sort=h24_volume_usd_desc','/networks/solana/new_pools'];
  for(const src of sources){
    try{
      const data=await getJson(src);
      for(const row of data.data||[]){
        const x=row.attributes||{};
        const address=x.address||String(row.id||'').replace(/^solana_/,'');
        if(!/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(address))continue;
        const liquidity=finite(x.reserve_in_usd);
        const volume24=finite(x.volume_usd?.h24);
        const created=Date.parse(x.pool_created_at||'');
        if(!Number.isFinite(liquidity)||liquidity<60_000)continue;
        if(!Number.isFinite(volume24)||volume24<50_000)continue;
        if(!Number.isFinite(created)||Date.now()-created<24*3600_000)continue;
        const name=String(x.name||'').slice(0,80);
        if(/USDC\s*\/\s*USDT|USDT\s*\/\s*USDC|SOL\s*\/\s*USDC|USDC\s*\/\s*SOL/i.test(name))continue;
        candidates.set(address,{pool:address,name,liquidityUsd:liquidity,volume24hUsd:volume24,source:src});
      }
    }catch(e){console.error('Discover warning',src,e.message);}
    await wait(650);
  }
  return [...candidates.values()].sort((a,b)=>b.volume24hUsd-a.volume24hUsd).slice(0,MAX_POOLS);
}
const byPool=new Map();
const previousPath=path.join(DIR,'dataset.json');
try {
  const previous=JSON.parse(fs.readFileSync(previousPath,'utf8'));
  if(previous.metadata?.source==='GeckoTerminal public API'&&previous.metadata?.network==='solana'){
    for(const p of previous.pools||[]) {
      if(/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(p?.meta?.pool||'')&&Array.isArray(p.candles)){
        byPool.set(p.meta.pool,{meta:p.meta,candles:parseCandles(p.candles.map(x=>
          [x.time,x.open,x.high,x.low,x.close,x.volume]))});
      }
    }
  }
  console.log('Restored historical tracked pools:',byPool.size);
} catch(e) {
  if(e.code!=='ENOENT')console.error('Previous research cache rejected:',e.message);
}
const collected=[];
const metadata={source:'GeckoTerminal public API',endpoint:BASE,network:'solana',
  collectedAt:new Date().toISOString(),timeframe:'5m',candleLimit:1800,
  sourceDocs:'https://api.geckoterminal.com/docs/index.html',
  note:'Selection includes current surviving liquid pools, with severe survivorship bias.'};
const errors=[];
const pools=await discoverPools();
console.log('Public eligible Solana pools:',pools.length);
for(const item of pools){
  try{
    const response=await getJson('/networks/solana/pools/'+encodeURIComponent(item.pool)+
      '/ohlcv/minute?aggregate=5&currency=usd&limit=900');
    const raw=response.data?.attributes?.ohlcv_list||[];
    let candles=parseCandles(raw);
    if(candles.length>=700){
      try{
        const oldest=candles[0].time-1;
        const older=await getJson('/networks/solana/pools/'+encodeURIComponent(item.pool)+
          '/ohlcv/minute?aggregate=5&currency=usd&limit=900&before_timestamp='+oldest);
        const prior=older.data?.attributes?.ohlcv_list||[];
        candles=parseCandles([...prior,...raw]);
      }catch(e){errors.push({pool:item.pool,error:'HISTORY_PAGE_UNAVAILABLE: '+String(e.message).slice(0,80)});}
    }
    const previous=byPool.get(item.pool);
    if(previous)candles=parseCandles([...previous.candles.map(x=>
      [x.time,x.open,x.high,x.low,x.close,x.volume]),...candles.map(x=>
      [x.time,x.open,x.high,x.low,x.close,x.volume])]);
    // Bound long-term cache size; requires multiple sessions to collect missing/rugged tokens.
    candles=candles.slice(-5000);
    const ex=makeExamples(item.pool,candles.map(x=>
      [x.time,x.open,x.high,x.low,x.close,x.volume]));
    if(candles.length<120 || ex.length<10){
      errors.push({pool:item.pool,error:'TOO_FEW_LABELS',count:candles.length});continue;
    }
    byPool.set(item.pool,{meta:item,candles});
    collected.push({meta:item,candles,examples:ex});
    console.log(item.name+' '+item.pool.slice(0,12)+' candles='+candles.length+' labels='+ex.length);
  }catch(e){errors.push({pool:item.pool,error:String(e.message).slice(0,130)});}
  await wait(1100);
}
// Retain historical pools that are no longer trending, subject to a 45-day expiry.
// Do not assume removed/dead pools had a positive exit; no future price means no label.
const cutoff=Date.now()/1000-45*86400;
const historical=[...byPool.values()].filter(x=>x.candles.at(-1)?.time>cutoff)
  .sort((a,b)=>b.candles.at(-1).time-a.candles.at(-1).time).slice(0,40);
const poolExamples=historical.map(x=>({...x,examples:makeExamples(x.meta.pool,
  x.candles.map(y=>[y.time,y.open,y.high,y.low,y.close,y.volume]))}));
const examples=poolExamples.flatMap(p=>p.examples);
const report=trainResearch(examples,{...metadata,requestedPools:pools.length,
  successfulPools:collected.length,historicalPools:historical.length,
  examples:examples.length,errors});
const summary={...report,model:report.model||null};
fs.writeFileSync(path.join(DIR,'training-report.json'),JSON.stringify(summary,null,2));
fs.writeFileSync(path.join(DIR,'dataset.json'),JSON.stringify({
  metadata,pools:historical.map(x=>({meta:x.meta,candles:x.candles})),
  // Regenerable labels are deliberately omitted from the long-term cache.
  exampleCount:examples.length
},null,0));
fs.writeFileSync(path.join(DIR,'README.txt'),[
 'MemePulse RESEARCH-ONLY market data training artifact.',
 'Source: '+metadata.source+' '+metadata.sourceDocs,
 'Observations fetched at: '+metadata.collectedAt,
 'Historical 5-minute OHLCV is not a record of executable trades.',
 'DO NOT deploy model for real trading. It was trained on present-day selected pools (survivorship bias).',
 'No wallet keys, live orders, seed phrases, or user data are collected.',
 'Status: '+summary.status,'Dataset examples: '+examples.length,
 'Distinct pools: '+historical.length,
 'Research cache may contain historical pools not present in the current promotional feed.',''
].join('\n'));
console.log('RESEARCH REPORT '+JSON.stringify({status:report.status,
  counts:report.counts,metrics:report.metrics,errors:errors.slice(0,5)},null,2));
if(collected.length<5)console.error('WARNING: fewer than 5 eligible pools. No production model permitted.');
// Research dataset scarcity is a soft status, not a reason to falsify results.
