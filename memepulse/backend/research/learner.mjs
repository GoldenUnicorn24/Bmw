// Lightweight, dependency-free logistic learner for RESEARCH ONLY.
// Fit exclusively to past observations, evaluate on future times AND unseen pools.
// No gradient updates from validation/test labels; fixed probability threshold.
import crypto from 'node:crypto';
import {FEATURE_NAMES, FRICTION_PCT, HORIZON_CANDLES} from './features.mjs';

const sigmoid=x=>x>=0?1/(1+Math.exp(-Math.min(x,40))):Math.exp(Math.max(x,-40))/(1+Math.exp(Math.max(x,-40)));
const finite=x=>Number.isFinite(x);
const mean=a=>a.length?a.reduce((s,n)=>s+n,0)/a.length:0;
const std=(a,m)=>Math.sqrt(mean(a.map(x=>(x-m)**2)));
const dot=(a,b)=>a.reduce((s,x,i)=>s+x*b[i],0);
const probThreshold=.70;

export function splitExamples(rows){
  const clean=rows.filter(x=>x&&typeof x.pool==='string'&&x.pool.length>0&&
    Number.isFinite(x.at)&&[0,1].includes(x.label)&&
    x.features?.length===FEATURE_NAMES.length&&x.features.every(finite)&&finite(x.netPct));
  const pools=[...new Set(clean.map(x=>x.pool))].sort();
  const heldout=new Set(pools.filter(p=>{
    // Deterministic split, no choice based on returns or labels.
    const hash=crypto.createHash('sha256').update('memepulse-v06:'+p).digest();
    return hash[0]<52;
  }));
  // Ensure a useful disjoint pool test even for a small collection.
  if(heldout.size<2&&pools.length>=8){
    for(const p of pools.slice(-2))heldout.add(p);
  }
  const trainPools=clean.filter(x=>!heldout.has(x.pool));
  const times=trainPools.map(x=>x.at).sort((a,b)=>a-b);
  const cutoff=times[Math.floor(times.length*.72)]??0;
  // Labels cover 60m into the future, so embargo 60m prevents overlap.
  const embargo=HORIZON_CANDLES*300;
  return {
    train:trainPools.filter(x=>x.at+embargo<cutoff),
    chronologicalTest:trainPools.filter(x=>x.at>=cutoff),
    unseenPoolTest:clean.filter(x=>heldout.has(x.pool)&&x.at>=cutoff),
    pools:pools.length, trainPools:new Set(trainPools.map(x=>x.pool)).size,
    unseenPools:heldout.size,cutoff
  };
}
export function fitLogistic(rows,{epochs=300,learningRate=.12,l2=.025}={}){
  if(!rows.length)throw Error('Training data empty');
  const d=FEATURE_NAMES.length;
  const mu=Array.from({length:d},(_,k)=>mean(rows.map(x=>x.features[k])));
  const sd=Array.from({length:d},(_,k)=>Math.max(.001,std(rows.map(x=>x.features[k]),mu[k])));
  const vectors=rows.map(x=>x.features.map((n,k)=>(n-mu[k])/sd[k]));
  const avg=mean(rows.map(x=>x.label));
  let bias=Math.log(Math.max(.005,Math.min(.995,avg))/(1-Math.max(.005,Math.min(.995,avg))));
  let weights=Array(d).fill(0);
  for(let ep=0;ep<epochs;ep++){
    const grads=Array(d).fill(0);let gb=0;
    for(let i=0;i<rows.length;i++){
      const error=sigmoid(bias+dot(weights,vectors[i]))-rows[i].label;
      gb+=error;
      for(let k=0;k<d;k++)grads[k]+=error*vectors[i][k];
    }
    const alpha=learningRate/(1+ep/200);
    bias-=alpha*gb/rows.length;
    for(let k=0;k<d;k++){
      weights[k]-=alpha*(grads[k]/rows.length+l2*weights[k]);
      if(!finite(weights[k]))throw Error('Numerical training failure');
    }
  }
  return {version:'memepulse-ohlcv-logit-v1',features:FEATURE_NAMES,
    means:mu,scales:sd,weights,bias,threshold:probThreshold,
    trainedExamples:rows.length,positiveLabelRate:avg,frictionPct:FRICTION_PCT,
    horizonMinutes:HORIZON_CANDLES*5, trainedAt:new Date().toISOString()};
}
export function predict(model,features){
  if(!model || !Array.isArray(features) || features.length!==FEATURE_NAMES.length ||
     features.some(x=>!finite(x)) || model.version!=='memepulse-ohlcv-logit-v1'||
     model.weights?.length!==FEATURE_NAMES.length ||
     model.features?.join('|')!==FEATURE_NAMES.join('|'))return null;
  const normalized=features.map((v,i)=>(v-model.means[i])/model.scales[i]);
  const value=sigmoid(model.bias+dot(model.weights,normalized));
  return finite(value)?value:null;
}
export function evaluate(model,rows){
  const items=rows.map(r=>({label:r.label,net:r.netPct,prob:predict(model,r.features)})).filter(x=>finite(x.prob));
  const selected=items.filter(x=>x.prob>=probThreshold);
  const wins=selected.filter(x=>x.label===1);
  const avgNet=mean(selected.map(x=>x.net));
  const hitRate=selected.length?wins.length/selected.length:null;
  const brier=mean(items.map(x=>(x.prob-x.label)**2));
  return {samples:items.length,signals:selected.length,signalRate:items.length?selected.length/items.length:null,
    labelRate:items.length?mean(items.map(x=>x.label)):null,
    precision:hitRate,winRateAtNet2Pct:hitRate,
    meanNetPct: selected.length?avgNet:null,brierScore:items.length?brier:null,
    // Not portfolio returns. These overlap across pools and ignore liquidity/gas/rugs.
    alwaysBuyMeanNetPct:items.length?mean(items.map(x=>x.net)):null};
}
export function trainResearch(examples, provenance={}){
  const parts=splitExamples(examples);
  if(parts.train.length<60) return {status:'INSUFFICIENT_DATA',
    reason:'Too few chronological training observations',counts:counts(parts),provenance};
  const model=fitLogistic(parts.train);
  const chronological=evaluate(model,parts.chronologicalTest);
  const unseenPools=evaluate(model,parts.unseenPoolTest);
  // Strict fixed gates. Failure keeps the model in research-only shadow mode.
  const approved=parts.train.length>=350&&parts.trainPools>=8&&
    chronological.samples>=80&&unseenPools.samples>=60&&parts.unseenPools>=2&&
    chronological.signals>=15&&unseenPools.signals>=12&&
    chronological.precision>=.60&&unseenPools.precision>=.58&&
    chronological.meanNetPct>0&&unseenPools.meanNetPct>0&&
    chronological.meanNetPct>chronological.alwaysBuyMeanNetPct&&
    unseenPools.meanNetPct>unseenPools.alwaysBuyMeanNetPct;
  return {status: approved?'RESEARCH_GATE_PASSED':'SHADOW_ONLY',
    productionAllowed:false, // Training never grants a live trading permission.
    reason:approved?'Research validation passed; separate security audit still mandatory':
      'Backtest or sample criteria insufficient; not permitted to influence live orders',
    model:{...model,productionAllowed:false},
    counts:counts(parts),
    metrics:{training:evaluate(model,parts.train),chronological,unseenPools},
    provenance,
    warnings:[
      'Trading costs are estimates, not executable historical Jupiter quotes',
      'Trending-pool selection has survivorship and promotional-selection bias',
      'OHLCV excludes intrabar execution path, spread, network gas, rug exits and honeypots',
      'Model was not verified with real trades and cannot authorize live swaps'
    ]};
}
function counts(s){return {train:s.train.length,chronologicalTest:s.chronologicalTest.length,
  unseenPoolTest:s.unseenPoolTest.length,totalPools:s.pools,
  trainingPools:s.trainPools,unseenPools:s.unseenPools,cutoffUnix:s.cutoff};}
