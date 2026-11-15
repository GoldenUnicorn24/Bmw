// Restricted SOL-only manual withdrawal module.
// Never sends automatically, never accepts an arbitrary destination from the client.
// Requires a dedicated, separately held high-entropy authorisation secret.
// The server must never be used with a user's primary wallet.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {PublicKey,Transaction,SystemProgram} from '@solana/web3.js';
import bs58 from 'bs58';
export const SOL_MAINNET_GENESIS='5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d';
const compare=(a,b)=>{
 const x=Buffer.from(String(a||'')),y=Buffer.from(String(b||''));
 return x.length===y.length&&crypto.timingSafeEqual(x,y);
};
function toLamports(value){
 const amount=Number(value);
 if(!Number.isFinite(amount)||amount<=0||amount>10||
   Math.abs(amount*1e9-Math.round(amount*1e9))>1e-4)
   throw Error('Withdrawal amount must be positive SOL, max 9 decimal places');
 return BigInt(Math.round(amount*1e9));
}
export function planWithdrawal({amountSol,balanceLamports,destination,from,maxLamports=250_000_000n,
  minReserveLamports=10_000_000n}){
 const lamports=toLamports(amountSol);
 if(lamports>BigInt(maxLamports))throw Error('Withdrawal exceeds maximum configured per transfer');
 const available=BigInt(balanceLamports)-BigInt(minReserveLamports);
 if(available<lamports)throw Error('Not enough free SOL after fee and safety reserve');
 if(!destination||!from||destination===from)throw Error('External permitted destination required');
 const key=new PublicKey(destination);
 if(!PublicKey.isOnCurve(key.toBytes()))throw Error('Withdrawal destination must be standard on-curve wallet');
 return {amountLamports:lamports,destination:key.toBase58(),from,
   expectedRemainingLamports:available-lamports};
}
function writeAtomic(file,data){
 const folder=path.dirname(file);fs.mkdirSync(folder,{recursive:true,mode:0o700});
 const temp=file+'.'+process.pid+'.tmp';
 fs.writeFileSync(temp,JSON.stringify(data.slice(-300),null,2),{mode:0o600});
 fs.renameSync(temp,file);
}
export function createWithdrawalService({env,signer,rpc,balanceSol,busy,otherActivity}){
 const enabled=env.ENABLE_WALLET_WITHDRAWALS==='YES_I_ACCEPT_RESTRICTED_SOL_WITHDRAWALS';
 const token=env.WITHDRAWAL_SECOND_FACTOR||'';
 const target=env.WITHDRAWAL_DESTINATION_B58||'';
 const maxSol=Number(env.MAX_WITHDRAWAL_SOL||'.25');
 const journalPath=env.WITHDRAWAL_LOG_PATH||'./private/withdrawals.json';
 let journal=[];
 try{journal=JSON.parse(fs.readFileSync(journalPath,'utf8'));
  if(!Array.isArray(journal))throw Error('bad journal');
 }catch(e){if(e.code!=='ENOENT')throw Error('Withdrawal journal invalid: '+e.message);}
 const transactions=journal;
 let prepared=new Map(),inFlight=false;
 const uncertain=()=>transactions.some(x=>['SUBMITTED','UNKNOWN'].includes(x.status));
 if(enabled){
   if(!signer||token.length<32||!target||!Number.isFinite(maxSol)||maxSol<=0||maxSol>10)
     throw Error('Withdrawal feature must have wallet, separate 32-character secret, destination and bounded limit');
   new PublicKey(target);
 }
 const requireReady=(supplied)=>{
   if(!enabled)throw Error('Manual withdrawals are disabled by default');
   if(!compare(supplied,token))throw Error('Separate withdrawal authorisation required');
   if(busy()||otherActivity()||inFlight)throw Error('Stop automatic trading and outstanding swaps before withdrawing');
   if(uncertain())throw Error('Unknown/unconfirmed withdrawal. Reconcile chain before sending again');
 };
 return {
   uncertain,
   status:()=>({enabled,signerReady:!!signer,
     destination:enabled?target:null,maxWithdrawalSol:enabled?maxSol:null,
     pendingReconciliation:uncertain(),manualOnly:true,
     note:'No arbitrary destination, no automatic withdrawal, no bank transfer or stock brokerage'}),
   async prepare(amountSol,secondFactor){
     requireReady(secondFactor);
     if((await rpc('getGenesisHash',[]))!==SOL_MAINNET_GENESIS)
       throw Error('RPC is not Solana mainnet-beta');
     const p=planWithdrawal({amountSol,balanceLamports:await balanceSol(),
       destination:target,from:signer.publicKey.toBase58(),
       maxLamports:BigInt(Math.round(maxSol*1e9))});
     const id=crypto.randomUUID(),created=Date.now();
     prepared.set(id,{...p,id,created});
     for(const [key,x]of prepared)if(Date.now()-x.created>90000)prepared.delete(key);
     return {previewId:id,amountSol:Number(p.amountLamports)/1e9,
       from:p.from,destination:p.destination,asset:'native SOL',network:'Solana mainnet-beta',
       expiresAt:created+60000,
       notice:'Manual approval required. No recovery if you send to the wrong address'};
   },
   async execute(id,secondFactor,confirmation){
     requireReady(secondFactor);
     if(confirmation!=='CONFIRM SOLANA WITHDRAWAL')throw Error('Exact explicit manual confirmation required');
     const p=prepared.get(id);prepared.delete(id);
     if(!p||Date.now()-p.created>60000)throw Error('Withdrawal preview expired');
     inFlight=true;
     try {
       if((await rpc('getGenesisHash',[]))!==SOL_MAINNET_GENESIS)throw Error('Wrong Solana network');
       planWithdrawal({amountSol:Number(p.amountLamports)/1e9,
         balanceLamports:await balanceSol(),destination:target,
         from:signer.publicKey.toBase58(),maxLamports:BigInt(Math.round(maxSol*1e9))});
       if(busy()||otherActivity())throw Error('Trading started during withdrawal preview');
       const entry={id:p.id,at:Date.now(),status:'SUBMITTED',type:'SOL_WITHDRAWAL',
         source:signer.publicKey.toBase58(),destination:target,
         amountLamports:p.amountLamports.toString(),signature:null};
       transactions.push(entry);writeAtomic(journalPath,transactions);
       try {
         const block=await rpc('getLatestBlockhash',[{commitment:'confirmed'}]);
         if(!block?.value?.blockhash)throw Error('No Solana blockhash');
         const tx=new Transaction({feePayer:signer.publicKey,recentBlockhash:block.value.blockhash});
         tx.add(SystemProgram.transfer({fromPubkey:signer.publicKey,toPubkey:new PublicKey(target),
           lamports:Number(p.amountLamports)}));
         if(tx.instructions.length!==1||!tx.instructions[0].programId.equals(SystemProgram.programId))
           throw Error('Unapproved transfer instruction');
         tx.sign(signer);
         const expected=bs58.encode(tx.signature);
         const sent=await rpc('sendTransaction',[tx.serialize().toString('base64'),
           {encoding:'base64',preflightCommitment:'confirmed',skipPreflight:false,maxRetries:0}]);
         entry.signature=String(sent||'');
         if(sent!==expected)throw Error('RPC signature does not match locally signed transaction');
         writeAtomic(journalPath,transactions);
         let status=null;
         for(let i=0;i<5;i++){
           const result=await rpc('getSignatureStatuses',[[expected],{searchTransactionHistory:true}]);
           status=result?.value?.[0]||null;
           if(status?.err)throw Error('Solana transfer execution failed');
           if(['confirmed','finalized'].includes(status?.confirmationStatus))break;
           await new Promise(resolve=>setTimeout(resolve,2500));
         }
         if(!['confirmed','finalized'].includes(status?.confirmationStatus))
           throw Error('Chain confirmation not observed; manual reconciliation required');
         entry.status='SUCCESS';entry.confirmedAt=Date.now();
         writeAtomic(journalPath,transactions);
         return {status:'SUCCESS',signature:expected,destination:target};
       }catch(e){
         entry.status='UNKNOWN';entry.error=String(e.message).slice(0,160);
         writeAtomic(journalPath,transactions);
         throw Error('Withdrawal uncertain. DO NOT RETRY. Verify transaction and balance independently: '+entry.error);
       }
     }finally{inFlight=false;}
   }
 };
}
