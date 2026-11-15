#!/usr/bin/env node
// Separate US-listed penny stock RESEARCH ONLY. No OTC price assumptions, no broker.
// Nasdaq-listed common-share universe verified from Nasdaq Trader directory;
// historical Stooq daily CSV is licensed third-party data, not redistributed.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {parseDailyCsv,equityExamples} from '../research/equity-features.mjs';
import {trainEquityResearch} from '../research/equity-learner.mjs';
const dir=process.env.PENNY_OUTPUT_DIR||'./research-output/penny-stocks';
const max=Math.min(65,Math.max(1,Number(process.env.PENNY_TICKER_LIMIT||35)));
fs.mkdirSync(dir,{recursive:true});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
let last=0;
async function fetchText(url){
 for(let attempt=0;attempt<3;attempt++){
  await wait(Math.max(0,2200-(Date.now()-last)));
  last=Date.now();
  const ac=new AbortController();const timer=setTimeout(()=>ac.abort(),16000);
  try {
   const res=await fetch(url,{headers:{accept:'text/plain,text/csv',
    'user-agent':'Mozilla/5.0 MemePulseMarketResearch/0.7'},signal:ac.signal});
   if([429,500,502,503,504].includes(res.status)&&attempt<2){
    await wait(8000*(attempt+1));continue;
   }
   if(!res.ok)throw Error('HTTP '+res.status);
   const text=await res.text();
   if(text.length>6_000_000)throw Error('Response over research size limit');
   return text;
  }finally{clearTimeout(timer);}
 }
 throw Error('Price provider unavailable');
}
function parseNasdaqDirectory(raw){
 const lines=raw.split(/\r?\n/);
 const head=lines.shift()?.trim().split('|')||[];
 const idx=name=>head.indexOf(name);
 for(const k of ['Symbol','Security Name','ETF','Test Issue','Market Category'])
   if(idx(k)<0)throw Error('Nasdaq source missing field '+k);
 const found=[];
 for(const line of lines){
  const row=line.split('|');if(row.length<head.length-2)continue;
  const ticker=row[idx('Symbol')]||'',name=row[idx('Security Name')]||'';
  if(!/^[A-Z]{1,5}$/.test(ticker)||row[idx('ETF')]!=='N'||row[idx('Test Issue')]!=='N')
    continue;
  // Nasdaq Capital Market "S" is a SMALL-CAP UNIVERSE, not a penny claim.
  if(row[idx('Market Category')]!=='S')continue;
  if(/\b(warrant|unit\b|preferred|depositary|note\b|rights|etn|etf|trust|fund\b)/i.test(name))continue;
  if(!/common stock|ordinary shares|common shares|american depositary shares/i.test(name))continue;
  found.push({ticker,name:name.slice(0,100),listing:'Nasdaq Capital Market'});
 }
 return found;
}
const errors=[],collected=[],samples=[];
const origin={assetClass:'US_LISTED_PENNY_STOCK',
 directory:'https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt',
 directoryDocs:'https://nasdaqtrader.com/Trader.aspx?id=SymbolDirDefs',
 priceProvider:'Stooq historical daily close',
 priceFormat:'https://stooq.com/q/d/l/?s=SYMBOL.us&i=d',
 selectedAt:new Date().toISOString(),
 definition:'Entry-day close USD 0.10 to USD 5.00; dollar trading volume >= 500,000 per day',
 restriction:'No raw Stooq price series redistribution. Research metrics only; not licensed broker data',
 disclaimer:'Excludes OTC and many delisted issuers; current listed-symbol universe has severe survivorship bias'};
let universe=[];
try{
 const listed=await fetchText(origin.directory);
 universe=parseNasdaqDirectory(listed);
}catch(e){errors.push({stage:'SYMBOL_DIRECTORY',error:String(e.message).slice(0,140)});}
const candidates=universe.map(x=>({
 ...x,order:crypto.createHash('sha256').update('memepulse-research-v07:'+x.ticker).digest('hex')
})).sort((a,b)=>a.order.localeCompare(b.order)).slice(0,max);
for(const item of candidates){
 try {
  const lower=item.ticker.toLowerCase();
  const csv=await fetchText('https://stooq.com/q/d/l/?s='+lower+'.us&i=d&d1=20240101');
  const rows=parseDailyCsv(csv);
  if(rows.length<110)throw Error('NO_USABLE_DAILY_PRICE_SERIES');
  const labels=equityExamples(item.ticker,rows);
  if(!labels.length)throw Error('NO_OBSERVATIONS_WITH_PENNY_ENTRY_PRICE_AND_LIQUIDITY');
  collected.push({ticker:item.ticker,exchange:item.listing,
    historicalPennyExamples:labels.length,
    first:rows[0].date,last:rows.at(-1).date});
  samples.push(...labels);
  console.log('PENNY',item.ticker,'daily bars',rows.length,'qualifying entries',labels.length);
 }catch(e){errors.push({ticker:item.ticker,error:String(e.message).slice(0,110)});}
}
const report=trainEquityResearch(samples,{...origin,requested:max,
 availableUniverse:universe.length,attempted:candidates.length,
 qualifiedSymbols:collected.length,qualifyingExamples:samples.length,
 warning:'Selected Nasdaq small-cap universe, verified penny price only historically at entry. Not OTC.',
 failures:errors.slice(0,35)});
report.assetClass='US_LISTED_PENNY_STOCK';
report.liveTradingAllowed=false;
fs.writeFileSync(path.join(dir,'training-report.json'),JSON.stringify(report,null,2));
fs.writeFileSync(path.join(dir,'market-source-metadata.json'),
 JSON.stringify({source:origin,qualified:collected,failures:errors},null,2));
console.log('PENNY RESEARCH RESULT',JSON.stringify({status:report.status,
 qualifiedSymbols:collected.length,examples:samples.length,counts:report.counts,
 metrics:report.metrics,failures:errors.slice(0,4)}));
