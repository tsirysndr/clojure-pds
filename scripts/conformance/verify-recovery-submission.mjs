import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import plc from '@did-plc/lib';
const require = createRequire(import.meta.url);
const plcRequire = createRequire(require.resolve('@did-plc/lib'));
const { cidForCbor } = plcRequire('@atproto/common');
const fixtures = JSON.parse(await readFile(process.argv[2], 'utf8'));
const originalNow = Date.now;
try {
  for (const f of fixtures) {
    let chain = [];
    const nullified = new Set();
    for (const row of f.entries) {
      Date.now = () => Date.parse(row.createdAt);
      const result = await plc.assureValidNextOp(f.did, chain, row.operation);
      for (const cid of result.nullified) nullified.add(cid.toString());
      chain = chain.filter(row => !nullified.has(row.cid.toString()));
      chain.push({...row, cid: await cidForCbor(row.operation), createdAt: new Date(row.createdAt)});
    }
    for (const row of f.entries) assert.equal(nullified.has(row.cid), row.nullified);
    Date.now = () => Date.parse(f.now);
    if (f.valid) {
      const result = await plc.assureValidNextOp(f.did, chain, f.operation);
      assert.deepEqual(result.nullified.map(cid => cid.toString()), f.nullified);
    } else {
      await assert.rejects(plc.assureValidNextOp(f.did, chain, f.operation));
    }
  }
} finally { Date.now = originalNow; }
console.log('Verified externally signed recovery submission plans against the pinned PLC library');
