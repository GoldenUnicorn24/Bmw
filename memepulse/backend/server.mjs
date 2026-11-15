import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { Keypair, PublicKey, VersionedTransaction } from '@solana/web3.js';
import bs58 from 'bs58';
import { confirmedPositions, exitSignal, safetyStatus, shouldHaltForUnknown } from './botPolicy.mjs';
import { analyzeMarket, combineWithSecurity } from './strategy.mjs';
import {appendSnapshot,confirmHistory,sizeBuy,dailyRemaining,strategyHealth,evaluateExit} from './autopilot.mjs';
import { WSOL, marketScore, marketRejectReasons, rugRejectReasons, tradeRejectReasons, validateSignal } from './risk.mjs';

const env = process.env;
const PORT = Number(env.PORT || 8787);
const HOST = env.HOST || '127.0.0.1';
const TOKEN = env.API_BEARER_TOKEN || '';
if (TOKEN.length < 32) throw Error('API_BEARER_TOKEN must be at least 32 characters. Refusing to start.');
const LIVE = env.ENABLE_LIVE_TRADES === 'YES_I_ACCEPT_REAL_LOSSES';
const AUTO_ALLOWED = env.ALLOW_AUTONOMOUS_LIVE === 'YES_I_ACCEPT_AUTONOMOUS_LOSSES';
const ALLOWLIST = new Set((env.TRADING_ALLOWLIST || '').split(',').map(x => x.trim()).filter(Boolean));
const JUPITER_KEY = env.JUPITER_API_KEY || '';
// No commercial AI API required: local, deterministic, auditable quant engine.
const RPC = env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const DATA_FILE = env.TRADE_LOG_PATH || './private/trades.json';
const STOP_FILE = env.STOP_FILE || './STOP';
const MAX_TRADE = BigInt(Math.round(Number(env.MAX_TRADE_SOL || '0.01') * 1e9));
const MAX_DAILY = BigInt(Math.round(Number(env.MAX_DAILY_SPEND_SOL || '0.05') * 1e9));
const RESERVE = BigInt(Math.round(Number(env.MIN_SOL_RESERVE || '0.02') * 1e9));
const MAX_ORDERS = Math.max(1, Math.min(5, Number(env.MAX_ORDERS_PER_DAY || '3')));
const MAX_REALIZED_LOSS_SOL = Math.min(1, Math.max(0.001, Number(env.MAX_DAILY_REALIZED_LOSS_SOL || '0.015')));
const MAX_OPEN_POSITIONS = Math.max(1, Math.min(3, Number(env.MAX_OPEN_POSITIONS || '2')));
const STOP_LOSS_PCT = Math.min(30, Math.max(3, Number(env.STOP_LOSS_PCT || '12')));
const TAKE_PROFIT_PCT = Math.min(100, Math.max(5, Number(env.TAKE_PROFIT_PCT || '25')));
const MAX_PRICE_IMPACT_PCT = Math.min(5, Math.max(0.2, Number(env.MAX_PRICE_IMPACT_PCT || '2')));
const MAX_SLIPPAGE_BPS = Math.min(200, Math.max(25, Number(env.MAX_SLIPPAGE_BPS || '100')));
const CONTROL_MAX_AGE_MS = 30000;
const AUTOPILOT_SESSION_HOURS = Math.min(24, Math.max(1, Number(env.AUTOPILOT_SESSION_HOURS || '24')));
const MAX_HISTORY_SAMPLES = 25;
if (!RPC.startsWith('https://')) throw Error('SOLANA_RPC_URL must be HTTPS');
const signer = env.SOLANA_PRIVATE_KEY_B58 ? Keypair.fromSecretKey(bs58.decode(env.SOLANA_PRIVATE_KEY_B58)) : null;
const address = signer?.publicKey.toBase58() || null;
if (LIVE && (!signer || !JUPITER_KEY)) throw Error('Live mode requires SOLANA_PRIVATE_KEY_B58 and JUPITER_API_KEY.');
let journal = [];
try { journal = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')); if (!Array.isArray(journal)) throw Error('Bad log'); }
catch (err) { if (err.code !== 'ENOENT') throw Error('Cannot load trade journal: ' + err.message); }
let busy = false;
let activeUntil = 0;
let exitOnlyUntil = 0;
let sessionExpiredLogged = false;
let lastAutoCycle = 0;
let lastAgentObservation = null;
let lastAgentError = null;
let agentEvents = [];
let lastExitAttempt = new Map();
function agentEvent(event, details = '') {
  agentEvents.unshift({ at: Date.now(), event, details: String(details).slice(0, 240) });
  agentEvents = agentEvents.slice(0, 60);
}
let marketCache = { at: 0, tokens: [] };
const prepared = new Map();
const usedKeys = new Map();
const cooldown = new Map();
const snapshots = new Map();
const peakByMint = new Map();
const lastConfirmed = new Map();
let lastStrategyHealth = null;

function saveJournal() {
  fs.mkdirSync(new URL('.', 'file://' + (DATA_FILE.startsWith('/') ? DATA_FILE : process.cwd() + '/' + DATA_FILE)), { recursive: true, mode: 0o700 });
  const tmp = DATA_FILE + '.' + process.pid + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(journal.slice(-1000), null, 2), { mode: 0o600 });
  fs.renameSync(tmp, DATA_FILE);
}
function reply(res, status, value) {
  const raw = JSON.stringify(value);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  res.end(raw);
}
async function readBody(req) {
  let s = '';
  for await (const chunk of req) {
    s += chunk;
    if (s.length > 16384) throw Error('Anfrage zu groß');
  }
  return s ? JSON.parse(s) : {};
}
function authenticated(req) {
  const supplied = (req.headers.authorization || '').replace(/^Bearer /i, '');
  const a = Buffer.from(supplied);
  const b = Buffer.from(TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
async function jsonFetch(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(14000) });
  const body = await response.text();
  if (!response.ok) throw Error('Remote API HTTP ' + response.status + ': ' + body.slice(0, 120));
  return JSON.parse(body);
}
async function rpc(method, params) {
  const r = await jsonFetch(RPC, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
  if (r.error) throw Error('RPC: ' + (r.error.message || 'unbekannter Fehler'));
  return r.result;
}
async function balanceSol() {
  return BigInt((await rpc('getBalance', [address, { commitment: 'confirmed' }])).value);
}
async function tokenBalance(mint) {
  const result = await rpc('getTokenAccountsByOwner', [address, { mint }, { encoding: 'jsonParsed', commitment: 'confirmed' }]);
  return (result.value || []).reduce((sum, account) => {
    const amount = account.account?.data?.parsed?.info?.tokenAmount?.amount || '0';
    return sum + BigInt(amount);
  }, 0n);
}
function normalizePair(pair, mint) {
  if (!pair || !pair.baseToken || pair.baseToken.address !== mint) return null;
  return {
    mint, name: String(pair.baseToken.name || '').slice(0, 80),
    symbol: String(pair.baseToken.symbol || '').slice(0, 20),
    priceUsd: Number(pair.priceUsd || 0), liquidityUsd: Number(pair.liquidity?.usd || 0),
    volume24h: Number(pair.volume?.h24 || 0),
    volume1h: Number(pair.volume?.h1 || 0),
    change5m: Number(pair.priceChange?.m5 ?? NaN),
    change1h: Number(pair.priceChange?.h1 ?? NaN),
    change6h: Number(pair.priceChange?.h6 ?? NaN),
    change24h: Number(pair.priceChange?.h24 ?? NaN),
    fdvUsd: Number(pair.fdv || 0),
    ageMinutes: Math.max(0, (Date.now() - Number(pair.pairCreatedAt || Date.now())) / 60000),
    buys1h: Number(pair.txns?.h1?.buys || 0), sells1h: Number(pair.txns?.h1?.sells || 0),
    dex: pair.dexId || '', pair: pair.pairAddress || '', marketUrl: pair.url || ''
  };
}
async function marketFor(mint) {
  const pairs = await jsonFetch('https://api.dexscreener.com/token-pairs/v1/solana/' + encodeURIComponent(mint));
  return (Array.isArray(pairs) ? pairs : []).map(p => normalizePair(p, mint))
    .filter(Boolean).sort((a, b) => b.liquidityUsd - a.liquidityUsd)[0] || null;
}
async function marketFeed() {
  if (Date.now() - marketCache.at < 45000 && marketCache.tokens.length) return marketCache.tokens;
  const [boosts, profiles] = await Promise.all([
    jsonFetch('https://api.dexscreener.com/token-boosts/top/v1'),
    jsonFetch('https://api.dexscreener.com/token-profiles/latest/v1')
  ]);
  const mints = [...new Set([...(Array.isArray(boosts) ? boosts : []), ...(Array.isArray(profiles) ? profiles : [])]
    .filter(x => x.chainId === 'solana').map(x => x.tokenAddress).filter(Boolean))].slice(0, 30);
  if (!mints.length) return [];
  const allPairs = await jsonFetch('https://api.dexscreener.com/tokens/v1/solana/' + mints.join(','));
  const byMint = new Map();
  for (const mint of mints) {
    const pairs = (Array.isArray(allPairs) ? allPairs : []).map(p => normalizePair(p, mint)).filter(Boolean)
      .sort((a, b) => b.liquidityUsd - a.liquidityUsd);
    if (pairs[0]) byMint.set(mint, { ...pairs[0], score: marketScore(pairs[0]), promotedFeed: true });
  }
  marketCache = { at: Date.now(), tokens: [...byMint.values()].sort((a, b) => b.score - a.score) };
  return marketCache.tokens;
}
async function rugReport(mint) {
  // Full report includes actual mint and freeze authorities; fail-closed if unavailable.
  const report = await jsonFetch('https://api.rugcheck.xyz/v1/tokens/' + encodeURIComponent(mint) + '/report');
  const reasons = rugRejectReasons(report);
  return { scoreRaw: Number(report.score), scoreNormalised: Number(report.score_normalised),
    mintAuthorityEnabled: !!report.token?.mintAuthority,
    freezeAuthorityEnabled: !!report.token?.freezeAuthority,
    risks: report.risks || [], approved: !reasons.length, reasons };
}
function assertCanTrade() {
  if (!LIVE) throw Error('Echtgeldhandel ist serverseitig deaktiviert');
  if (!signer) throw Error('Kein Trading-Wallet auf dem Server');
  if (fs.existsSync(STOP_FILE)) throw Error('STOP-Datei vorhanden: alle Trades gesperrt');
  if (busy) throw Error('Ein Trade wird bereits ausgeführt');
}
async function prepareOrder(body, source = 'MANUAL') {
  assertCanTrade();
  const mint = String(body.mint || '');
  new PublicKey(mint);
  const side = String(body.side || 'BUY').toUpperCase();
  if (side !== 'BUY' && side !== 'SELL') throw Error('Ungültige Handelsrichtung');
  let amount;
  if (side === 'BUY') {
    const sol = Number(body.amountSol);
    if (!Number.isFinite(sol) || sol <= 0 || sol > 2) throw Error('Ungültiger SOL-Betrag');
    amount = BigInt(Math.round(sol * 1e9));
  } else {
    const pct = Number(body.percent);
    if (!Number.isFinite(pct) || pct < 1 || pct > 100) throw Error('Verkaufsanteil muss zwischen 1 und 100 liegen');
    const holdings = await tokenBalance(mint);
    const available = source === 'BOT_EXIT' ?
      BigInt(confirmedPositions(journal).positions.find(p => p.mint === mint && p.autoOwned)?.tokenAtomic || '0') : holdings;
    amount = (holdings < available ? holdings : available) * BigInt(Math.floor(pct * 100)) / 10000n;
  }
  const balance = await balanceSol();
  const reasons = tradeRejectReasons({
    side, mint, amountLamports: amount, allowlist: ALLOWLIST, maxTradeLamports: MAX_TRADE,
    maxDailyLamports: MAX_DAILY, journal, currentBalanceLamports: balance,
    reserveLamports: RESERVE, maxOrdersPerDay: MAX_ORDERS
  });
  let market = null;
  let safety = null;
  if (side === 'BUY') {
    const halt = safetyStatus(journal, MAX_REALIZED_LOSS_SOL);
    reasons.push(...halt.reasons);
    if (confirmedPositions(journal).positions.length >= MAX_OPEN_POSITIONS)
      reasons.push('Maximale Anzahl offener Wallet-Positionen erreicht');
    if (confirmedPositions(journal).positions.some(p => p.mint === mint))
      reasons.push('Token ist bereits in der Wallet-Positionenliste');
    if (journal.some(t => t.side === 'BUY' && t.mint === mint && t.at > Date.now() - 86400000 &&
      ['SUBMITTED', 'SUCCESS', 'UNKNOWN'].includes(t.status)))
      reasons.push('Token wurde bereits in den letzten 24 Stunden gekauft');
    market = await marketFor(mint);
    reasons.push(...marketRejectReasons(market, 75));
    safety = await rugReport(mint);
    reasons.push(...safety.reasons);
    const last = cooldown.get(mint) || 0;
    if (Date.now() - last < 24 * 60 * 60 * 1000) reasons.push('Token bereits in den letzten 24 Stunden gekauft');
  }
  if (reasons.length) throw Error('Risikoprüfung: ' + reasons.join('; '));
  const params = new URLSearchParams({
    inputMint: side === 'BUY' ? WSOL : mint,
    outputMint: side === 'BUY' ? mint : WSOL,
    amount: String(amount), taker: address, slippageBps: String(MAX_SLIPPAGE_BPS)
  });
  const quote = await jsonFetch('https://api.jup.ag/swap/v2/order?' + params,
    { headers: { 'x-api-key': JUPITER_KEY } });
  if (!quote.transaction || !quote.requestId || !quote.outAmount) throw Error('Jupiter hat keine handelbare Route geliefert');
  if (Number.isFinite(Number(quote.priceImpactPct)) && quote.priceImpactPct !== null &&
    Math.abs(Number(quote.priceImpactPct)) > MAX_PRICE_IMPACT_PCT)
    throw Error('Jupiter-Preiswirkung oberhalb der Risikogrenze');
  if (quote.transactionVersion && Number(quote.transactionVersion) > 0)
    throw Error('Transaktionsversion nicht vom Server unterstützt');
  if (quote.inAmount && BigInt(quote.inAmount) !== amount) throw Error('Quote-Einsatz stimmt nicht überein');
  if (BigInt(quote.outAmount) <= 0n) throw Error('Quote hat keinen Auszahlungsbetrag');
  const id = crypto.randomUUID();
  const order = { id, side, mint, amountLamports: String(amount),
    inputMint: params.get('inputMint'), outputMint: params.get('outputMint'),
    transaction: quote.transaction, requestId: quote.requestId,
    estimatedOutputAtomic: quote.outAmount, market, safety, source, created: Date.now() };
  prepared.set(id, order);
  setTimeout(() => prepared.delete(id), CONTROL_MAX_AGE_MS + 2000).unref();
  return { previewId: id, side, mint, inputAtomic: String(amount), outputAtomic: quote.outAmount,
    router: quote.router || 'Jupiter', estimatedPriceUsd: market?.priceUsd ?? null,
    feeBps: quote.feeBps ?? null, slippageBps: MAX_SLIPPAGE_BPS,
    priceImpactPct: quote.priceImpactPct ?? null,
    expiresAt: order.created + CONTROL_MAX_AGE_MS, wallet: address, source, safety };
}
async function executeOrder(id, source) {
  assertCanTrade();
  // Acquire the execution lock BEFORE any asynchronous RPC to prevent parallel spends.
  busy = true;
  try {
  const order = prepared.get(id);
  if (!order) throw Error('Quote abgelaufen oder bereits verwendet');
  prepared.delete(id);
  if (Date.now() - order.created > CONTROL_MAX_AGE_MS) throw Error('Quote ist zu alt');
  const balance = await balanceSol();
  const reasons = tradeRejectReasons({
    side: order.side, mint: order.mint, amountLamports: BigInt(order.amountLamports),
    allowlist: ALLOWLIST, maxTradeLamports: MAX_TRADE, maxDailyLamports: MAX_DAILY,
    journal, currentBalanceLamports: balance, reserveLamports: RESERVE, maxOrdersPerDay: MAX_ORDERS
  });
  if (order.side === 'BUY') reasons.push(...safetyStatus(journal, MAX_REALIZED_LOSS_SOL).reasons);
  if (order.side === 'BUY' && confirmedPositions(journal).positions.length >= MAX_OPEN_POSITIONS)
    reasons.push('Maximale Positionsanzahl erreicht');
  if (order.side === 'BUY' && confirmedPositions(journal).positions.some(p => p.mint === order.mint))
    reasons.push('Token bereits gehalten');
  if (reasons.length) throw Error('Trade wegen Limit gestoppt: ' + reasons.join('; '));
  const entry = { id, at: Date.now(), side: order.side, mint: order.mint,
    amountLamports: order.amountLamports, estimatedOutputAtomic: order.estimatedOutputAtomic,
    entryPriceUsd: order.market?.priceUsd ?? null,
    source, status: 'SUBMITTED', signature: null };
  journal.push(entry);
  saveJournal(); // Block duplicate spending BEFORE broadcasting.
  try {
    const tx = VersionedTransaction.deserialize(Buffer.from(order.transaction, 'base64'));
    if (!tx.message.staticAccountKeys.some(k => k.equals(signer.publicKey)))
      throw Error('Signer nicht in der Jupiter-Transaktion gefunden');
    tx.sign([signer]); // Secret remains on backend only.
    const signed = Buffer.from(tx.serialize()).toString('base64');
    const result = await jsonFetch('https://api.jup.ag/swap/v2/execute', {
      method: 'POST', headers: { 'content-type': 'application/json', 'x-api-key': JUPITER_KEY },
      body: JSON.stringify({ signedTransaction: signed, requestId: order.requestId })
    });
    entry.signature = result.signature || null;
    entry.actualInputAtomic = result.totalInputAmount || null;
    entry.actualOutputAtomic = result.totalOutputAmount || null;
    entry.status = result.status === 'Success' ? 'SUCCESS' : 'FAILED';
    entry.error = result.status === 'Success' ? null : String(result.error || result.code || 'Jupiter-Ausführung fehlgeschlagen');
    if (entry.status === 'SUCCESS' && order.side === 'BUY') cooldown.set(order.mint, Date.now());
    if (entry.status === 'SUCCESS') agentEvent('EXECUTION_' + order.side, order.mint.slice(0, 12) + ' · ' + source);
    saveJournal();
    return { id, status: entry.status, signature: entry.signature, error: entry.error || null };
  } catch (err) {
    // May have landed despite timeout. Never auto-resubmit an unknown swap.
    entry.status = 'UNKNOWN';
    entry.error = String(err.message).slice(0, 200);
    saveJournal();
    throw Error('Ausführung ungewiss. Nicht erneut klicken! Signatur/Wallet im Explorer prüfen. ' + entry.error);
  }
  } finally { busy = false; }
}
// Local decision engine: never needs xAI, Grok, OpenAI or any external model key.
async function scanAndExplain(mint) {
  const market = await marketFor(mint);
  const now = Date.now();
  if (market) snapshots.set(mint, appendSnapshot(snapshots.get(mint) || [], market, now).slice(-MAX_HISTORY_SAMPLES));
  const signal = analyzeMarket(market || { mint });
  const confirmation = confirmHistory(snapshots.get(mint) || [], now);
  const marketReasons = marketRejectReasons(market, 75);
  const observations = confirmation.passed ? [] : [confirmation.code];
  if (marketReasons.length || !signal.eligible || !confirmation.passed) {
    return { ...combineWithSecurity(signal, [...marketReasons, ...observations]), market,
      safety: null, confirmation };
  }
  try {
    const safety = await rugReport(mint);
    const result = combineWithSecurity(signal, safety.reasons);
    return { ...result, market, safety, confirmation };
  } catch (err) {
    return { ...combineWithSecurity(signal, ['TOKEN_SECURITY_UNAVAILABLE']), market,
      safety: null, confirmation, error: String(err.message).slice(0, 140) };
  }
}
function currentPerformance() {
  const realized = confirmedPositions(journal).realized.map(x =>
    ({ pnlSol: Number(BigInt(x.pnlSolAtomic)) / 1e9 })).slice(-200);
  return strategyHealth(realized);
}
async function agentCycle() {
  const now = Date.now();
  const entriesAllowed = AUTO_ALLOWED && LIVE && activeUntil > now;
  const exitsAllowed = AUTO_ALLOWED && LIVE && exitOnlyUntil > now;
  if (!entriesAllowed && !exitsAllowed) return;
  if (!entriesAllowed && !sessionExpiredLogged) {
    sessionExpiredLogged = true;
    agentEvent('SESSION_EXPIRED','Käufe gestoppt; bestehende Auto-Positionen werden bis zum Ausstiegsfenster weiter überwacht');
  }
  if (fs.existsSync(STOP_FILE)) {
    activeUntil = 0; exitOnlyUntil = 0;
    agentEvent('STOPPED', 'STOP-Datei erkannt');
    return;
  }
  if (busy || Date.now() - lastAutoCycle < 60000) return;
  lastAutoCycle = Date.now();
  const halt = safetyStatus(journal, MAX_REALIZED_LOSS_SOL);
  if (halt.halted) { activeUntil = 0; agentEvent('RISK_HALT', halt.reasons.join('; ')); return; }

  // Exit checks are deterministic, independent of positive engine signals.
  const holdings = confirmedPositions(journal).positions.filter(p => p.autoOwned);
  for (const p of holdings) {
    if (Date.now() - (lastExitAttempt.get(p.mint) || 0) < 120000) continue;
    try {
      const market = await marketFor(p.mint);
      if (!market?.priceUsd) { agentEvent('EXIT_MONITOR_MISSING', p.mint.slice(0, 12)); return; }
      const peak = Math.max(peakByMint.get(p.mint)||0, market.priceUsd, p.entryPriceUsd);
      peakByMint.set(p.mint, peak);
      const exit = evaluateExit({entryPriceUsd:p.entryPriceUsd,markPriceUsd:market.priceUsd,
        peakPriceUsd:peak,buyAt:p.buyAt,stopLossPct:STOP_LOSS_PCT,takeProfitPct:TAKE_PROFIT_PCT});
      lastAgentObservation = { at: Date.now(), mint: p.mint, action: exit.reason,
        priceUsd: market.priceUsd, entryPriceUsd: p.entryPriceUsd,
        peakPriceUsd:peak, changePct:exit.changePct };
      if (!exit.sell) continue;
      lastExitAttempt.set(p.mint, Date.now());
      agentEvent('EXIT_TRIGGER', p.mint.slice(0, 12) + ': ' + exit.reason + ' ' + exit.changePct.toFixed(1) + '%');
      const order = await prepareOrder({ side: 'SELL', mint: p.mint, percent: 100 }, 'BOT_EXIT');
      await executeOrder(order.previewId, 'BOT_EXIT');
      return;
    } catch (err) {
      lastAgentError = String(err.message).slice(0, 220);
      agentEvent('EXIT_SKIPPED', lastAgentError);
      // Exit failure must not lead to fresh entries in this cycle.
      return;
    }
  }
  if (!entriesAllowed) return; // Exit-only window never places fresh BUY orders.
  if (holdings.length >= MAX_OPEN_POSITIONS) return;
  lastStrategyHealth = currentPerformance();
  if (lastStrategyHealth.halt) {
    agentEvent('STRATEGY_HALT', 'Vergangene abgeschlossene Trades zeigen negative durchschnittliche Rendite');
    activeUntil = 0;
    return;
  }
  // One candidate at a time, limited to explicitly reviewed allowlist mints.
  for (const mint of ALLOWLIST) {
    if (Date.now() >= activeUntil || fs.existsSync(STOP_FILE)) return;
    if (cooldown.get(mint) && Date.now() - cooldown.get(mint) < 86400000) continue;
    if (journal.some(t => t.side === 'BUY' && t.mint === mint && t.at > Date.now() - 86400000 &&
      ['SUBMITTED', 'SUCCESS', 'UNKNOWN'].includes(t.status))) continue;
    try {
      const result = await scanAndExplain(mint);
      lastAgentObservation = { at: Date.now(), mint, action: result.action,
        confidence: result.confidence || 0, reason: result.reason, eligible: !!result.eligible };
      agentEvent(result.eligible ? 'BUY_SIGNAL' : 'HOLD_SIGNAL', mint.slice(0, 12) + ' · ' + (result.reason || 'Kein Signal'));
      if (!result.eligible) continue;
      const availableWallet = await balanceSol();
      const amount = sizeBuy({walletLamports:availableWallet,reserveLamports:RESERVE,
        maxTradeLamports:MAX_TRADE,dailyRemainingLamports:dailyRemaining(journal,MAX_DAILY),
        riskBudgetBps:50,stopLossPct:STOP_LOSS_PCT});
      if (amount.amountLamports <= 0n) {
        agentEvent('FUNDS_LIMIT', amount.reason);
        return;
      }
      const preview = await prepareOrder({ side: 'BUY', mint,
        amountSol:Number(amount.amountLamports)/1e9 }, 'ENGINE');
      if (Date.now() >= activeUntil || fs.existsSync(STOP_FILE)) return;
      await executeOrder(preview.previewId, 'ENGINE');
      return; // Only one transaction per cycle.
    } catch (err) {
      lastAgentError = String(err.message).slice(0, 220);
      agentEvent('SCAN_SKIPPED', lastAgentError);
    }
  }
}

setInterval(() => { if (activeUntil > Date.now() || exitOnlyUntil > Date.now()) agentCycle().catch(err => console.error('Agent:', err.message)); }, 60000).unref();
const server = http.createServer(async (req, res) => {
  if (req.url === '/health' && req.method === 'GET') return reply(res, 200, { ok: true, name: 'MemePulse API', realTradesEnabled: LIVE });
  if (!authenticated(req)) return reply(res, 401, { error: 'Bearer-Authentifizierung erforderlich' });
  try {
    const url = new URL(req.url, 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/api/status') return reply(res, 200, {
      live: LIVE, autoAllowed: AUTO_ALLOWED, autoActive: activeUntil > Date.now(),
      exitOnlyActive: exitOnlyUntil > Date.now() && activeUntil <= Date.now(),
      autoExpiresAt: activeUntil || null, exitProtectionExpiresAt: exitOnlyUntil || null,
      engineConnected: true, strategyName: 'MemePulse Quant v1', engineVersion: 'memepulse-quant-v1-autopilot2', wallet: address, whitelistedMints: [...ALLOWLIST],
      maxTradeSol: Number(MAX_TRADE) / 1e9, maxDailySpendSol: Number(MAX_DAILY) / 1e9,
      maxOrdersPerDay: MAX_ORDERS, maxOpenPositions: MAX_OPEN_POSITIONS,
      positionSizing: 'max 2% of wallet liquidity, 0.5% SOL stop-equivalent loss budget',
      sessionHours: AUTOPILOT_SESSION_HOURS, strategyHealth: currentPerformance(),
      stopLossPct: STOP_LOSS_PCT, takeProfitPct: TAKE_PROFIT_PCT,
      maxDailyRealizedLossSol: MAX_REALIZED_LOSS_SOL,
      risk: safetyStatus(journal, MAX_REALIZED_LOSS_SOL), stopped: fs.existsSync(STOP_FILE),
      warning: 'Kein garantierter Profit. Sicherheitsprüfung ersetzt kein Audit.'
    });
    if (req.method === 'GET' && url.pathname === '/api/market') return reply(res, 200, {
      tokens: await marketFeed(), promotedFeed: true, source: 'DEX Screener', timestamp: Date.now()
    });
    if (req.method === 'GET' && url.pathname === '/api/trades') return reply(res, 200, { trades: journal.slice(-80).reverse() });
    if (req.method === 'GET' && url.pathname === '/api/agent') return reply(res, 200, {
      active: Date.now() < activeUntil && !fs.existsSync(STOP_FILE),
      exitOnlyActive: Date.now() >= activeUntil && Date.now() < exitOnlyUntil,
      expiresAt: activeUntil || null, exitProtectionExpiresAt: exitOnlyUntil || null, lastObservation: lastAgentObservation,
      performance: currentPerformance(), historyTracked: snapshots.size,
      lastError: lastAgentError, events: agentEvents,
      limits: { stopLossPct: STOP_LOSS_PCT, takeProfitPct: TAKE_PROFIT_PCT,
        maxDailyRealizedLossSol: MAX_REALIZED_LOSS_SOL, maxOpenPositions: MAX_OPEN_POSITIONS },
      risk: safetyStatus(journal, MAX_REALIZED_LOSS_SOL)
    });
    if (req.method === 'GET' && url.pathname === '/api/portfolio') {
      const ledger = confirmedPositions(journal);
      const positions = await Promise.all(ledger.positions.map(async p => {
        try { const market = await marketFor(p.mint); return { ...p, markPriceUsd: market?.priceUsd || null,
          estimatedChangePct: p.entryPriceUsd > 0 && market?.priceUsd ? (market.priceUsd / p.entryPriceUsd - 1) * 100 : null,
          unrealizedPnlNote: 'Nur Schätzung auf Basis DEX-Preisen; keine ausführbare Quote' }; }
        catch { return { ...p, markPriceUsd: null, estimatedChangePct: null }; }
      }));
      return reply(res, 200, { positions, realized: ledger.realized.slice(-30),
        risk: safetyStatus(journal, MAX_REALIZED_LOSS_SOL),
        note: 'Ohne Wallet-Transaktionen außerhalb dieser App. SOL-Gebühren, Transfers und Sell Slippage sind nicht bilanziert.' });
    }
    if (req.method === 'GET' && url.pathname === '/api/wallet') return reply(res, 200, {
      address, balanceSol: signer ? Number(await balanceSol()) / 1e9 : null
    });
    if (req.method === 'GET' && url.pathname === '/api/deposit') return reply(res, 200, {
      network: 'Solana mainnet', asset: 'SOL', address,
      balanceSol: signer ? Number(await balanceSol()) / 1e9 : null,
      ready: !!(signer && LIVE), autonomousArmed: activeUntil>Date.now(),
      instructions:'Deposit only native SOL to your separately created dedicated wallet after verifying the address. Funding never authorizes trading.'
    });
    if (req.method !== 'POST') return reply(res, 404, { error: 'Route nicht gefunden' });
    const body = await readBody(req);
    if (url.pathname === '/api/ai/signal') {
      const mint = String(body.mint || '');
      new PublicKey(mint);
      return reply(res, 200, await scanAndExplain(mint));
    }
    if (url.pathname === '/api/orders/prepare') {
      return reply(res, 200, await prepareOrder(body));
    }
    if (url.pathname === '/api/orders/execute') {
      if (body.confirm !== 'LIVE TRADE') throw Error('Explizite Echtgeldbestätigung fehlt');
      return reply(res, 200, await executeOrder(String(body.previewId || ''), 'MANUAL'));
    }
    if (url.pathname === '/api/agent/start') {
      if (!AUTO_ALLOWED || !LIVE || !ALLOWLIST.size) throw Error('Autonomes Trading nicht vollständig freigeschaltet');
      const safety = safetyStatus(journal, MAX_REALIZED_LOSS_SOL);
      if (safety.halted) throw Error('Bot wegen Risikostopp blockiert: ' + safety.reasons.join('; '));
      if (fs.existsSync(STOP_FILE)) throw Error('STOP-Datei blockiert den Bot');
      if (body.confirm !== 'ACTIVATE AUTOMATIC LIVE TRADING') throw Error('Autonome Bestätigung fehlt');
      activeUntil = Date.now() + AUTOPILOT_SESSION_HOURS * 3600_000; // Always explicit; not persisted across restart.
      exitOnlyUntil = activeUntil + 2 * 3600_000;
      sessionExpiredLogged = false;
      agentEvent('ACTIVATED', 'Benutzer aktivierte eine ' + AUTOPILOT_SESSION_HOURS + '-Stunden-Session');
      setImmediate(() => agentCycle().catch(e => { lastAgentError = e.message; agentEvent('ERROR', e.message); }));
      return reply(res, 200, { active: true, expiresAt: activeUntil, note: 'Autopilot startet nur nach ausdrücklicher Freigabe. Käufe nur aus Allowlist und innerhalb Limits.' });
    }
    if (url.pathname === '/api/agent/stop') {
      activeUntil = 0;
      exitOnlyUntil = 0; // Explicit kill switch stops all automated transactions.
      agentEvent('MANUAL_STOP', 'Stop über API ausgelöst');
      return reply(res, 200, { active: false, note: 'Gestoppt; ein bereits gesendeter Swap kann nicht rückgängig gemacht werden.' });
    }
    return reply(res, 404, { error: 'Route nicht gefunden' });
  } catch (err) {
    const message = String(err.message || err).slice(0, 320);
    console.error(req.method, req.url, message);
    return reply(res, 400, { error: message });
  }
});
server.listen(PORT, HOST, () => console.log('MemePulse API bound to ' + HOST + ':' + PORT + ', live=' + LIVE + ', signer=' + !!signer));
