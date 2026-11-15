import test from 'node:test';
import assert from 'node:assert/strict';
import { marketScore, marketRejectReasons, rugRejectReasons, tradeRejectReasons, validateSignal } from '../risk.mjs';

const market = { mint: 'good', priceUsd: .01, liquidityUsd: 150000, volume24h: 400000,
  ageMinutes: 200, buys1h: 100, sells1h: 50, change1h: 7 };
test('market score uses explicit metrics', () => {
  assert.equal(marketScore(market), 100);
  assert.deepEqual(marketRejectReasons(market), []);
  assert.ok(marketRejectReasons({ ...market, liquidityUsd: 100 }).length);
});
test('rug report fails closed on missing authorities, raw risk and danger flags', () => {
  const clean = { score: 125, score_normalised: 80, risks: [],
    token: { mintAuthority: null, freezeAuthority: null } };
  assert.deepEqual(rugRejectReasons(clean), []);
  assert.ok(rugRejectReasons(null).length);
  assert.ok(rugRejectReasons({ ...clean, score: 2200 }).length);
  assert.ok(rugRejectReasons({ ...clean, risks: [{ level: 'DANGER' }] }).length);
  assert.ok(rugRejectReasons({ ...clean, token: { mintAuthority: 'abc', freezeAuthority: null } }).length);
  assert.ok(rugRejectReasons({ ...clean, token: null }).length);
  assert.ok(rugRejectReasons({ ...clean, score: NaN }).length);
});
test('buy requires allowlist and respects trade ceiling and daily cap', () => {
  const config = { side: 'BUY', mint: 'good', amountLamports: 8_000_000n, allowlist: new Set(['good']),
    maxTradeLamports: 10_000_000n, maxDailyLamports: 20_000_000n, currentBalanceLamports: 100_000_000n,
    reserveLamports: 10_000_000n, maxOrdersPerDay: 3, journal: [], now: Date.now() };
  assert.deepEqual(tradeRejectReasons(config), []);
  assert.ok(tradeRejectReasons({ ...config, mint: 'bad' }).some(x => x.includes('Freigabeliste')));
  assert.ok(tradeRejectReasons({ ...config, amountLamports: 11_000_000n }).length);
  assert.ok(tradeRejectReasons({ ...config, currentBalanceLamports: 12_000_000n }).length);
  const journal = [{ at: Date.now(), side: 'BUY', amountLamports: '15000000', status: 'UNKNOWN' }];
  assert.ok(tradeRejectReasons({ ...config, journal }).some(x => x.includes('Einsatzlimit')));
});
test('selling is allowed for risk-exit even when mint is not buy-allowlisted', () => {
  const input = { side: 'SELL', mint: 'unlisted', amountLamports: 100n,
    allowlist: new Set(), maxTradeLamports: 1n, maxDailyLamports: 1n,
    journal: [], currentBalanceLamports: 0n, reserveLamports: 100n };
  assert.deepEqual(tradeRejectReasons(input), []);
});
test('AI cannot override mathematical threshold or mint', () => {
  assert.equal(validateSignal({ action: 'BUY', mint: 'a', confidence: .9, reason: 'Positive Momentum' }, 'a'), true);
  assert.equal(validateSignal({ action: 'BUY', mint: 'b', confidence: .99, reason: 'Positive Momentum' }, 'a'), false);
  assert.equal(validateSignal({ action: 'BUY', mint: 'a', confidence: .7, reason: 'Positive Momentum' }, 'a'), false);
});
