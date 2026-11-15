// Jupiter v2 live-order defence-in-depth. All checks fail closed.
// These are NOT a substitute for an independent transaction-program audit.
import bs58 from 'bs58';
import {SystemProgram, TOKEN_PROGRAM_ID, TOKEN_2022_PROGRAM_ID,PublicKey} from '@solana/web3.js';
const WSOL=new PublicKey('So11111111111111111111111111111111111111112');
const ATA_PROGRAM=new PublicKey('ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL');
const associatedWsol=(wallet)=>PublicKey.findProgramAddressSync(
  [wallet.toBuffer(),TOKEN_PROGRAM_ID.toBuffer(),WSOL.toBuffer()],ATA_PROGRAM)[0];

const strictAtomic=(v,label)=>{
  if(!/^[0-9]{1,32}$/.test(String(v??'')))throw Error(label+' missing/invalid');
  const n=BigInt(v);if(n<=0n)throw Error(label+' must be > 0');return n;
};
const toSignature=(b)=>bs58.encode(Buffer.from(b));
export function inspectSwapTransaction(tx,signer,order){
  if(!tx?.message||!tx?.signatures||!signer||!order)
    throw Error('Missing swap transaction, signer or approved order intent');
  const m=tx.message;
  if(m.version!==0)throw Error('Only audited v0 transaction envelope is supported');
  const keys=m.staticAccountKeys||[];
  if(m.header?.numRequiredSignatures!==1||tx.signatures.length!==1||!keys[0]?.equals(signer))
    throw Error('Swap must use exactly one signer and wallet as the fee payer');
  if(!Array.isArray(m.compiledInstructions)||!m.compiledInstructions.length)
    throw Error('Swap contains no executable instructions');
  if(m.compiledInstructions.length>28||m.addressTableLookups?.length>10)
    throw Error('Abnormally complex swap transaction');
  // Program ids must be unambiguously available without untrusted ALT lookup.
  for(const ix of m.compiledInstructions){
    const program=keys[ix.programIdIndex];
    if(!program)throw Error('Cannot independently identify a swap program id');
    if(program.equals(SystemProgram.programId)){
      const data=Buffer.from(ix.data||[]);
      if(data.length!==12||data.readUInt32LE(0)!==2)
        throw Error('Only exact native SOL -> own wrapped-SOL ATA transfers are permitted');
      const from=keys[ix.accountKeyIndexes?.[0]];
      const to=keys[ix.accountKeyIndexes?.[1]];
      const lamports=data.readBigUInt64LE(4);
      const allowed=BigInt(order.amountLamports||'0');
      // Only BUY may wrap native SOL, only into the deterministic WSOL ATA
      // controlled by the same signer, never to arbitrary tip/fee accounts.
      if(order.side!=='BUY'||!from?.equals(signer)||!to?.equals(associatedWsol(signer))||
        lamports<=0n||lamports>allowed)
        throw Error('Direct native SOL transfers in swap are forbidden except capped own WSOL wrapping');
    }
    if(program.equals(TOKEN_PROGRAM_ID)||program.equals(TOKEN_2022_PROGRAM_ID)){
      const op=Number(Buffer.from(ix.data||[])[0]);
      // CloseAccount (9) forwards rent/SOL. Permit refund only to the wallet.
      if(op===9){
        const targetIndex=ix.accountKeyIndexes?.[1];
        if(!Number.isInteger(targetIndex)||!keys[targetIndex]?.equals(signer))
          throw Error('Closing token account to an unapproved address');
      }
    }
  }
  return {payer:signer.toBase58(),version:0,signerCount:1,
    instructionCount:m.compiledInstructions.length,additionalAccounts:m.addressTableLookups?.length||0};
}
export function validateSwapSimulation(sim){
  if(!sim||!sim.value||sim.value.err!==null)
    throw Error('RPC swap simulation did not succeed; refusing live broadcast');
  const units=Number(sim.value.unitsConsumed);
  if(!Number.isFinite(units)||units<=0||units>1400000)
    throw Error('RPC swap simulation compute budget invalid');
  return {simulated:true,unitsConsumed:units};
}
export function checkJupiterExecution(result,tx,order,{maxSlippageBps=100}={}){
  const expectedSig=toSignature(tx?.signatures?.[0]||[]);
  if(!expectedSig||expectedSig==='11111111111111111111111111111111')
    throw Error('Cannot match missing local signature');
  if(!result||result.status!=='Success')throw Error('Jupiter swap outcome is not confirmed successful');
  if(result.signature!==expectedSig)throw Error('Jupiter signature differs from locally signed transaction');
  const input=strictAtomic(result.totalInputAmount,'Actual input amount');
  const output=strictAtomic(result.totalOutputAmount,'Actual output amount');
  const quotedInput=strictAtomic(order?.amountLamports,'Quoted input amount');
  const quotedOutput=strictAtomic(order?.estimatedOutputAtomic,'Quoted output amount');
  if(input>quotedInput)throw Error('Live swap consumed more input tokens than approved');
  const slippage=Number(maxSlippageBps);
  if(!Number.isInteger(slippage)||slippage<0||slippage>200)
    throw Error('Invalid configured slippage tolerance');
  const minimum=(quotedOutput*BigInt(10000-slippage))/10000n;
  if(output<minimum)throw Error('Output violates estimated slippage protection');
  return {signature:expectedSig,inputAtomic:input.toString(),
    outputAtomic:output.toString(),minExpectedAtomic:minimum.toString()};
}
function tokenAtoms(arr,mint,owner){
  let total=0n;
  for(const x of arr||[]){
    if(x?.mint!==mint||x?.owner!==owner)continue;
    const amount=x.uiTokenAmount?.amount;
    if(!/^\d+$/.test(String(amount??'')))throw Error('Unparseable on-chain token amount');
    total+=BigInt(amount);
  }
  return total;
}
export function verifySwapChainReceipt(receipt,{
 signature,side,mint,wallet,inputAtomic,outputAtomic,minimumSolDecrease=0n
}){
  if(!receipt?.meta||receipt.meta.err!==null)
    throw Error('No successful on-chain swap receipt');
  if(!receipt.transaction?.signatures?.includes(signature))
    throw Error('Chain receipt signature mismatch');
  const x=receipt.transaction.message?.accountKeys||[];
  const ownerIndex=x.findIndex(v=>(typeof v==='string'?v:v?.pubkey)===wallet);
  if(ownerIndex<0)throw Error('Wallet was not a participant in confirmed receipt');
  const before=receipt.meta.preBalances?.[ownerIndex],after=receipt.meta.postBalances?.[ownerIndex];
  if(!Number.isSafeInteger(before)||!Number.isSafeInteger(after))
    throw Error('On-chain native SOL balances unavailable');
  const tokenBefore=tokenAtoms(receipt.meta.preTokenBalances,mint,wallet);
  const tokenAfter=tokenAtoms(receipt.meta.postTokenBalances,mint,wallet);
  const spentOrGained=tokenAfter-tokenBefore;
  const amt=strictAtomic(outputAtomic,'Execution output');
  if(side==='BUY'){
    if(spentOrGained<amt)throw Error('Wallet did not receive quoted token amount on chain');
    if(before<=after)throw Error('SOL debit not visible on chain');
    // Native SOL sent can include fee/rent; no exact equality with Jupiter inAmount.
  } else if(side==='SELL'){
    const input=strictAtomic(inputAtomic,'Execution input');
    if(-spentOrGained<input)throw Error('Wallet token balance did not fall by sold quantity');
    // Actual SOL can appear as native SOL or remain wrapped; native settlement
    // is required to avoid presenting wrapped SOL as spendable native balance.
    if(after<=before)throw Error('Native SOL credit was not observed');
  } else throw Error('Unrecognised swap side');
  return {confirmed:true,signature,wallet,side,mint,
    changedTokenAtomic:spentOrGained.toString(),
    nativeSolDeltaLamports:String(BigInt(after)-BigInt(before))};
}
export async function waitForConfirmedReceipt(rpc,signature,{polls=6,waitMs=1900}={}){
  if(!/^[1-9A-HJ-NP-Za-km-z]{80,90}$/.test(signature))
    throw Error('Invalid swap signature');
  for(let i=0;i<polls;i++){
    const receipt=await rpc('getTransaction',[signature,{
      commitment:'confirmed',encoding:'jsonParsed',maxSupportedTransactionVersion:0}]);
    if(receipt?.meta){
      if(receipt.meta.err!==null)throw Error('On-chain swap failed');
      return receipt;
    }
    if(i+1<polls)await new Promise(resolve=>setTimeout(resolve,waitMs));
  }
  throw Error('Receipt unavailable; order requires manual reconciliation before retry');
}
