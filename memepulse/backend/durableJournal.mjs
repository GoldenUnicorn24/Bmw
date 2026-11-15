// Durable, non-truncating transaction journals.
// An UNKNOWN/SUBMITTED record must never vanish because a buffer reached N rows.
// Atomically replace the full ledger and fsync file+parent directory to reduce
// lost state after a crash. Real deployments also need snapshots and backups.
import fs from 'node:fs';
import path from 'node:path';
export function saveDurableJournal(filename,entries){
 if(!Array.isArray(entries))throw Error('Invalid journal: expected array');
 const dir=path.dirname(path.resolve(filename));
 fs.mkdirSync(dir,{recursive:true,mode:0o700});
 const resolved=path.resolve(filename);
 const temp=path.join(dir,'.'+path.basename(resolved)+'.'+process.pid+'.'+Date.now()+'.tmp');
 let fd;
 try{
   fd=fs.openSync(temp,fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_EXCL,0o600);
   fs.writeFileSync(fd,JSON.stringify(entries,null,2));
   fs.fsyncSync(fd);
   fs.closeSync(fd);fd=undefined;
   fs.renameSync(temp,resolved);
   try {
     const dfd=fs.openSync(dir,fs.constants.O_RDONLY);
     try{fs.fsyncSync(dfd)}finally{fs.closeSync(dfd)}
   }catch(e){if(process.platform==='linux')throw e;}
 }finally{
   if(fd!==undefined)fs.closeSync(fd);
   try{fs.unlinkSync(temp)}catch(e){if(e.code!=='ENOENT')throw e;}
 }
}
export function loadJournal(filename){
 try{
   const parsed=JSON.parse(fs.readFileSync(filename,'utf8'));
   if(!Array.isArray(parsed))throw Error('Journal is not an array');
   return parsed;
 }catch(e){
   if(e.code==='ENOENT')return [];
   throw Error('Refusing to start with unreadable transaction journal: '+e.message);
 }
}
