import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeMarket, combineWithSecurity } from '../strategy.mjs';

const healthy = Object.freeze({
  mint: 'mint1', priceUsd: .004, liquidityUsd: 360000, volume24h: 1750000,
  volume1h: 172000, ageMinutes: 1200, change5m: 3.2,
  change1h: 9.5, change6h: 16, buys1h: 170, sells1h: 85,
  fdvUsd: 6000000
});
test('native strategy only buys with enough multi-window signal strength', () => {
  const signal = analyzeMarket(healthy);
  assert.equal(signal.action, 'BUY');
  assert.equal(signal.eligible, true);
  assert.ok(signal.confidence >= .85);
  assert.equal(signal.engine, 'memepulse-quant-v1');
  assert.ok(signal.evidence.buyerSellerRatio >= 1.2);
});
test('missing features, low liquidity, blow-off top and weak demand force HOLD', () => {
  for (const patch of [
    { priceUsd: 0 }, { volume1h: NaN }, { liquidityUsd: 22000 },
    { change5m: 88 }, { change1h: 55 }, { change6h: -40 },
    { buys1h: 10 }, { sells1h: 200 }, { ageMinutes: 2 }
  ]) {
    const result = analyzeMarket({ ...healthy, ...patch });
    assert.equal(result.action, 'HOLD', JSON.stringify(patch));
    assert.equal(result.eligible, false);
    assert.ok(result.blockers.length);
  }
});
test('risk audit can only cancel a buy, never create one', () => {
  const buy = analyzeMarket(healthy);
  assert.equal(combineWithSecurity(buy, []).action, 'BUY');
  const veto = combineWithSecurity(buy, ['RUGCHECK_FAILED']);
  assert.equal(veto.action, 'HOLD');
  assert.equal(veto.eligible, false);
  assert.equal(combineWithSecurity(analyzeMarket({ ...healthy, change1h: -45 }), []).action, 'HOLD');
});
test('missing or invalid market data cannot create positive signal', () => {
  assert.equal(analyzeMarket(null).action, 'HOLD');
  assert.equal(analyzeMarket({ mint: 'm', priceUsd: undefined }).action, 'HOLD');
});
