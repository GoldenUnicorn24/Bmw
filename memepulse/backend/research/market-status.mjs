// No coupling to live-trading permissions; only server-side file reading.
import {researchStatus} from './status.mjs';
export function separateMarketReports({memePath='',pennyPath='',legacySolanaPath=''}={}){
 const normalize=(file,market)=>({market,...researchStatus(file),liveInfluence:false,
   disclaimer:'Research-only, never an approval to trade'});
 return {
   updatedAt:new Date().toISOString(),productionAllowed:false,
   markets:{
     solanaMemeCoins:normalize(memePath,'SOLANA_MEME_COINS'),
     usPennyStocks:normalize(pennyPath,'US_LISTED_PENNY_STOCK'),
     legacyUnclassifiedSolana:normalize(legacySolanaPath,'SOLANA_GENERAL_POOLS')
   },
   stockTradingAvailable:false,stockBrokerConnected:false,
   cryptoStockModelsShared:false
 };
}
