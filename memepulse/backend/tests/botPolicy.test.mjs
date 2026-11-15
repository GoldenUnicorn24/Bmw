import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmedPositions, exitSignal, safetyStatus, shouldHaltForUnknown } from '../botPolicy.mjs';

const now = Date.now();
const buy = { at: now - 3000, status: 'SUCCESS', side: 'BUY',
  mint: 'token1', source: 'GROK', amountLamports: '10000000',
  actualInputAtomic: '10000000', actualOutputAtomic: '1000', entryPriceUsd: .02 };
const sell = { at: now - 1000, status: 'SUCCESS', side: 'SELL',
  mint: 'token1', source: 'GROK_EXIT', amountLamports: '500',
  actualInputAtomic: '500', actualOutputAtomic: '7000000' };

test('open positions are reconciled from confirmed fills, not paper gains', () => {
  const p = confirmedPositions([buy]).positions[0];
  assert.equal(p.tokenAtomic, '1000');
  assert.equal(p.costSolAtomic, '10000000');
  assert.equal(p.autoOwned, true);
  const closed = confirmedPositions([buy, sell]);
  assert.equal(closed.positions[0].tokenAtomic, '500');
  assert.equal(closed.positions[0].costSolAtomic, '5000000');
  assert.equal(closed.realized[0].pnlSolAtomic, '2000000');
});
test('model cannot suppress numerical stop loss or take profit', () => {
  assert.equal(exitSignal(.02, .016, 12, 25)?.reason, 'STOP_LOSS');
  assert.equal(exitSignal(.02, .028, 12, 25)?.reason, 'TAKE_PROFIT');
  assert.equal(exitSignal(.02, .0195, 12, 25), null);
  assert.equal(exitSignal(.02, NaN, 12, 25), null);
});
test('unknown or pending execution locks purchases until reconciled', () => {
  assert.equal(shouldHaltForUnknown([{ status:'UNKNOWN' }]), true);
  assert.equal(shouldHaltForUnknown([{ status:'SUBMITTED' }]), true);
  assert.equal(safetyStatus([buy, { ...sell, status: 'UNKNOWN' }], .015, now).halted, true);
  assert.equal(safetyStatus([buy, sell], .015, now).halted, false);
});
test('missing Jupiter execution atomic amounts blocks further AI orders', () => {
  const bad = [{ ...buy, actualOutputAtomic: null }];
  const s = safetyStatus(bad, .015, now);
  assert.equal(s.halted, true);
});
