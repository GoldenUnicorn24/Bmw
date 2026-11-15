// Fail-closed audit of third-party serialized Jupiter V0 transactions.
// Only approves an explicitly-reviewed set of on-chain programs, a single
// signature from our own wallet, safe budget instructions, and WSOL wrapping.
// Not a full audit of arbitrary swap instructions. Production requires
// independent review of swap program IDs and execution traces.
import { PublicKey, SystemProgram, SystemInstruction, TransactionInstruction } from '@solana/web3.js';

const WSOL = new PublicKey('So11111111111111111111111111111111111111112');
const TOKEN_PROGRAM = new PublicKey('TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA');
const ASSOCIATED_PROGRAM = new PublicKey('ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL');
const COMPUTE_PROGRAM = new PublicKey('ComputeBudget111111111111111111111111111111');
const TOKEN2022_PROGRAM = new PublicKey('TokenzQdBNbLqP5VEhdkAS6EPFzRN3uYWnH1fzQLn');
const MAX_TX_BYTES=1232;
const MAX_COMPUTE_UNITS=1_400_000;
const MAX_PRICE_MICROLAMPORTS=500_000n;

export function verifiedProgramIds(raw) {
  const text=String(raw||'').trim();
  if(!text) return new Set();
  const ids=text.split(',').map(x=>x.trim()).filter(Boolean);
  if(ids.length>20)throw Error('Too many program IDs; reviewed swap allowlist required');
  const keys=new Set(ids.map(id=>new PublicKey(id).toBase58()));
  return keys;
}
export function wsolAssociatedAddress(wallet) {
  return PublicKey.findProgramAddressSync([
    new PublicKey(wallet).toBuffer(),TOKEN_PROGRAM.toBuffer(),WSOL.toBuffer()
  ],ASSOCIATED_PROGRAM)[0];
}
export function checkJupiterMessage(tx,{wallet,allowedProgramIds,maxInputLamports,orderMint,
    maxTransactionBytes=MAX_TX_BYTES}={}){
  const reasons=[];
  const fail=reason=>reasons.push(reason);
  if(!tx||!tx.message||!Array.isArray(tx.signatures))
    return {approved:false,reasons:['UNREADABLE_TRANSACTION']};
  const m=tx.message;
  const owner=new PublicKey(wallet);
  const allowed=allowedProgramIds instanceof Set?allowedProgramIds:verifiedProgramIds(allowedProgramIds);
  if(!allowed.size)fail('PROGRAM_ALLOWLIST_EMPTY');
  if(!Number.isInteger(maxTransactionBytes)||maxTransactionBytes<1||tx.serialize().length>maxTransactionBytes)
    fail('TRANSACTION_PACKET_OVERSIZE');
  if(m.version!==0)fail('REQUIRE_VERSION_ZERO');
  if(m.header?.numRequiredSignatures!==1||tx.signatures.length!==1)
    fail('UNEXPECTED_ADDITIONAL_SIGNER');
  if(!m.staticAccountKeys?.[0]?.equals(owner))
    fail('HOT_WALLET_MUST_BE_SOLE_FEE_PAYER');
  if(!Array.isArray(m.compiledInstructions)||m.compiledInstructions.length===0)
    fail('NO_SWAP_INSTRUCTIONS');
  if(m.compiledInstructions?.length>30)fail('TOO_MANY_INSTRUCTIONS');
  const keys=m.staticAccountKeys||[];
  const recipient=wsolAssociatedAddress(owner);
  const available=BigInt(maxInputLamports||0);
  let priceMicro=0n,computeUnits=200_000;
  const allowedMints=new Set([WSOL.toBase58(),new PublicKey(orderMint).toBase58()]);
  for(const ins of m.compiledInstructions||[]){
    const prog=keys[ins.programIdIndex];
    // Require every PROGRAM ID in static keys: no dynamically ALT-loaded executable target.
    if(!prog){fail('DYNAMIC_PROGRAM_UNREVIEWED');continue;}
    const id=prog.toBase58(),data=Buffer.from(ins.data);
    if(!allowed.has(id)){fail('PROGRAM_NOT_REVIEWED:'+id);continue;}
    const accounts=Array.from(ins.accountKeyIndexes||[]).map(i=>keys[i]||null);
    if(id===SystemProgram.programId.toBase58()){
      if(accounts.some(a=>!a)){fail('UNRESOLVED_SYSTEM_ACCOUNT');continue;}
      try {
        const decoded=new TransactionInstruction({programId:prog,keys:accounts.map(a=>({pubkey:a,isSigner:false,isWritable:true})),data});
        const type=SystemInstruction.decodeInstructionType(decoded);
        if(type!=='Transfer'){fail('UNREVIEWED_SYSTEM_INSTRUCTION:'+type);continue;}
        const {fromPubkey,toPubkey,lamports}=SystemInstruction.decodeTransfer(decoded);
        if(!fromPubkey.equals(owner)||!toPubkey.equals(recipient)||
          BigInt(lamports)>available||BigInt(lamports)<=0n)
          fail('UNTRUSTED_NATIVE_SOL_TRANSFER');
      }catch{fail('UNDECODABLE_SYSTEM_TRANSFER');}
    }
    if(id===COMPUTE_PROGRAM.toBase58()){
      if(data.length===5&&data[0]===2){
        computeUnits=data.readUInt32LE(1);
        if(computeUnits<1||computeUnits>MAX_COMPUTE_UNITS)fail('EXCESSIVE_COMPUTE_UNITS');
      } else if(data.length===9&&data[0]===3){
        priceMicro=data.readBigUInt64LE(1);
        if(priceMicro>MAX_PRICE_MICROLAMPORTS)fail('EXCESSIVE_PRIORITY_FEE');
      } else if(data.length===5&&data[0]===1) {
        fail('UNREVIEWED_COMPUTE_BUDGET_HEAP');
      } else fail('UNKNOWN_COMPUTE_BUDGET_INSTRUCTION');
    }
    if(id===TOKEN_PROGRAM.toBase58()||id===TOKEN2022_PROGRAM.toBase58()){
      // SetAuthority(6), Approve(4), MintTo(7), FreezeAccount(10) etc prohibited.
      // TransferChecked(12) carries a mint argument and is auditable.
      const opcode=data[0];
      const safe=new Set([1,9,12,16,17,18]);
      if(!safe.has(opcode))fail('UNREVIEWED_TOKEN_INSTRUCTION:'+opcode);
      if(opcode===12){
        if(!accounts[1]||!allowedMints.has(accounts[1].toBase58()))
          fail('TOKEN_TRANSFER_MINT_MISMATCH');
      }
      if(opcode===9 && (!accounts[1]||!accounts[1].equals(owner)))
        fail('TOKEN_ACCOUNT_CLOSE_DESTINATION_NOT_WALLET');
    }
    if(id===ASSOCIATED_PROGRAM.toBase58()){
      // Only create ATA for our own wallet and expected mint, never arbitrary recipients.
      if(!accounts[0]?.equals(owner)||!accounts[2]?.equals(owner)||
         !accounts[3]||!allowedMints.has(accounts[3].toBase58()))
         fail('UNREVIEWED_ASSOCIATED_TOKEN_ACCOUNT');
      if(data.length>1||data.length===1&&data[0]>1)
         fail('UNREVIEWED_ATA_INSTRUCTION');
    }
  }
  // Aggregate estimated priority fee, not a guarantee of actual network fee.
  if(BigInt(computeUnits)*priceMicro/1_000_000n>1_000_000n)
    fail('PRIORITY_FEE_OVER_0_001_SOL');
  return {approved:reasons.length===0,reasons:[...new Set(reasons)],
    checkedInstructions:m.compiledInstructions?.length||0,
    programCount:new Set((m.compiledInstructions||[]).map(i=>keys[i.programIdIndex]?.toBase58()).filter(Boolean)).size};
}
