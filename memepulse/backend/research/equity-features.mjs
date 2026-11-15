// Daily US penny-share research. Separate from Solana five-minute token model.
// Definition here: USD share with closing price $0.10–$5.00 AT SIGNAL DATE.
// This is an operational screen, not a guarantee of issuer classification.
// Requiring dollar turnover reduces (does not eliminate) liquidity risk.
export const EQUITY_FEATURES=[
 'daily_return','weekly_return','monthly_return','quarter_return',
 'range_10d','range_30d','relative_volume','gap','green_fraction','volatility_20d'
];
export const EQUITY_HORIZON=10; // trading days; independent of crypto 60-minute horizon
export const EQUITY_ESTIMATED_COST_PCT=4.0; // two-sided spread, slippage + commissions assumed
const num=x=>Number(x);
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
export function parseDailyCsv(raw){
 if(typeof raw!=='string'||raw.length>5_000_000)return [];
 const lines=raw.trim().split(/\r?\n/);
 if(lines[0]?.replace(/\uFEFF/g,'').trim()!=='Date,Open,High,Low,Close,Volume')return [];
 const unique=new Map();
 for(const line of lines.slice(1)){
  const a=line.trim().split(',');if(a.length!==6)continue;
  const [date,open,high,low,close,volume]=a;
  if(!/^\d{4}-\d{2}-\d{2}$/.test(date))continue;
  const ts=Date.parse(date+'T00:00:00Z')/1000;
  if(!Number.isFinite(ts)||ts<946684800)continue;
  const [o,h,l,c,v]=[open,high,low,close,volume].map(num);
  if(![o,h,l,c,v].every(Number.isFinite)||Math.min(o,h,l,c)<=0||v<0)continue;
  if(l>Math.min(o,c)||h<Math.max(o,c)||h<l)continue;
  unique.set(date,{at:ts,date,open:o,high:h,low:l,close:c,volume:v});
 }
 return [...unique.values()].sort((a,b)=>a.at-b.at);
}
const ln=(a,b)=>Math.log(a/b);
export function pennyFeatures(bars,i){
 if(!Array.isArray(bars)||i<65||i>=bars.length)return null;
 const current=bars[i],before=bars.slice(i-64,i+1);
 if(!current||before.length!==65||before.some(x=>!Number.isFinite(x.close)||x.close<=0))return null;
 // No gaps beyond ten calendar days — holidays/weekends are normal.
 if(before.some((x,j)=>j&& (x.at-before[j-1].at<86400||x.at-before[j-1].at>10*86400)))return null;
 const recent=before.slice(-20);
 const oldVol=before.slice(-40,-20).reduce((s,x)=>s+x.volume,0);
 const newVol=recent.reduce((s,x)=>s+x.volume,0);
 const returns=recent.slice(1).map((x,j)=>ln(x.close,recent[j].close));
 const mean=returns.reduce((s,v)=>s+v,0)/returns.length;
 const volatility=Math.sqrt(returns.reduce((s,x)=>s+(x-mean)**2,0)/returns.length);
 const range=(n)=>ln(Math.max(...before.slice(-n).map(x=>x.high)),
                      Math.min(...before.slice(-n).map(x=>x.low)));
 const result=[
  ln(current.close,bars[i-1].close),
  ln(current.close,bars[i-5].close),
  ln(current.close,bars[i-20].close),
  ln(current.close,bars[i-60].close),
  range(10),range(30),
  Math.log((newVol+1)/(oldVol+1)),
  ln(current.open,bars[i-1].close),
  recent.filter(x=>x.close>x.open).length/recent.length,
  volatility
 ];
 return result.every(Number.isFinite)?result.map((v,k)=>clamp(v,k===6?-4:-1,k===6?4:1)):null;
}
export function equityExamples(symbol,bars,{
 stride=10,horizon=EQUITY_HORIZON,costPct=EQUITY_ESTIMATED_COST_PCT}={}){
 const out=[];
 if(!Array.isArray(bars))return out;
 for(let i=65;i+horizon<bars.length;i+=stride){
   const x=bars[i],future=bars[i+horizon];
   if(!x||x.close<.10||x.close>5)continue; // penny at ENTRY, not based on current value
   if(x.close*x.volume<500_000)continue; // minimum observed daily dollar turnover
   if(!future||future.at-x.at>22*86400)continue;
   const features=pennyFeatures(bars,i);
   if(!features)continue;
   const grossPct=100*(future.close/x.close-1);
   if(!Number.isFinite(grossPct)||Math.abs(grossPct)>1500)continue;
   const netPct=grossPct-costPct;
   out.push({pool:symbol,at:x.at,label:Number(netPct>0),features,
     grossPct:Number(grossPct.toFixed(4)),netPct:Number(netPct.toFixed(4)),
     horizonTradingDays:horizon,closeAtEntry:x.close,estimatedCostPct:costPct,
     assetClass:'US_LISTED_PENNY_STOCK'});
 }
 return out;
}
