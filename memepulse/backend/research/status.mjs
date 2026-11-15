// The experimental learner is strictly segregated from real swap authorization.
// This module READS offline reports but cannot turn any model into a live trade gate.
// Deliberately no prediction hook in executeOrder, prepareOrder or agentCycle.
import fs from 'node:fs';

export function researchStatus(file){
  const unknown={status:'NO_TRAINING_REPORT',liveInfluence:false,
    reason:'No validated research report installed on this server'};
  if(!file)return unknown;
  try{
    const st=fs.statSync(file);
    if(!st.isFile()||st.size>2_000_000)return {...unknown,status:'INVALID_REPORT'};
    const r=JSON.parse(fs.readFileSync(file,'utf8'));
    const approved=false;
    const metric=(x)=>x&&typeof x==='object'?{
      samples:Number(x.samples)||0,signals:Number(x.signals)||0,
      precision:Number.isFinite(x.precision)?x.precision:null,
      meanNetPct:Number.isFinite(x.meanNetPct)?x.meanNetPct:null,
      alwaysBuyMeanNetPct:Number.isFinite(x.alwaysBuyMeanNetPct)?x.alwaysBuyMeanNetPct:null,
      brierScore:Number.isFinite(x.brierScore)?x.brierScore:null
    }:null;
    return {status:String(r.status||'INVALID_REPORT'),liveInfluence:approved,
      sampleCount:Number(r.provenance?.examples)||0,
      source:String(r.provenance?.source||'unknown'),
      asOf:String(r.provenance?.collectedAt||''),
      counts:r.counts||null,
      metrics:{chronological:metric(r.metrics?.chronological),
        unseenPools:metric(r.metrics?.unseenPools)},
      warning:'Research only; no model may authorize financial transactions'};
  }catch(e){return {...unknown,status:'REPORT_UNAVAILABLE',reason:String(e.message).slice(0,90)};}
}
