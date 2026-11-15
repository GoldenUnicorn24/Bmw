// PRELOAD ONLY FOR TEST CHILD PROCESSES. Never import in production.
// Fully synthetic RPC/Jupiter/market responses; absolutely no network calls.
if(process.env.MEMEPULSE_MOCK_TEST_NETWORK!=='CI_FAKE_ONLY')
  throw Error('Refusing to activate mock RPC outside CI_FAKE_ONLY');
import { Keypair,TransactionMessage,VersionedTransaction,SystemProgram,ComputeBudgetProgram } from '@solana/web3.js';
import bs58 from 'bs58';
import {wsolAssociatedAddress} from '../swapGuard.mjs';
const signer=Keypair.fromSecretKey(bs58.decode(process.env.SOLANA_PRIVATE_KEY_B58));
const mint=process.env.TRADING_ALLOWLIST;
const reply=(obj,status=200)=>new Response(JSON.stringify(obj),{
  status,headers:{'content-type':'application/json'}
});
globalThis.fetch=async (link,options={})=>{
 const url=new URL(String(link));
 if(url.hostname==='rpc.synthetic.invalid'){
   const body=JSON.parse(options.body||'{}');
   if(body.method==='getGenesisHash')return reply({jsonrpc:'2.0',id:1,result:'5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d'});
   if(body.method==='getBalance')return reply({jsonrpc:'2.0',id:1,result:{value:1_000_000_000}});
   if(body.method==='getSignatureStatuses')return reply({jsonrpc:'2.0',id:1,
     result:{value:[{err:null,confirmationStatus:'confirmed'}]}});
   if(body.method==='getTokenAccountsByOwner')return reply({jsonrpc:'2.0',id:1,result:{value:[]}});
   throw Error('No fake RPC implementation for '+body.method);
 }
 if(url.hostname==='api.dexscreener.com'&&url.pathname.includes('/token-pairs/v1/solana/')){
   return reply([{baseToken:{address:mint,name:'Mock Token',symbol:'MOCK'},priceUsd:'0.003',
     liquidity:{usd:350_000},volume:{h24:1_500_000,h1:200_000},
     priceChange:{m5:3,h1:7,h6:15,h24:11},txns:{h1:{buys:180,sells:90}},
     pairCreatedAt:Date.now()-360*60_000,fdv:4_000_000,dexId:'mock'}]);
 }
 if(url.hostname==='api.rugcheck.xyz')return reply({score:100,risks:[],
   token:{mintAuthority:null,freezeAuthority:null}});
 if(url.hostname==='api.jup.ag'&&url.pathname==='/swap/v2/order'){
   const amount=Number(url.searchParams.get('amount'));
   const tx=new VersionedTransaction(new TransactionMessage({
     payerKey:signer.publicKey,recentBlockhash:'11111111111111111111111111111111',
     instructions:[ComputeBudgetProgram.setComputeUnitLimit({units:400000}),
       SystemProgram.transfer({fromPubkey:signer.publicKey,
         toPubkey:wsolAssociatedAddress(signer.publicKey),lamports:amount})]
   }).compileToV0Message());
   return reply({requestId:'MOCK_ORDER_ONLY',transaction:Buffer.from(tx.serialize()).toString('base64'),
     inAmount:String(amount),outAmount:'1500000',priceImpactPct:0.2,transactionVersion:0});
 }
 if(url.hostname==='api.jup.ag'&&url.pathname==='/swap/v2/execute'){
   const body=JSON.parse(options.body);
   const signed=VersionedTransaction.deserialize(Buffer.from(body.signedTransaction,'base64'));
   if(!signed.message.staticAccountKeys[0].equals(signer.publicKey))
     throw Error('Mock signed transaction had unexpected payer');
   const actual=bs58.encode(signed.signatures[0]);
   return reply({status:'Success',signature:process.env.MEMEPULSE_MOCK_BAD_SIG==='YES'?
     Keypair.generate().publicKey.toBase58():actual,
     totalInputAmount:'10000000',totalOutputAmount:'1500000'});
 }
 throw Error('Mock test refuses network request: '+String(link).slice(0,200));
};
