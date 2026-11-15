import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import bs58 from 'bs58';
import {Keypair,PublicKey,Transaction,SystemProgram} from '@solana/web3.js';
import {planWithdrawal,createWithdrawalService,SOL_MAINNET_GENESIS} from '../withdrawal.mjs';
const secret='w'.repeat(40);
const wallet=Keypair.generate();
const destination=Keypair.generate().publicKey.toBase58();
const amount='0.01';
const fixtures=()=>fs.mkdtempSync(path.join(os.tmpdir(),'memepulse-withdraw-'));
const config=(file)=>({ENABLE_WALLET_WITHDRAWALS:'YES_I_ACCEPT_RESTRICTED_SOL_WITHDRAWALS',
 WITHDRAWAL_SECOND_FACTOR:secret,WITHDRAWAL_DESTINATION_B58:destination,
 WITHDRAWAL_LOG_PATH:file,MAX_WITHDRAWAL_SOL:'.25'});
test('withdrawal restrictions reject empty destination, overspending, off-curve/system program',()=>{
 assert.throws(()=>planWithdrawal({amountSol:1,balanceLamports:500000000n,
  maxLamports:250000000n,from:wallet.publicKey.toBase58(),destination}),/maximum/);
 assert.throws(()=>planWithdrawal({amountSol:.49,balanceLamports:500000000n,
  maxLamports:500000000n,minReserveLamports:20000000n,
  from:wallet.publicKey.toBase58(),destination}),/enough/);
 assert.throws(()=>planWithdrawal({amountSol:.01,balanceLamports:1000000000n,
  from:wallet.publicKey.toBase58(),destination:PublicKey.findProgramAddressSync([Buffer.from('withdrawal')],SystemProgram.programId)[0].toBase58()}),/on-curve/);
 const p=planWithdrawal({amountSol:.01,balanceLamports:1000000000n,
  from:wallet.publicKey.toBase58(),destination});
 assert.equal(p.amountLamports,10_000_000n);
});
test('disabled by default; needs a separate second factor and no active bot',async()=>{
 const temp=fixtures();
 try{
  const env={...config(path.join(temp,'withdrawals.json')),ENABLE_WALLET_WITHDRAWALS:'NO'};
  const svc=createWithdrawalService({env,signer:wallet,
   rpc:async()=>SOL_MAINNET_GENESIS,balanceSol:async()=>1000000000n,
   busy:()=>false,otherActivity:()=>false});
  assert.equal(svc.status().enabled,false);
  await assert.rejects(()=>svc.prepare(.01,secret),/disabled/);
  const active=createWithdrawalService({env:config(path.join(temp,'active.json')),signer:wallet,
   rpc:async()=>SOL_MAINNET_GENESIS,balanceSol:async()=>1000000000n,
   busy:()=>false,otherActivity:()=>true});
  await assert.rejects(()=>active.prepare(.01,secret),/Stop automatic/);
  const inactive=createWithdrawalService({env:config(path.join(temp,'inactive.json')),signer:wallet,
   rpc:async()=>SOL_MAINNET_GENESIS,balanceSol:async()=>1000000000n,
   busy:()=>false,otherActivity:()=>false});
  await assert.rejects(()=>inactive.prepare(.01,'short-secret'),/authorisation/);
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
});
test('mock withdrawal builds exactly one SOL transfer to fixed external address and stores confirmation',async()=>{
 const temp=fixtures(),journal=path.join(temp,'withdrawals.json');
 let decoded=null,queries=0;
 const rpc=async(method,params)=>{
  if(method==='getGenesisHash')return SOL_MAINNET_GENESIS;
  if(method==='getLatestBlockhash')return {value:{blockhash:'11111111111111111111111111111111'}};
  if(method==='sendTransaction'){
   decoded=Transaction.from(Buffer.from(params[0],'base64'));
   assert.equal(decoded.instructions.length,1);
   assert.equal(decoded.instructions[0].programId.toBase58(),SystemProgram.programId.toBase58());
   assert.equal(decoded.instructions[0].keys[1].pubkey.toBase58(),destination);
   return bs58.encode(decoded.signature);
  }
  if(method==='getSignatureStatuses'){queries++;return {value:[{err:null,confirmationStatus:'confirmed'}]};}
  throw Error('Unexpected RPC '+method);
 };
 try{
  const svc=createWithdrawalService({env:config(journal),signer:wallet,rpc,
   balanceSol:async()=>1000000000n,busy:()=>false,otherActivity:()=>false});
  const quote=await svc.prepare(amount,secret);
  assert.equal(quote.destination,destination);
  await assert.rejects(()=>svc.execute(quote.previewId,secret,'WRONG'),/confirmation/);
  const outcome=await svc.execute(quote.previewId,secret,'CONFIRM SOLANA WITHDRAWAL');
  assert.equal(outcome.status,'SUCCESS');
  assert.ok(decoded);
  assert.equal(queries,1);
  const log=JSON.parse(fs.readFileSync(journal));
  assert.equal(log.length,1);
  assert.equal(log[0].amountLamports,'10000000');
  assert.equal(log[0].status,'SUCCESS');
  assert.equal(svc.uncertain(),false);
  await assert.rejects(()=>svc.execute(quote.previewId,secret,'CONFIRM SOLANA WITHDRAWAL'),/expired/);
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
});
test('RPC mismatch and unknown on-chain transaction halt further withdrawals',async()=>{
 const temp=fixtures();
 try{
  const svc=createWithdrawalService({env:config(path.join(temp,'journal.json')),signer:wallet,
   rpc:async(method)=>method==='getGenesisHash'?SOL_MAINNET_GENESIS:
    method==='getLatestBlockhash'?{value:{blockhash:'11111111111111111111111111111111'}}:'wrong-signature',
   balanceSol:async()=>1000000000n,busy:()=>false,otherActivity:()=>false});
  const p=await svc.prepare(.01,secret);
  await assert.rejects(()=>svc.execute(p.previewId,secret,'CONFIRM SOLANA WITHDRAWAL'),/DO NOT RETRY/);
  assert.equal(svc.uncertain(),true);
  await assert.rejects(()=>svc.prepare(.01,secret),/Reconcile chain/);
 }finally{fs.rmSync(temp,{recursive:true,force:true});}
});
