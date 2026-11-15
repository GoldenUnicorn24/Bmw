// Pure position accounting based only on confirmed Jupiter fills, never advertised ROI.
const toUnits = value => {
  try { return BigInt(String(value ?? 0)); } catch { return 0n; }
};
export function confirmedPositions(journal) {
  const state = new Map();
  const realized = [];
  for (const trade of journal) {
    if (trade.status !== 'SUCCESS') continue;
    const mint = String(trade.mint || '');
    if (!mint) continue;
    const prev = state.get(mint) || { mint, tokens: 0n, costSol: 0n, entryPriceUsd: 0, buyAt: 0, sources: new Set() };
    const input = toUnits(trade.actualInputAtomic);
    const output = toUnits(trade.actualOutputAtomic);
    if (input <= 0n || output <= 0n) continue;
    if (trade.side === 'BUY') {
      const priorQty = prev.tokens;
      prev.tokens += output;
      prev.costSol += input;
      prev.entryPriceUsd = priorQty <= 0n ? Number(trade.entryPriceUsd || 0) :
        ((prev.entryPriceUsd * Number(priorQty)) + Number(trade.entryPriceUsd || 0) * Number(output)) / Number(prev.tokens);
      prev.buyAt = Number(trade.at);
      prev.sources.add(String(trade.source));
    } else if (trade.side === 'SELL' && prev.tokens > 0n) {
      const sold = input > prev.tokens ? prev.tokens : input;
      const proratedCost = prev.costSol * sold / prev.tokens;
      prev.tokens -= sold;
      prev.costSol -= proratedCost;
      realized.push({ mint, source: String(trade.source || ''), at: Number(trade.at), pnlSolAtomic: output - proratedCost,
        soldTokensAtomic: sold, realizedOutputSolAtomic: output });
      if (prev.tokens === 0n) { prev.entryPriceUsd = 0; prev.sources.clear(); }
    }
    state.set(mint, prev);
  }
  return { positions: [...state.values()].filter(p => p.tokens > 0n).map(p => ({
    mint: p.mint, tokenAtomic: p.tokens.toString(), costSolAtomic: p.costSol.toString(),
    entryPriceUsd: p.entryPriceUsd, buyAt: p.buyAt, autoOwned: p.sources.has('ENGINE')
  })), realized: realized.map(r => ({ ...r, pnlSolAtomic: r.pnlSolAtomic.toString(),
    soldTokensAtomic: r.soldTokensAtomic.toString(), realizedOutputSolAtomic: r.realizedOutputSolAtomic.toString() })) };
}
export function realizedTodayAtomic(journal, now = Date.now()) {
  const start = new Date(now);
  start.setUTCHours(0, 0, 0, 0);
  return confirmedPositions(journal).realized.filter(x => x.at >= start.getTime())
    .reduce((sum, x) => sum + BigInt(x.pnlSolAtomic), 0n);
}
export function exitSignal(entryPriceUsd, currentPriceUsd, stopLossPct = 12, takeProfitPct = 25) {
  if (!Number.isFinite(entryPriceUsd) || entryPriceUsd <= 0 ||
    !Number.isFinite(currentPriceUsd) || currentPriceUsd <= 0) return null;
  const changePct = (currentPriceUsd / entryPriceUsd - 1) * 100;
  if (changePct <= -stopLossPct) return { side: 'SELL', reason: 'STOP_LOSS', changePct };
  if (changePct >= takeProfitPct) return { side: 'SELL', reason: 'TAKE_PROFIT', changePct };
  return null;
}
export function shouldHaltForUnknown(journal) {
  return journal.some(x => x.status === 'UNKNOWN' || x.status === 'SUBMITTED');
}
export function safetyStatus(journal, maxRealizedDailyLossSol, now = Date.now()) {
  const pnlAtomic = realizedTodayAtomic(journal, now);
  const limit = BigInt(Math.round(maxRealizedDailyLossSol * 1e9));
  return {
    dailyRealizedPnlSol: Number(pnlAtomic) / 1e9,
    lossLimitSol: maxRealizedDailyLossSol,
    halted: shouldHaltForUnknown(journal) || journal.some(t => t.status === 'SUCCESS' && (!/^\d+$/.test(String(t.actualInputAtomic)) || !/^\d+$/.test(String(t.actualOutputAtomic)))) || (limit > 0n && pnlAtomic <= -limit),
    reasons: [...(shouldHaltForUnknown(journal) ? ['Ungeklärte Blockchain-Transaktion zuerst manuell prüfen'] : []),
      ...(journal.some(t => t.status === 'SUCCESS' && (!/^\d+$/.test(String(t.actualInputAtomic)) || !/^\d+$/.test(String(t.actualOutputAtomic)))) ? ['Fehlende Ausführungsbeträge im Ledger – keine Automatik'] : []),
      ...(limit > 0n && pnlAtomic <= -limit ? ['Täglicher realisierter Verlustgrenzwert überschritten'] : [])]
  };
}
