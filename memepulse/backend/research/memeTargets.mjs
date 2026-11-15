// Conservative risk-aware research labels from FUTURE 5-minute OHLCV candles.
// This is an offline approximation only, NOT a fill simulator or executable quote.
// Stop is evaluated first if a candle touches both stop and take-profit levels.
export function simulateMemeExit(candles,entryIndex,{
  maxCandles=24,stopLossPct=12,takeProfitPct=25,
  frictionPct=3
}={}){
  const entry=candles?.[entryIndex];
  if(!entry||!Number.isFinite(entry.close)||entry.close<=0) return null;
  if(!Number.isInteger(maxCandles)||maxCandles<1||entryIndex+maxCandles>=candles.length)
    return null;
  const stop=entry.close*(1-stopLossPct/100);
  const take=entry.close*(1+takeProfitPct/100);
  let price=null,reason='MAX_HOLD',duration=0;
  for(let i=1;i<=maxCandles;i++){
    const candle=candles[entryIndex+i];
    if(!candle||candle.time-entry.time!==i*300 ||
      ![candle.open,candle.low,candle.high,candle.close].every(Number.isFinite))
      return null; // no interpolation over missing periods
    duration=i*5;
    if(candle.low<=stop){
      reason='STOP_LOSS';
      price=Math.min(stop,candle.open); // gaps down may execute below the stop
      break;
    }
    if(candle.high>=take){
      reason='TAKE_PROFIT';
      price=take; // do not assume a more favourable high fill
      break;
    }
    if(i===maxCandles)price=candle.close;
  }
  if(!Number.isFinite(price)||price<=0)return null;
  const grossPct=(price/entry.close-1)*100;
  const netPct=grossPct-frictionPct;
  return {reason,durationMinutes:duration,
    exitPrice:price,grossPct:Number(grossPct.toFixed(4)),
    netPct:Number(netPct.toFixed(4)),label:Number(netPct>0),
    assumedRoundTripCostPct:frictionPct};
}
