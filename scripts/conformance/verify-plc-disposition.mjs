import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import plc from '@did-plc/lib';

const require = createRequire(import.meta.url);
const plcRequire = createRequire(require.resolve('@did-plc/lib'));
const { cidForCbor } = plcRequire('@atproto/common');
const fixtures = JSON.parse(await readFile(process.argv[2], 'utf8'));
const realNow = Date.now;
try {
  for (const fixture of fixtures) {
    let canonical = [];
    const nullified = new Set();
    for (const row of fixture.entries) {
      Date.now = () => Date.parse(row.createdAt);
      const result = await plc.assureValidNextOp(fixture.did, canonical, row.operation);
      for (const cid of result.nullified) nullified.add(cid.toString());
      canonical = canonical.filter(row => !nullified.has(row.cid.toString()));
      canonical.push({ ...row, cid: await cidForCbor(row.operation), createdAt: new Date(row.createdAt) });
    }
    for (const row of fixture.entries) assert.equal(nullified.has(row.cid), row.nullified);
    Date.now = () => Date.parse(fixture.entries.at(-1).createdAt) + 1;
    if (fixture.expected === 'unresolved' && !fixture.unknownParent) {
      await plc.assureValidNextOp(fixture.did, canonical, fixture.operation);
    } else {
      await assert.rejects(plc.assureValidNextOp(fixture.did, canonical, fixture.operation));
    }
  }
} finally { Date.now = realNow; }
console.log('Verified PLC queue dispositions against the pinned reference library');
