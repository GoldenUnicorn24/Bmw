// Independent equity-only research learner. Never connected to any stock broker.
// The Solana crypto logistic model has different feature semantics/time resolution.
import crypto from 'node:crypto';
import {EQUITY_FEATURES,EQUITY_ESTIMATED_COST_PCT,EQUITY_HORIZON} from './equity-features.mjs';
const mean=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0;
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const sigmoid=x=>1/(1+Math.exp(-clamp(x,-40,40)));
const dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0);
export function splitEquityExamples(rows){
 const valid=rows.filter(x=>x&&/^[A-Z]{1,5}$/.test(x.pool)&&
  x.assetClass==='US_LISTED_PENNY_STOCK'&&Number.isFinite(x.at)&&
  x.features?.length===EQUITY_FEATURES.length&&x.features.every(Number.isFinite)&&
  [0,1].includes(x.label)&&Number.isFinite(x.netPct));
 const symbols=[...new Set(valid.map(x=>x.pool))].sort();
 const heldout=new Set(symbols.filter(s=>
   crypto.createHash('sha256').update('penny-v07:'+s).digest()[0]<55));
 if(heldout.size<2&&symbols.length>=6)for(const s of symbols.slice(-2))heldout.add(s);
 const trainingUniverse=valid.filter(x=>!heldout.has(x.pool));
 const sorted=trainingUniverse.map(x=>x.at).sort((a,b)=>a-b);
 const cutoff=sorted[Math.floor(sorted.length*.75)]??0;
 const embargo=22*86400; // 10-session target may span ~14–22 days
 return {
  train:trainingUniverse.filter(x=>x.at+embargo<cutoff),
  later:trainingUniverse.filter(x=>x.at>=cutoff),
  unseen:valid.filter(x=>heldout.has(x.pool)&&x.at>=cutoff),
  cutoff,symbols:symbols.length,trainingSymbols:new Set(trainingUniverse.map(x=>x.pool)).size,
  unseenSymbols:heldout.size,embargoSeconds:embargo
 };
}
export function fitEquityResearch(rows){
 if(rows.length<1)throw Error('No equity training rows');
 const d=EQUITY_FEATURES.length;
 const mu=Array.from({length:d},(_,i)=>mean(rows.map(x=>x.features[i])));
 const scale=Array.from({length:d},(_,i)=>Math.max(.005,
   Math.sqrt(mean(rows.map(x=>(x.features[i]-mu[i])**2)))));
 let weights=Array(d).fill(0);
 const rate=clamp(mean(rows.map(x=>x.label)),.01,.99);
 let bias=Math.log(rate/(1-rate));
 const inputs=rows.map(r=>r.features.map((f,i)=>(f-mu[i])/scale[i]));
 for(let epoch=0;epoch<280;epoch++){
   const grad=Array(d).fill(0);let gb=0;
   for(let k=0;k<rows.length;k++){
     const e=sigmoid(bias+dot(weights,inputs[k]))-rows[k].label;
     gb+=e;
     for(let i=0;i<d;i++)grad[i]+=e*inputs[k][i];
   }
   const alpha=.10/(1+epoch/180);
   bias-=alpha*gb/rows.length;
   for(let i=0;i<d;i++)weights[i]-=alpha*(grad[i]/rows.length+.035*weights[i]);
 }
 return {version:'memepulse-penny-logit-v1',assetClass:'US_LISTED_PENNY_STOCK',
  featureNames:EQUITY_FEATURES,means:mu,scales:scale,weights,bias,
  threshold:.72,tradingDays:EQUITY_HORIZON,estimatedCostPct:EQUITY_ESTIMATED_COST_PCT,
  trainingExamples:rows.length,productionAllowed:false};
}
export function equityProbability(model,x){
 if(!model||model.version!=='memepulse-penny-logit-v1'||
    x?.length!==EQUITY_FEATURES.length||x.some(v=>!Number.isFinite(v))||
    model.featureNames?.join()!==EQUITY_FEATURES.join())return null;
 const z=x.map((a,i)=>(a-model.means[i])/model.scales[i]);
 const probability=sigmoid(model.bias+dot(model.weights,z));
 return Number.isFinite(probability)?probability:null;
}
export function assessEquity(model,rows){
 const cases=rows.map(x=>({...x,p:equityProbability(model,x.features)}))
   .filter(x=>Number.isFinite(x.p));
 const selected=cases.filter(x=>x.p>=model.threshold);
 const win=selected.filter(x=>x.label===1).length;
 return {examples:cases.length,signals:selected.length,
   precision:selected.length?win/selected.length:null,
   averageNetPct:selected.length?mean(selected.map(x=>x.netPct)):null,
   alwaysBuyAverageNetPct:cases.length?mean(cases.map(x=>x.netPct)):null,
   brier:cases.length?mean(cases.map(x=>(x.p-x.label)**2)):null};
}
export function trainEquityResearch(rows,provenance={}){
 const parts=splitEquityExamples(rows);
 const counts={train:parts.train.length,later:parts.later.length,unseen:parts.unseen.length,
  symbols:parts.symbols,trainingSymbols:parts.trainingSymbols,
  unseenSymbols:parts.unseenSymbols,embargoSeconds:parts.embargoSeconds};
 if(parts.train.length<75)return {status:'INSUFFICIENT_DATA',
  model:null,productionAllowed:false,counts,provenance,
  reason:'At least 75 historical penny-stock examples needed'};
 const model=fitEquityResearch(parts.train);
 const later=assessEquity(model,parts.later);
 const unseen=assessEquity(model,parts.unseen);
 const sufficient=parts.train.length>=300&&parts.trainingSymbols>=10&&parts.unseenSymbols>=3&&
   later.examples>=90&&unseen.examples>=70&&later.signals>=20&&unseen.signals>=15;
 const passes=sufficient&&later.precision>=.63&&unseen.precision>=.60&&
   later.averageNetPct>0&&unseen.averageNetPct>0&&
   later.averageNetPct>later.alwaysBuyAverageNetPct&&
   unseen.averageNetPct>unseen.alwaysBuyAverageNetPct;
 return {status:passes?'RESEARCH_GATE_PASSED':'SHADOW_ONLY',
   productionAllowed:false,model,counts,provenance,
   metrics:{training:assessEquity(model,parts.train),later,unseen},
   warnings:['No Penny Stocks broker, custody, or cash withdrawal integration',
   'Only stocks with available data and sufficient recorded dollar volume are covered',
   'Delisted and bankrupt issuers may be omitted (survivorship bias)',
   'Corporate actions, splits, fees, spreads and liquidity are not fully modelled',
   'No OTC/PK stock support, no short-selling, no real securities orders',
   'An apparent gain is NOT a claim of executable or future profitability']};
}
