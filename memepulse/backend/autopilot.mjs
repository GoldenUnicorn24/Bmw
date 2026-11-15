// MemePulse Autopilot 2 — independent confirmation and position sizing.
// No ML/profit guarantee. Rolling market *snapshots* are not full OHLCV candles.
// This module only approves/denies candidates, never signs transactions.
const toSafe = x => Number.isFinite(Number(x)) ? Number(x) : NaN;
const isFinitePositive = x => Number.isFinite(x) && x > 0;
const clamp = (n,a,b) => Math.min(b,Math.max(a,n));

export function appendSnapshot(history = [], market, now = Date.now()) {
  if (!market || !isFinitePositive(toSafe(market.priceUsd)) ||
      !isFinitePositive(toSafe(market.liquidityUsd))) return history.slice(-24);
  const current = { at: now, price: toSafe(market.priceUsd), liquidity: toSafe(market.liquidityUsd),
    volume1h: toSafe(market.volume1h), change1h: toSafe(market.change1h) };
  const kept = history.filter(p => Number.isFinite(p.at) && now - p.at <= 90*60*1000).slice(-24);
  // Rate limiting prevents back-to-back duplicate HTTP reads from faking price confirmation.
  if (kept.length && now - kept.at(-1).at < 55_000) return kept;
  return [...kept, current].slice(-24);
}

export function confirmHistory(history = [], now = Date.now()) {
  const window = history.filter(p => now - p.at < 15*60*1000 &&
      now >= p.at && isFinitePositive(p.price) && isFinitePositive(p.liquidity));
  if (window.length < 3) return { passed:false, code:'HISTORY_NOT_READY',
    reason:'Mindestens drei frische Beobachtungen erforderlich', sampleCount: window.length };
  const selected = window.slice(-6);
  if (selected.at(-1).at - selected[0].at < 120_000)
    return { passed:false, code:'HISTORY_TOO_SHORT', reason:'Marktbeobachtung zu kurz', sampleCount:selected.length };
  if (now - selected.at(-1).at > 110_000) return { passed:false, code:'HISTORY_STALE',
    reason:'Letzte Kursprobe zu alt', sampleCount:selected.length };
  const prices = selected.map(p=>p.price);
  const liq = selected.map(p=>p.liquidity);
  const minLiq=Math.min(...liq),maxLiq=Math.max(...liq);
  const peak=Math.max(...prices);
  const low=Math.min(...prices);
  const swingPct=100*(peak/low-1);
  const drawdownPct=100*(1-prices.at(-1)/peak);
  const liquidityFade=1 - liq.at(-1)/maxLiq;
  const changePct=100*(prices.at(-1)/prices[0]-1);
  const recent=100*(prices.at(-1)/prices.at(-2)-1);
  const blockers=[];
  if (minLiq < 75_000 || liquidityFade > .20) blockers.push('LIQUIDITY_FADE');
  if (swingPct > 16) blockers.push('UNSTABLE_PRICE');
  if (drawdownPct > 6) blockers.push('FAST_DRAWDOWN');
  if (recent < -4) blockers.push('MOMENTUM_REVERSAL');
  if (changePct < -2) blockers.push('CONFIRMATION_NEGATIVE');
  if (changePct > 13) blockers.push('PUMP_EXHAUSTION');
  return { passed:blockers.length===0, code:blockers.length?blockers.join(','):'CONFIRMED',
    reason:blockers.length?'Historischer Filter blockiert: '+blockers.join(', '):'Kurs über mehrere getrennte Messungen stabil bestätigt',
    sampleCount:selected.length, changePct:Number(changePct.toFixed(2)),
    drawdownPct:Number(drawdownPct.toFixed(2)), maxLiquidityFadePct:Number((liquidityFade*100).toFixed(2)) };
}

