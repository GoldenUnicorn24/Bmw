import test from 'node:test';
import assert from 'node:assert/strict';
import bs58 from 'bs58';
import {Keypair,VersionedTransaction,TransactionMessage,ComputeBudgetProgram,
  SystemProgram,TOKEN_PROGRAM_ID,PublicKey} from '@solana/web3.js';
import {inspectSwapTransaction,validateSwapSimulation,checkJupiterExecution,
  verifySwapChainReceipt,waitForConfirmedReceipt} from '../swapGuard.mjs';
const wallet=Keypair.generate();
const token=Keypair.generate().publicKey.toBase58();
const recentBlockhash='11111111111111111111111111111111';
function signed(ixs){
 const msg=new TransactionMessage({payerKey:wallet.publicKey,recentBlockhash,
  instructions:ixs}).compileToV0Message();
 const tx=new VersionedTransaction(msg);tx.sign([wallet]);return tx;
}
const intent={side:'BUY',mint:token,amountLamports:'10000000'};
const wsolAta=PublicKey.findProgramAddressSync([
 wallet.publicKey.toBuffer(),TOKEN_PROGRAM_ID.toBuffer(),
 new PublicKey('So11111111111111111111111111111111111111112').toBuffer()
],new PublicKey('ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL'))[0];
const good=signed([ComputeBudgetProgram.setComputeUnitLimit({units:180000})]);
const sig=bs58.encode(good.signatures[0]);
test('rejects unsigned route, direct SOL redirection and fee-payer mismatch',()=>{
 assert.equal(inspectSwapTransaction(good,wallet.publicKey,intent).signerCount,1);
 const wrapping=signed([SystemProgram.transfer({
  fromPubkey:wallet.publicKey,toPubkey:wsolAta,lamports:10000000})]);
 assert.equal(inspectSwapTransaction(wrapping,wallet.publicKey,intent).signerCount,1);
 assert.throws(()=>inspectSwapTransaction(wrapping,wallet.publicKey,{
  ...intent,amountLamports:'9000000'}),/capped own WSOL/);
 const redirected=signed([SystemProgram.transfer({
  fromPubkey:wallet.publicKey,toPubkey:Keypair.generate().publicKey,lamports:10000000})]);
 assert.throws(()=>inspectSwapTransaction(redirected,wallet.publicKey,intent),/Direct native SOL transfers/);
 assert.throws(()=>inspectSwapTransaction(good,Keypair.generate().publicKey,intent),/fee payer/);
});
test('Jupiter signature and exact actual amounts must match local signed order',()=>{
 const order={amountLamports:'10000000',estimatedOutputAtomic:'1000'};
 const success={status:'Success',signature:sig,totalInputAmount:'10000000',
  totalOutputAmount:'995'};
 assert.equal(checkJupiterExecution(success,good,order,{maxSlippageBps:100}).outputAtomic,'995');
 assert.throws(()=>checkJupiterExecution({...success,signature:'wrong'},good,order),/signature/);
 assert.throws(()=>checkJupiterExecution({...success,totalInputAmount:'10000001'},good,order),/more input/);
 assert.throws(()=>checkJupiterExecution({...success,totalOutputAmount:'900'},good,order),/slippage/);
 assert.throws(()=>checkJupiterExecution({...success,totalInputAmount:null},good,order),/invalid/);
 assert.throws(()=>checkJupiterExecution({...success,status:'Failed'},good,order),/not confirmed/);
});
test('onchain receipt must prove minted token credit and SOL debit to signer',()=>{
 const receipt={meta:{err:null,preBalances:[100000000],postBalances:[89500000],
  preTokenBalances:[],postTokenBalances:[{mint:token,owner:wallet.publicKey.toBase58(),
   uiTokenAmount:{amount:'995'}}]},
  transaction:{signatures:[sig],message:{accountKeys:[{pubkey:wallet.publicKey.toBase58()}]}}};
 const o={signature:sig,side:'BUY',mint:token,wallet:wallet.publicKey.toBase58(),
  inputAtomic:'10000000',outputAtomic:'995'};
 assert.equal(verifySwapChainReceipt(receipt,o).confirmed,true);
 assert.throws(()=>verifySwapChainReceipt({
  ...receipt,meta:{...receipt.meta,postTokenBalances:[]}},o),/did not receive/);
 assert.throws(()=>verifySwapChainReceipt({
  ...receipt,meta:{...receipt.meta,err:{InstructionError:[1,'Custom']}}},o),/No successful/);
});
test('SELL must debit sold tokens and receive SOL, or stay unknown',()=>{
 const o={signature:sig,side:'SELL',mint:token,wallet:wallet.publicKey.toBase58(),
  inputAtomic:'2000',outputAtomic:'11000000'};
 const receipt={meta:{err:null,preBalances:[100000000],postBalances:[110000000],
  preTokenBalances:[{mint:token,owner:o.wallet,uiTokenAmount:{amount:'2000'}}],
  postTokenBalances:[{mint:token,owner:o.wallet,uiTokenAmount:{amount:'0'}}]},
  transaction:{signatures:[sig],message:{accountKeys:[{pubkey:o.wallet}]}}};
 assert.equal(verifySwapChainReceipt(receipt,o).confirmed,true);
 assert.throws(()=>verifySwapChainReceipt({...receipt,meta:{...receipt.meta,
  postBalances:[99000000]}},o),/Native SOL credit/);
});
test('no receipt after all polling rounds must fail closed',async()=>{
 const fake=async()=>null;
 await assert.rejects(()=>waitForConfirmedReceipt(fake,sig,{polls:2,waitMs:1}),/unavailable/);
 const chain=async()=>({meta:{err:null}});
 assert.ok(await waitForConfirmedReceipt(chain,sig,{polls:1}));
});

test('preflight simulation must explicitly succeed within compute budget',()=>{
 assert.deepEqual(validateSwapSimulation({value:{err:null,unitsConsumed:900000}}),
  {simulated:true,unitsConsumed:900000});
 assert.throws(()=>validateSwapSimulation({value:{err:'CustomError',unitsConsumed:1000}}),
  /simulation/);
 assert.throws(()=>validateSwapSimulation({value:{err:null,unitsConsumed:1800000}}),
  /compute/);
 assert.throws(()=>validateSwapSimulation(null),/simulation/);
});
