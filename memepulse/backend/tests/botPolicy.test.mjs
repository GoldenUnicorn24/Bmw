import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmedPositions, exitSignal, realizedTodayAtomic, safetyStatus } from '../botPolicy.mjs';

const now = Date.now();
const buy = (mint='mintA', source='GROK') => ({ status: 'SUCCESS', side: 'BUY', mint, at: now-1000,
  actualInputAtomic: '100000000', actualOutputAtomic: '1000000', entryPriceUsd: 1, source });
const sell = (out, inTokens = '1000000') => ({ status: 'SUCCESS', side: 'SELL', mint: 'mintA', at: now,
  actualInputAtomic: inTokens, actualOutputAtomic: out, source: 'GROK_EXIT' });
test('only confirmed chain fills count as positions', () => {
  const a = confirmedPositions([buy(), { ...buy('bad'), status:'UNKNOWN' }]);
  assert.equal(a.positions.length, 1);
  assert.equal(a.positions[0].autoOwned, true);
  assert.equal(a.positions[0].costSolAtomic, '100000000');
});
test('realized SOL loss is computed after matching token sale', () => {
  const entries = [buy(), sell('87000000')];
  assert.equal(confirmedPositions(entries).positions.length, 0);
  assert.equal(realizedTodayAtomic(entries, now), -13000000n);
  assert.equal(safetyStatus(entries, .01, now).halted, true);
});
test('partial sale retains proportional cost basis and the remainder', () => {
  const result = confirmedPositions([buy(), sell('60000000', '500000')]);
  assert.equal(result.positions[0].tokenAtomic, '500000');
  assert.equal(result.positions[0].costSolAtomic, '50000000');
  assert.equal(result.realized[0].pnlSolAtomic, '10000000');
});
test('UNKNOWN chain transaction halts automated buys', () => {
  assert.equal(safetyStatus([{ status: 'UNKNOWN' }], .03).halted, true);
});
test('exit thresholds are conservative and fail closed for missing quotes', () => {
  assert.equal(exitSignal(1, .87).reason, 'STOP_LOSS');
  assert.equal(exitSignal(1, 1.26).reason, 'TAKE_PROFIT');
  assert.equal(exitSignal(1, .99), null);
  assert.equal(exitSignal(0, 1), null);
  assert.equal(exitSignal(1, 0), null);
});
