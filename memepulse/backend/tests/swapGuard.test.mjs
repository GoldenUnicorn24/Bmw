import test from 'node:test';
import assert from 'node:assert/strict';
import {
  Keypair, PublicKey, SystemProgram, ComputeBudgetProgram,
  TransactionMessage, TransactionInstruction, VersionedTransaction
} from '@solana/web3.js';
import {checkJupiterMessage,verifiedProgramIds,wsolAssociatedAddress} from '../swapGuard.mjs';

const wallet=Keypair.generate();
const mint=Keypair.generate().publicKey.toBase58();
const attacker=Keypair.generate().publicKey;
const TOKEN=new PublicKey('TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA');
const allowed=new Set([SystemProgram.programId.toBase58(),ComputeBudgetProgram.programId.toBase58(),
 TOKEN.toBase58()]);
const blockhash='11111111111111111111111111111111';
const build=(instructions,payer=wallet.publicKey)=>new VersionedTransaction(
 new TransactionMessage({payerKey:payer,recentBlockhash:blockhash,instructions}).compileToV0Message());
const testGuard=(tx,patch={})=>checkJupiterMessage(tx,{
 wallet:wallet.publicKey.toBase58(),allowedProgramIds:allowed,
 maxInputLamports:50_000_000n,orderMint:mint,...patch
});
test('requires explicitly approved Solana program IDs',()=>{
 assert.equal(verifiedProgramIds('').size,0);
 assert.equal(verifiedProgramIds(SystemProgram.programId.toBase58()).size,1);
 assert.throws(()=>verifiedProgramIds('not-a-key'),/Invalid|Non-base58/i);
 const good=build([ComputeBudgetProgram.setComputeUnitLimit({units:500000})]);
 assert.equal(testGuard(good,{allowedProgramIds:new Set()}).approved,false);
 assert.ok(testGuard(good,{allowedProgramIds:new Set()}).reasons.includes('PROGRAM_ALLOWLIST_EMPTY'));
});
test('allows a bounded wrapped-SOL system transfer exclusively to wallet-owned WSOL ATA',()=>{
 const recipient=wsolAssociatedAddress(wallet.publicKey);
 const tx=build([ComputeBudgetProgram.setComputeUnitLimit({units:600_000}),
   ComputeBudgetProgram.setComputeUnitPrice({microLamports:10000}),
   SystemProgram.transfer({fromPubkey:wallet.publicKey,toPubkey:recipient,lamports:10_000_000})]);
 const r=testGuard(tx);
 assert.equal(r.approved,true,r.reasons.join(','));
 assert.equal(r.checkedInstructions,3);
});
test('rejects destination diversion, arbitrary executable program and token authority change',()=>{
 const badTransfer=build([SystemProgram.transfer({
   fromPubkey:wallet.publicKey,toPubkey:attacker,lamports:1_000_000})]);
 assert.ok(testGuard(badTransfer).reasons.includes('UNTRUSTED_NATIVE_SOL_TRANSFER'));
 const unknown=build([new TransactionInstruction({
   programId:attacker,keys:[],data:Buffer.from([1])})]);
 assert.ok(testGuard(unknown).reasons.some(x=>x.startsWith('PROGRAM_NOT_REVIEWED')));
 const authority=build([new TransactionInstruction({
   programId:TOKEN,keys:[],data:Buffer.from([6,0])})]);
 assert.ok(testGuard(authority).reasons.some(x=>x.startsWith('UNREVIEWED_TOKEN_INSTRUCTION')));
});
test('rejects wrong wallet, second signature, excess input amount and unexpected compute fees',()=>{
 const valid=build([ComputeBudgetProgram.setComputeUnitLimit({units:300000})]);
 assert.ok(testGuard(valid,{wallet:attacker.toBase58()}).reasons.includes('HOT_WALLET_MUST_BE_SOLE_FEE_PAYER'));
 const otherSigner=Keypair.generate();
 const two=build([SystemProgram.transfer({
   fromPubkey:otherSigner.publicKey,toPubkey:wallet.publicKey,lamports:1000})]);
 assert.ok(testGuard(two).reasons.includes('UNEXPECTED_ADDITIONAL_SIGNER'));
 const over=build([SystemProgram.transfer({fromPubkey:wallet.publicKey,
   toPubkey:wsolAssociatedAddress(wallet.publicKey),lamports:200_000_000})]);
 assert.ok(testGuard(over).reasons.includes('UNTRUSTED_NATIVE_SOL_TRANSFER'));
 const hugeFee=build([ComputeBudgetProgram.setComputeUnitPrice({microLamports:900_000})]);
 assert.ok(testGuard(hugeFee).reasons.includes('EXCESSIVE_PRIORITY_FEE'));
});
test('Token transferChecked cannot approve another mint or grant authority',()=>{
 const wrongMint=Keypair.generate().publicKey;
 const ins=new TransactionInstruction({programId:TOKEN,
   keys:[{pubkey:wallet.publicKey,isSigner:false,isWritable:true},
     {pubkey:wrongMint,isSigner:false,isWritable:false}],
   data:Buffer.from([12,1,0,0,0,0,0,0,0,9])});
 assert.ok(testGuard(build([ins])).reasons.includes('TOKEN_TRANSFER_MINT_MISMATCH'));
});
