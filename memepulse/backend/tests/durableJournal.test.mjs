import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {loadJournal,saveDurableJournal} from '../durableJournal.mjs';

test('unknown and submitted chain transfers survive more than 1000 historical entries',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'memepulse-fsync-'));
  try{
    const file=path.join(dir,'private','trades.json');
    assert.deepEqual(loadJournal(file),[]);
    const data=[{id:'EARLY_UNCERTAIN',status:'UNKNOWN',signature:'tx1'},
      {id:'EARLY_PENDING',status:'SUBMITTED',signature:'tx2'}];
    for(let i=0;i<1400;i++)data.push({id:'good'+i,status:'SUCCESS'});
    saveDurableJournal(file,data);
    const recovered=loadJournal(file);
    assert.equal(recovered.length,1402);
    assert.equal(recovered[0].status,'UNKNOWN');
    assert.equal(recovered[1].status,'SUBMITTED');
    assert.equal(fs.statSync(file).mode&0o777,0o600);
    assert.ok(fs.readdirSync(path.dirname(file)).every(x=>!x.endsWith('.tmp')));
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('corrupted ledger fails closed instead of silently creating a blank ledger',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'memepulse-corrupt-'));
  try{
    const file=path.join(dir,'trades.json');
    fs.writeFileSync(file,'INVALID!');
    assert.throws(()=>loadJournal(file),/Refusing to start/);
    fs.writeFileSync(file,JSON.stringify({oops:true}));
    assert.throws(()=>loadJournal(file),/Refusing to start/);
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('rejects accidental saves of wrong journal types',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'memepulse-types-'));
  try{assert.throws(()=>saveDurableJournal(path.join(dir,'trades.json'),{}),/array/)}
  finally{fs.rmSync(dir,{recursive:true,force:true});}
});
