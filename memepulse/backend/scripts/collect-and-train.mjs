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
function finite(n){const x=Number(n);return Number.isFinite(x)?x:NaN;}
async function getJson(endpoint){
  const url=BASE+endpoint;
  for(let attempt=0;attempt<3;attempt++){
    const controller=new AbortController();
    const timeout=setTimeout(()=>controller.abort(),12000);
    try{
      const r=await fetch(url,{headers:{accept:'application/json','user-agent':'MemePulseResearch/0.6 (public historical OHLCV only)'},
        signal:controller.signal});
      if(!r.ok){
        if([429,500,502,503,504].includes(r.status)&&attempt<2){await wait(3500*(attempt+1));continue;}
        throw Error('HTTP '+r.status+' '+endpoint.slice(0,80));
      }
      return await r.json();
    }finally{clearTimeout(timeout);}
  }
  throw Error('Provider retry exhausted');
}
async function discoverPools(){
  const candidates=new Map();
  const sources=['/networks/solana/trending_pools','/networks/solana/pools?sort=h24_volume_usd_desc'];
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
const collected=[];
const metadata={source:'GeckoTerminal public API',endpoint:BASE,network:'solana',
  collectedAt:new Date().toISOString(),timeframe:'5m',candleLimit:900,
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
    const candles=parseCandles(raw);
    const ex=makeExamples(item.pool,raw);
    // Enforce reasonable chronological coverage rather than pretend to have rich history.
    if(candles.length<120 || ex.length<10){errors.push({pool:item.pool,error:'TOO_FEW_CANDLES',count:candles.length});continue;}
    collected.push({meta:item,candles,examples:ex});
    console.log(item.name+' '+item.pool.slice(0,12)+' candles='+candles.length+' labels='+ex.length);
  }catch(e){errors.push({pool:item.pool,error:String(e.message).slice(0,130)});}
  await wait(1100);
}
const examples=collected.flatMap(p=>p.examples);
const report=trainResearch(examples,{...metadata,requestedPools:pools.length,successfulPools:collected.length,
  examples:examples.length,errors});
const summary={...report,model:report.model||null};
fs.writeFileSync(path.join(DIR,'training-report.json'),JSON.stringify(summary,null,2));
fs.writeFileSync(path.join(DIR,'dataset.json'),JSON.stringify({metadata,pools:collected.map(x=>({
  meta:x.meta,candles:x.candles})),examples},null,0));
fs.writeFileSync(path.join(DIR,'README.txt'),[
 'MemePulse RESEARCH-ONLY market data training artifact.',
 'Source: '+metadata.source+' '+metadata.sourceDocs,
 'Observations fetched at: '+metadata.collectedAt,
 'Historical 5-minute OHLCV is not a record of executable trades.',
 'DO NOT deploy model for real trading. It was trained on present-day selected pools (survivorship bias).',
 'No wallet keys, live orders, seed phrases, or user data are collected.',
 'Status: '+summary.status,'Dataset examples: '+examples.length,
 'Distinct pools: '+collected.length,''
].join('\n'));
console.log('RESEARCH REPORT '+JSON.stringify({status:report.status,
  counts:report.counts,metrics:report.metrics,errors:errors.slice(0,5)},null,2));
if(collected.length<5)console.error('WARNING: fewer than 5 eligible pools. No production model permitted.');
// Research dataset scarcity is a soft status, not a reason to falsify results.
