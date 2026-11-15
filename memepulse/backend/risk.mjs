export const WSOL = 'So11111111111111111111111111111111111111112';
export const LAMPORTS_PER_SOL = 1_000_000_000;

export function marketScore(t) {
  if (!t || !Number.isFinite(t.priceUsd) || t.priceUsd <= 0) return 0;
  let score = 0;
  if (t.liquidityUsd >= 100000) score += 25;
  else if (t.liquidityUsd >= 50000) score += 16;
  if (t.volume24h >= 250000) score += 20;
  else if (t.volume24h >= 100000) score += 10;
  if (t.ageMinutes >= 120) score += 15;
  else if (t.ageMinutes >= 30) score += 8;
  if (t.buys1h > 20 && t.buys1h / Math.max(1, t.sells1h) >= 1.1) score += 20;
  if (t.change1h >= 3 && t.change1h <= 25) score += 20;
  return score;
}

export function marketRejectReasons(t, minScore = 75) {
  const reasons = [];
  if (!t) return ['Keine Marktdaten'];
  if (!Number.isFinite(t.priceUsd) || t.priceUsd <= 0) reasons.push('Ungültiger Preis');
  if (!Number.isFinite(t.liquidityUsd) || t.liquidityUsd < 50000) reasons.push('Zu wenig Liquidität');
  if (!Number.isFinite(t.volume24h) || t.volume24h < 100000) reasons.push('Zu wenig Handelsvolumen');
  if (!Number.isFinite(t.ageMinutes) || t.ageMinutes < 30) reasons.push('Token zu jung');
  if (marketScore(t) < minScore) reasons.push('Markt-Metrik unter Grenzwert');
  return reasons;
}

// RugCheck raw score is an aggregate *risk* score: LOWER is safer.
// Never use score_normalised for live authorization; its direction is inconsistently documented.
// Fail closed on unavailable reports or unknown authorities.
export function rugRejectReasons(report) {
  if (!report || report.score == null || !Number.isFinite(Number(report.score)) || Number(report.score) < 0)
    return ['RugCheck-Rohwert nicht verfügbar'];
  const reasons = [];
  const raw = Number(report.score);
  if (raw > 800) reasons.push('RugCheck-Rohwert über konservativer Risikogrenze');
  if (!Array.isArray(report.risks)) reasons.push('Token-Risiken unvollständig');
  else if (report.risks.some(r => ['danger', 'critical'].includes(String(r.level || '').toLowerCase())))
    reasons.push('Kritische Sicherheitswarnung von RugCheck');
  // Full report is mandatory for permission checks. The /summary endpoint alone
  // cannot prove whether an arbitrary mint or freeze authority was revoked.
  if (!report.token || typeof report.token !== 'object')
    reasons.push('Mint-/Freeze-Berechtigungen nicht bestätigt');
  else {
    if (report.token.mintAuthority) reasons.push('Mint Authority ist aktiv');
    if (report.token.freezeAuthority) reasons.push('Freeze Authority ist aktiv');
    if (!('mintAuthority' in report.token) || !('freezeAuthority' in report.token))
      reasons.push('Token-Berechtigungen unvollständig');
  }
  return reasons;
}

export function tradeRejectReasons({ side, mint, amountLamports, allowlist, maxTradeLamports, maxDailyLamports, journal, currentBalanceLamports, reserveLamports, maxOrdersPerDay = 3, now = Date.now() }) {
  const problems = [];
  if (!['BUY', 'SELL'].includes(side)) problems.push('Unbekannte Handelsrichtung');
  if (side === 'BUY' && !allowlist.has(mint)) problems.push('Token nicht auf ausdrücklicher Freigabeliste');
  if (typeof amountLamports !== 'bigint' || amountLamports <= 0n) problems.push('Ungültige Handelsmenge');
  if (side === 'BUY' && amountLamports > maxTradeLamports) problems.push('Maximaler Einsatz je Trade überschritten');
  const start = new Date(now);
  start.setUTCHours(0, 0, 0, 0);
  const today = journal.filter(x => x.at >= start.getTime() && ['SUBMITTED', 'SUCCESS', 'UNKNOWN'].includes(x.status));
  const buys = today.filter(x => x.side === 'BUY');
  const spent = buys.reduce((sum, x) => sum + BigInt(x.amountLamports || '0'), 0n);
  if (side === 'BUY' && spent + amountLamports > maxDailyLamports) problems.push('Tägliches Einsatzlimit überschritten');
  if (side === 'BUY' && buys.length >= maxOrdersPerDay) problems.push('Tägliche Orderanzahl überschritten');
  if (side === 'BUY' && typeof currentBalanceLamports === 'bigint' &&
      currentBalanceLamports < amountLamports + reserveLamports) problems.push('Zu wenig SOL einschließlich Reserve');
  return problems;
}

export function validateSignal(signal, mint) {
  return Boolean(signal && signal.action === 'BUY' && signal.mint === mint &&
    Number.isFinite(signal.confidence) && signal.confidence >= 0.85 &&
    typeof signal.reason === 'string' && signal.reason.length >= 6 &&
    signal.reason.length <= 500);
}
