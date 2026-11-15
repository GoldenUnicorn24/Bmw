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
      samples:Number(x.samples ?? x.examples)||0,signals:Number(x.signals)||0,
      precision:Number.isFinite(x.precision)?x.precision:null,
      meanNetPct:Number.isFinite(x.meanNetPct)?x.meanNetPct:Number.isFinite(x.averageNetPct)?x.averageNetPct:null,
      alwaysBuyMeanNetPct:Number.isFinite(x.alwaysBuyMeanNetPct)?x.alwaysBuyMeanNetPct:Number.isFinite(x.alwaysBuyAverageNetPct)?x.alwaysBuyAverageNetPct:null,
      brierScore:Number.isFinite(x.brierScore)?x.brierScore:Number.isFinite(x.brier)?x.brier:null
    }:null;
    return {status:String(r.status||'INVALID_REPORT'),liveInfluence:approved,
      sampleCount:Number(r.provenance?.examples ?? r.provenance?.qualifyingExamples ?? r.provenance?.trainingExamples)||0,
      assetClass:String(r.assetClass||r.provenance?.assetClass||'NOT_CLASSIFIED'),
      source:String(r.provenance?.source||r.provenance?.priceProvider||'unknown'),
      asOf:String(r.provenance?.collectedAt||r.provenance?.selectedAt||''),
      counts:r.counts||null,
      metrics:{chronological:metric(r.metrics?.chronological),
        unseenPools:metric(r.metrics?.unseenPools ?? r.metrics?.unseen),
        later:metric(r.metrics?.later)},
      warning:'Research only; no model may authorize financial transactions'};
  }catch(e){return {...unknown,status:'REPORT_UNAVAILABLE',reason:String(e.message).slice(0,90)};}
}
