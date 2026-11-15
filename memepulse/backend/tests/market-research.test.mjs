import test from 'node:test';
import assert from 'node:assert/strict';
import {parseDailyCsv,pennyFeatures,equityExamples,EQUITY_FEATURES} from '../research/equity-features.mjs';
import {splitEquityExamples,trainEquityResearch,equityProbability} from '../research/equity-learner.mjs';
import {separateMarketReports} from '../research/market-status.mjs';

const daily=(n=230,start=1704067200,base=.92)=>Array.from({length:n},(_,i)=>{
  const at=new Date((start+i*86400)*1000).toISOString().slice(0,10);
  const open=base*(1+Math.sin(i/19)*.08+i*.0004);
  const close=open*(1+.02*Math.sin(i/3));
  return [at,open,(Math.max(open,close))*1.01,(Math.min(open,close))*.99,close,1_700_000];
});
const csv=rows=>'Date,Open,High,Low,Close,Volume\n'+rows.map(x=>x.join(',')).join('\n');
test('Equity CSV must be valid OHLCV, not an HTML quota/rate-limit response',()=>{
 assert.deepEqual(parseDailyCsv('<html>Quota exhausted</html>'),[]);
 assert.deepEqual(parseDailyCsv('N/D'),[]);
 assert.equal(parseDailyCsv(csv(daily(110))).length,110);
 assert.equal(parseDailyCsv(csv([['2026-01-01',1,1,.9,2,100]] )).length,0);
});
test('Penny threshold is verified from ENTRY-DAY share price, not ticker name or later price',()=>{
 const rows=parseDailyCsv(csv(daily(230)));
 const labelled=equityExamples('ABCD',rows);
 assert.ok(labelled.length>5);
 assert.ok(labelled.every(x=>x.closeAtEntry>=.1&&x.closeAtEntry<=5));
 assert.ok(labelled.every(x=>x.assetClass==='US_LISTED_PENNY_STOCK'&&x.estimatedCostPct===4));
 const expensive=rows.map(x=>({...x,open:x.open*12,high:x.high*12,low:x.low*12,close:x.close*12}));
 assert.equal(equityExamples('ABCD',expensive).length,0);
});
test('Penny feature computation is causal; changing FUTURE prices cannot change present inputs',()=>{
 const bars=parseDailyCsv(csv(daily(230)));
 const a=pennyFeatures(bars,100);
 assert.equal(a.length,EQUITY_FEATURES.length);
 const altered=bars.map((x,i)=>i>100?{...x,close:x.close*3}:x);
 assert.deepEqual(pennyFeatures(altered,100),a);
});
test('Penny model never trades, uses unseen issuers and embargoed future test',()=>{
 const bars=parseDailyCsv(csv(daily(500)));
 const small=equityExamples('SMAL',bars).slice(0,10);
 const res=trainEquityResearch(small);
 assert.equal(res.productionAllowed,false);
 assert.equal(res.status,'INSUFFICIENT_DATA'); 
 const symbols=Array.from({length:15},(_,i)=>String.fromCharCode(65+i)+'BCD');
 const valid=symbols.flatMap(sym=>equityExamples(sym,bars));
 const groups=splitEquityExamples(valid);
 assert.ok(groups.train.every(x=>x.at+22*86400<groups.cutoff));
 assert.ok(groups.later.every(x=>x.at>=groups.cutoff));
 assert.ok(groups.unseen.every(x=>!groups.train.some(t=>t.pool===x.pool)));
 const fit=trainEquityResearch(valid,{fixture:'synthetic tests only'});
 assert.equal(fit.productionAllowed,false);
 if(fit.model){
   assert.equal(fit.model.productionAllowed,false);
   const p=equityProbability(fit.model,valid[0].features);
   assert.ok(p>=0&&p<=1);
 }
});
test('Meme and stocks remain separate with no trading permissions, even with installed reports',()=>{
 const x=separateMarketReports();
 assert.equal(x.productionAllowed,false);
 assert.equal(x.stockTradingAvailable,false);
 assert.equal(x.cryptoStockModelsShared,false);
 assert.equal(x.markets.usPennyStocks.status,'NO_TRAINING_REPORT');
});
