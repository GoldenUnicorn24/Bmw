// MemePulse Signal Engine 1.0: self-contained, deterministic, explainable.
// This is NOT a pretrained neural network and not a profitability guarantee.
// It is a conservative multi-horizon scoring model with hard rejection gates.
// Risk controls (RugCheck, allowlists, trade caps) are separate and mandatory.
const n = x => Number.isFinite(Number(x)) ? Number(x) : NaN;
const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
const round = v => Math.round(v * 100) / 100;

export function analyzeMarket(t) {
  if (!t || !t.mint) return { action: 'HOLD', mint: t?.mint || '', confidence: 0, score: 0,
    reason: 'Keine überprüfbaren Marktdaten', blockers: ['MARKET_MISSING'], engine: 'memepulse-quant-v1' };
  const liquidity = n(t.liquidityUsd), volume = n(t.volume24h), vol1 = n(t.volume1h);
  const m5 = n(t.change5m), m1 = n(t.change1h), m6 = n(t.change6h);
  const buyers = n(t.buys1h), sellers = n(t.sells1h), age = n(t.ageMinutes);
  const fdv = n(t.fdvUsd);
  const ratio = sellers > 0 ? buyers / sellers : buyers > 0 ? 5 : 0;
  const turnover = liquidity > 0 ? vol1 / liquidity : NaN;
  const blockers = [];
  const reject = (condition, code) => { if (condition) blockers.push(code); };
  reject(!Number.isFinite(n(t.priceUsd)) || n(t.priceUsd) <= 0, 'PRICE_INVALID');
  reject(!Number.isFinite(liquidity) || liquidity < 75000, 'LOW_LIQUIDITY');
  reject(!Number.isFinite(volume) || volume < 180000, 'LOW_VOLUME');
  reject(!Number.isFinite(vol1) || vol1 < 15000, 'LOW_1H_ACTIVITY');
  reject(!Number.isFinite(age) || age < 60 || age > 60 * 24 * 365, 'TOKEN_AGE');
  reject(!Number.isFinite(m5) || !Number.isFinite(m1) || !Number.isFinite(m6), 'INCOMPLETE_TREND');
  reject(Number.isFinite(m5) && (m5 < -3 || m5 > 12), '5M_TREND_EXTREME');
  reject(Number.isFinite(m1) && (m1 < 2 || m1 > 24), '1H_TREND_EXTREME');
  reject(Number.isFinite(m6) && (m6 < -20 || m6 > 80), '6H_TREND_EXTREME');
  reject(!Number.isFinite(buyers) || !Number.isFinite(sellers) || buyers < 25 || ratio < 1.20, 'BUY_PRESSURE_WEAK');
  reject(!Number.isFinite(turnover) || turnover < .02 || turnover > 3, 'ABNORMAL_TURNOVER');
  reject(Number.isFinite(fdv) && fdv > 0 && fdv / liquidity > 120, 'FDV_LIQUIDITY_IMBALANCE');

  let score = 0;
  // Quality 30
  score += liquidity >= 250000 ? 16 : liquidity >= 120000 ? 12 : liquidity >= 75000 ? 8 : 0;
  score += volume >= 1000000 ? 14 : volume >= 400000 ? 11 : volume >= 180000 ? 8 : 0;
  // Momentum agreement 35
  score += m5 >= 1 && m5 <= 7 ? 13 : m5 >= 0 && m5 <= 10 ? 9 : 0;
  score += m1 >= 4 && m1 <= 14 ? 14 : m1 >= 2 && m1 <= 24 ? 10 : 0;
  score += m6 >= -5 && m6 <= 35 ? 8 : m6 >= -20 && m6 <= 80 ? 4 : 0;
  // Demand & liquidity usage 25
  score += ratio >= 1.7 && ratio <= 3 ? 15 : ratio >= 1.25 ? 11 : 0;
  score += turnover >= .1 && turnover <= 1.4 ? 10 : turnover >= .02 && turnover <= 3 ? 6 : 0;
  // Token lifecycle 10
  score += age >= 240 ? 10 : age >= 120 ? 8 : age >= 60 ? 5 : 0;
  score = clamp(score, 0, 100);

  const passed = blockers.length === 0 && score >= 82;
  let reason = passed
    ? 'Trend über 5m/1h/6h bestätigt; Kaufdruck und Liquidität innerhalb der Schwellen.'
    : blockers.length
      ? 'Kein Kauf: ' + blockers.join(', ')
      : 'Kein Kauf: Signalstärke unter 82/100.';
  return {
    action: passed ? 'BUY' : 'HOLD', mint: String(t.mint),
    confidence: round(score / 100), score, eligible: passed,
    reason, blockers, engine: 'memepulse-quant-v1',
    evidence: { change5mPct: Number.isFinite(m5) ? round(m5) : null,
      change1hPct: Number.isFinite(m1) ? round(m1) : null,
      change6hPct: Number.isFinite(m6) ? round(m6) : null,
      buyerSellerRatio: Number.isFinite(ratio) ? round(ratio) : null,
      turnover1h: Number.isFinite(turnover) ? round(turnover) : null,
      liquidityUsd: Number.isFinite(liquidity) ? round(liquidity) : null }
  };
}

export function combineWithSecurity(signal, reasons) {
  const blockers = [...(signal.blockers || []), ...(reasons || [])];
  if (!blockers.length) return { ...signal, eligible: signal.action === 'BUY' };
  return { ...signal, action: 'HOLD', eligible: false,
    reason: 'Sicherheitsprüfung verhindert Kauf: ' + blockers.slice(-6).join(', '),
    blockers };
}
