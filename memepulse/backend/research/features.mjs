// OHLCV feature engineering; each vector uses candles up to and including index i.
// Target uses future candles only in the offline learner, never in inference.
// Features are dimensionless so pools with different quote denominations can be compared.
const numeric = x => Number.isFinite(Number(x)) ? Number(x) : NaN;
const clip = (x,a,b) => Math.max(a,Math.min(b,x));
const lnRatio = (a,b) => Math.log(a/b);

export const FEATURE_NAMES = [
  'return_5m','return_15m','return_60m','return_120m',
  'range_30m','candle_body','atr_60m',
  'volume_recent_vs_prior','green_candle_ratio','momentum_stability'
];
export const LOOKBACK_CANDLES = 25;
export const HORIZON_CANDLES = 12; // 60 minutes at 5-minute resolution
export const FRICTION_PCT = 2.0; // highly uncertain placeholder for two-sided fee/slippage

export function parseCandles(rows) {
  if (!Array.isArray(rows)) return [];
  const clean = [];
  const seen = new Set();
  for (const raw of rows) {
    if (!Array.isArray(raw) || raw.length < 6) continue;
    const [time,open,high,low,close,volume] = raw.map(numeric);
    if (![time,open,high,low,close,volume].every(Number.isFinite)) continue;
    if (time < 1_600_000_000 || open<=0||high<=0||low<=0||close<=0||volume<0) continue;
    if (low>Math.min(open,close) || high<Math.max(open,close)||low>high) continue;
    const ts=Math.floor(time);
    if(seen.has(ts))continue;
    seen.add(ts); clean.push({time:ts,open,high,low,close,volume});
  }
  clean.sort((a,b)=>a.time-b.time);
  // Price sources sometimes skip empty intervals; do not silently interpolate.
  return clean;
}
export function featureVector(c, i) {
  if (!Array.isArray(c) || i<LOOKBACK_CANDLES || i>=c.length) return null;
  const now=c[i],older=c[i-24],prev=c[i-1];
  if (!now||!older||!prev || now.time-older.time !== 24*300) return null;
  const slice=c.slice(i-23,i+1);
  if(slice.length!==24 || slice.some((x,j)=>j && x.time-slice[j-1].time!==300))return null;
  const prices=slice.map(x=>x.close);
  const high6=Math.max(...slice.slice(-6).map(x=>x.high));
  const low6=Math.min(...slice.slice(-6).map(x=>x.low));
  const v1=slice.slice(-6).reduce((a,x)=>a+x.volume,0);
  const v0=slice.slice(-12,-6).reduce((a,x)=>a+x.volume,0);
  const returns=slice.slice(1).map((x,j)=>lnRatio(x.close,slice[j].close));
  const absChange=returns.reduce((s,x)=>s+Math.abs(x),0)/returns.length;
  const direction=Math.abs(returns.reduce((s,x)=>s+x,0))/(returns.reduce((s,x)=>s+Math.abs(x),0)+1e-9);
  const f=[
    lnRatio(now.close,prev.close),lnRatio(now.close,c[i-3].close),
    lnRatio(now.close,c[i-12].close),lnRatio(now.close,older.close),
    lnRatio(high6,low6), lnRatio(now.close,now.open),
    slice.slice(-12).reduce((s,x)=>s+Math.log(x.high/x.low),0)/12,
    Math.log((v1+1)/(v0+1)),
    returns.filter(x=>x>0).length/returns.length,
    direction
  ];
  if(f.some(x=>!Number.isFinite(x)))return null;
  return f.map((x,index)=>clip(x,index===7?-3:-1,index===7?3:1));
}
export function makeExamples(pool,candles,{stride=12,horizon=HORIZON_CANDLES,frictionPct=FRICTION_PCT}={}){
  const c=parseCandles(candles);
  const out=[];
  for(let i=LOOKBACK_CANDLES;i+horizon<c.length;i+=stride){
    const vector=featureVector(c,i);
    if(!vector)continue;
    // Require a future candle at exactly the requested interval, not a distant gap.
    const future=c[i+horizon];
    if(future.time-c[i].time!==horizon*300)continue;
    const grossPct=100*(future.close/c[i].close-1);
    if(!Number.isFinite(grossPct)||Math.abs(grossPct)>1000)continue;
    out.push({
      pool,at:c[i].time,features:vector,
      label:Number(grossPct>frictionPct),
      grossPct:Number(grossPct.toFixed(4)),
      netPct:Number((grossPct-frictionPct).toFixed(4)),
      horizonMinutes:horizon*5
    });
  }
  return out;
}
