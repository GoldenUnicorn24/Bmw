import test from 'node:test';
import assert from 'node:assert/strict';
import {simulateMemeExit} from '../research/memeTargets.mjs';
import {parseCandles,makeExamples,HORIZON_CANDLES,FRICTION_PCT} from '../research/features.mjs';
const initial=1700000100;
const candles=(n=80)=>{
 let p=.008;
 return Array.from({length:n},(_,i)=>{
  const open=p;p*=1.002;
  return {time:initial+i*300,open,high:p*1.001,low:open*.999,close:p,volume:32000};
 });
};
test('meme training horizon is max two hours with conservative fee assumption',()=>{
 assert.equal(HORIZON_CANDLES,24);
 assert.equal(FRICTION_PCT,3);
 const c=candles(90);
 const out=simulateMemeExit(c,25);
 assert.equal(out.reason,'MAX_HOLD');
 assert.equal(out.durationMinutes,120);
 assert.ok(Math.abs(out.netPct-(out.grossPct-3))<1e-6);
});
test('intrabar ambiguity uses STOP before take-profit; down-gap executes below stop',()=>{
 const c=candles();
 const entry=c[26].close;
 c[27]={...c[27],open:entry*.85,low:entry*.70,high:entry*1.30,close:entry*.9};
 const a=simulateMemeExit(c,26);
 assert.equal(a.reason,'STOP_LOSS');
 assert.ok(a.grossPct<=-15);
 assert.ok(a.netPct<=-18);
});
test('future discontinuity never becomes a made-up profit or interpolated candle',()=>{
 const c=candles();
 c[40]={...c[40],time:c[40].time+700};
 assert.equal(simulateMemeExit(c,25),null);
 const rows=c.map(x=>[x.time,x.open,x.high,x.low,x.close,x.volume]);
 const examples=makeExamples('mint',rows,{stride:1});
 assert.ok(examples.every(x=>x.at!==c[25].time));
});
test('training cannot use future targets as current entry features',()=>{
 const c=candles(120).map(x=>[x.time,x.open,x.high,x.low,x.close,x.volume]);
 const base=makeExamples('mint',c,{stride:1});
 const changed=c.map((row,i)=>i>26?[...row.slice(0,4),row[4]*1.1,row[5]]:row);
 const after=makeExamples('mint',changed,{stride:1});
 const x=base.find(r=>r.at===c[25][0]);
 const y=after.find(r=>r.at===c[25][0]);
 assert.ok(x&&y);
 assert.deepEqual(x.features,y.features);
});
