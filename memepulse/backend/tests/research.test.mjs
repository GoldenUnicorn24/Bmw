import test from 'node:test';
import assert from 'node:assert/strict';
import {parseCandles,featureVector,makeExamples,HORIZON_CANDLES} from '../research/features.mjs';
import {trainResearch,fitLogistic,predict,evaluate,splitExamples} from '../research/learner.mjs';

function candles(count=300,{start=1700000100,jump=300,drift=.004}={}){
  const out=[];let price=1;
  for(let i=0;i<count;i++){
    const open=price;price*=1+drift+Math.sin(i/7)*.0015;
    out.push([start+i*jump,open,Math.max(open,price)*1.002,Math.min(open,price)*.998,
      price,20000+i*4]);
  }
  return out;
}
test('OHLCV parse sorts, validates and removes duplicate candles',()=>{
  const rows=candles(35);
  const raw=[...rows.reverse(),rows[0],[100,0,0,0,0,0],['oops',1,2,1,1,1]];
  const result=parseCandles(raw);
  assert.equal(result.length,35);
  assert.ok(result[0].time<result.at(-1).time);
});
test('feature vectors use only earlier observations, never future target data',()=>{
  const c=parseCandles(candles());
  const before=featureVector(c,60);
  const altered=c.map((x,i)=>i>60?{...x,close:x.close*100}:x);
  assert.deepEqual(featureVector(altered,60),before);
  assert.equal(featureVector(c,24),null);
  assert.equal(featureVector(c,60)?.length,10);
  const gapped=c.map((x,i)=>i===50?{...x,time:x.time+300}:x);
  assert.equal(featureVector(gapped,61),null);
});
test('labels are strictly 60-minute future returns with cost estimate',()=>{
  const c=candles();
  const x=makeExamples('pool',c);
  assert.ok(x.length>5);
  assert.equal(x[0].horizonMinutes,120);
  assert.equal(x[0].at,c[25][0]);
  assert.ok(Math.abs(x[0].netPct-(x[0].grossPct-3))<.0001);
  assert.ok(x.every(y=>[0,1].includes(y.label)&&y.features.length===10));
});
test('research models cannot automatically authorize real trades',()=>{
  const pools=Array.from({length:14},(_,i)=>'Pool'+i);
  const data=pools.flatMap((pool,i)=>makeExamples(pool,candles(500,{
    start:1700000100,drift:.0008+i*.00013
  })));
  const split=splitExamples(data);
  assert.ok(split.train.length>0);
  assert.ok(split.chronologicalTest.length>0);
  assert.ok(split.unseenPoolTest.length>0);
  assert.ok(split.unseenPools>=2);
  assert.ok(split.train.every(x=>x.at+HORIZON_CANDLES*300<split.cutoff));
  assert.ok(split.chronologicalTest.every(x=>x.at>=split.cutoff));
  const r=trainResearch(data,{fixture:'synthetic unit tests only'});
  assert.equal(r.productionAllowed,false);
  assert.equal(r.model.productionAllowed,false);
  assert.ok(['SHADOW_ONLY','RESEARCH_GATE_PASSED'].includes(r.status));
  assert.ok(r.metrics.chronological.samples>0);
  assert.ok(r.metrics.unseenPools.samples>0);
  assert.ok(r.model.weights.every(Number.isFinite));
  const p=predict(r.model,data[0].features);
  assert.ok(p>=0&&p<=1);
  assert.equal(predict(r.model,[1,2]),null);
});
test('too few samples produce insufficient status and no deployable model',()=>{
  const r=trainResearch(makeExamples('one',candles(100)));
  assert.equal(r.status,'INSUFFICIENT_DATA');
  assert.equal(r.model,undefined);
});