function bigint(x){ try { return BigInt(x); } catch { return 0n; } }
export function sizeBuy({walletLamports, reserveLamports, maxTradeLamports, dailyRemainingLamports,
  riskBudgetBps=50, stopLossPct=12, minOrderLamports=300_000n}) {
  const wallet=bigint(walletLamports), reserve=bigint(reserveLamports);
  const remaining=wallet-reserve;
  if(remaining<=0n)return {amountLamports:0n,reason:'NO_AVAILABLE_SOL'};
  const risk=clamp(Math.floor(Number(riskBudgetBps)),1,100);
  const stop=clamp(Number(stopLossPct),3,50);
  // 50bps wallet-equity loss budget at 12% stop implies <=4.17% wallet position.
  // Limit an individual buy further to <=2% liquid balance, regardless of risk budget.
  const stopBased=BigInt(Math.floor(Number(wallet)*risk/10000/(stop/100)));
  const liquidBased=remaining*2n/100n;
  const amount=[stopBased,liquidBased,remaining,bigint(maxTradeLamports),bigint(dailyRemainingLamports)]
    .reduce((min,x)=>x<min?x:min);
  if(amount<bigint(minOrderLamports))return {amountLamports:0n,reason:'ORDER_BELOW_MINIMUM'};
  return {amountLamports:amount,reason:'BUDGET_OK'};
}

export function dailyRemaining(journal, maxDailyLamports, now=Date.now()){
  const start=new Date(now);start.setUTCHours(0,0,0,0);
  const spent=journal.filter(e=>e.side==='BUY'&&e.at>=start.getTime()&&
    ['SUBMITTED','SUCCESS','UNKNOWN'].includes(e.status))
    .reduce((n,e)=>n+bigint(e.amountLamports),0n);
  const cap=bigint(maxDailyLamports);
  return cap>spent?cap-spent:0n;
}

export function strategyHealth(closedTrades=[], minTrades=20) {
  const trades=closedTrades.filter(t=>Number.isFinite(t.pnlSol));
  if(trades.length<minTrades)return {status:'INSUFFICIENT_SAMPLE',trades:trades.length,
    winRate:null,profitFactor:null,expectancySol:null,halt:false};
  const wins=trades.filter(t=>t.pnlSol>0);
  const gains=wins.reduce((v,t)=>v+t.pnlSol,0);
  const loss=Math.abs(trades.filter(t=>t.pnlSol<0).reduce((v,t)=>v+t.pnlSol,0));
  const expectancy=trades.reduce((v,t)=>v+t.pnlSol,0)/trades.length;
  const profitFactor=loss>0?gains/loss:null;
  return {status:expectancy<0?'NEGATIVE_EXPECTANCY':'MONITORING',trades:trades.length,
    winRate:Number((wins.length/trades.length*100).toFixed(1)),
    profitFactor:profitFactor===null?null:Number(profitFactor.toFixed(2)),
    expectancySol:Number(expectancy.toFixed(8)),
    // Auto entries pause with statistically weak observed experience; exits still allowed.
    halt:trades.length>=minTrades&&expectancy<0};
}

export function evaluateExit({entryPriceUsd,markPriceUsd,peakPriceUsd,buyAt,now=Date.now(),
 stopLossPct=12,takeProfitPct=25,trailingStopPct=9,maxHoldMinutes=120}){
  const entry=toSafe(entryPriceUsd),price=toSafe(markPriceUsd),peak=toSafe(peakPriceUsd);
  if(!isFinitePositive(entry)||!isFinitePositive(price))return {sell:false,reason:'PRICE_UNAVAILABLE',changePct:null};
  const changePct=100*(price/entry-1);
  const dropFromPeakPct=isFinitePositive(peak)?100*(1-price/peak):0;
  if(changePct<=-stopLossPct)return {sell:true,reason:'HARD_STOP',changePct,dropFromPeakPct};
  if(changePct>=takeProfitPct)return {sell:true,reason:'TAKE_PROFIT',changePct,dropFromPeakPct};
  if(isFinitePositive(peak)&&peak>=entry*1.06&&dropFromPeakPct>=trailingStopPct)
    return {sell:true,reason:'TRAILING_STOP',changePct,dropFromPeakPct};
  if(Number.isFinite(buyAt)&&now-buyAt>=maxHoldMinutes*60_000)
    return {sell:true,reason:'MAX_HOLD_TIME',changePct,dropFromPeakPct};
  return {sell:false,reason:'HOLD',changePct,dropFromPeakPct};
}
