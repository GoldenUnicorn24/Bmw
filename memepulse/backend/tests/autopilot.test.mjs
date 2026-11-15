import test from 'node:test';
import assert from 'node:assert/strict';
import {appendSnapshot, confirmHistory, sizeBuy, dailyRemaining, strategyHealth, evaluateExit} from '../autopilot.mjs';
const now=Date.now();
const mk=(at,price=.01,liquidity=180000)=>({at,price,liquidity,volume1h:200000,change1h:5});
test('requires three distinct spaced snapshots; duplicate polls do not count',()=>{
 const t={priceUsd:.01,liquidityUsd:180000,volume1h:120000,change1h:5};
 let h=[];h=appendSnapshot(h,t,now-180000);
 h=appendSnapshot(h,t,now-150000);assert.equal(h.length,1);
 h=appendSnapshot(h,t,now-90000);h=appendSnapshot(h,t,now);
 assert.equal(h.length,3);assert.equal(confirmHistory(h,now).passed,true);
 assert.equal(confirmHistory(h.slice(-2),now).passed,false);
});
test('blocks abrupt price reversal, liquidity drain and pump spikes',()=>{
 const ok=[mk(now-240000,.01),mk(now-120000,.0102),mk(now,.0103)];
 assert.equal(confirmHistory(ok,now).passed,true);
 assert.equal(confirmHistory([mk(now-240000,.01),mk(now-120000,.012),mk(now,.0101)],now).passed,false);
 assert.equal(confirmHistory([mk(now-240000,.01,200000),mk(now-120000,.0101,200000),mk(now,.0102,100000)],now).passed,false);
 assert.equal(confirmHistory([mk(now-240000,.01),mk(now-120000,.012),mk(now,.014)],now).passed,false);
});
test('position size respects wallet reserve, daily budget, trade cap, capital percent',()=>{
 const base={walletLamports:2000000000n,reserveLamports:20000000n,maxTradeLamports:150000000n,
  dailyRemainingLamports:70000000n};
 const v=sizeBuy(base);assert.ok(v.amountLamports>0n);assert.ok(v.amountLamports<=40000000n);
 assert.ok(v.amountLamports<=70000000n);
 assert.equal(sizeBuy({...base,walletLamports:20000000n}).amountLamports,0n);
 assert.equal(sizeBuy({...base,dailyRemainingLamports:0n}).amountLamports,0n);
});
test('daily budget includes pending and unknown buys',()=>{
 const j=[{side:'BUY',status:'UNKNOWN',at:now,amountLamports:'3000000'},
 {side:'BUY',status:'SUCCESS',at:now,amountLamports:'2000000'},
 {side:'SELL',status:'SUCCESS',at:now,amountLamports:'2000000'}];
 assert.equal(dailyRemaining(j,7000000n,now),2000000n);
 assert.equal(dailyRemaining(j,5000000n,now),0n);
});
test('forced exit guards and peak-based trailing stop',()=>{
 const input={entryPriceUsd:.01,markPriceUsd:.012,peakPriceUsd:.015,buyAt:now-60000,now};
 assert.equal(evaluateExit(input).reason,'TRAILING_STOP');
 assert.equal(evaluateExit({...input,markPriceUsd:.008}).reason,'HARD_STOP');
 assert.equal(evaluateExit({...input,markPriceUsd:.013}).reason,'TAKE_PROFIT');
 assert.equal(evaluateExit({...input,markPriceUsd:.0105,peakPriceUsd:.011,buyAt:now-140*60000}).reason,'MAX_HOLD_TIME');
 assert.equal(evaluateExit({...input,markPriceUsd:NaN}).sell,false);
});
test('performance watchdog halts entries after 20 realized losing trades',()=>{
 const losing=Array.from({length:20},()=>({pnlSol:-.001}));
 assert.equal(strategyHealth(losing).halt,true);
 assert.equal(strategyHealth(losing.slice(0,12)).halt,false);
 assert.equal(strategyHealth(Array.from({length:20},()=>({pnlSol:.001}))).halt,false);
});
