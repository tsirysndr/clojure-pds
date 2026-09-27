import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import plc from '@did-plc/lib';

const input = JSON.parse(await readFile(process.argv[2], 'utf8'));
for (let i = 0; i < input.operations.length; i++) {
  const data = await plc.validateOperationLog(input.did, input.operations.slice(0, i + 1));
  assert.deepEqual(data.rotationKeys, input.expectedKeys[i]);
}
// A server-authorized removal cannot bypass PLC's higher-priority recovery window.
const require = createRequire(import.meta.url);
const plcRequire = createRequire(require.resolve('@did-plc/lib'));
const { cidForCbor } = plcRequire('@atproto/common');
const realNow = Date.now;
let canonical = [];
const nullified = new Set();
try {
  for (const row of input.audit) {
    Date.now = () => Date.parse(row.createdAt);
    const result = await plc.assureValidNextOp(input.did, canonical, row.operation);
    for (const cid of result.nullified) nullified.add(cid.toString());
    canonical = canonical.filter(row => !nullified.has(row.cid.toString()));
    canonical.push({ ...row, cid: await cidForCbor(row.operation), createdAt: new Date(row.createdAt) });
  }
} finally { Date.now = realNow; }
assert.equal(canonical.at(-1).cid.toString(), input.auditHead);
for (const row of input.audit) assert.equal(nullified.has(row.cid), row.nullified);
console.log('Verified recovery-key replacement, ordering, removal and recovery priority');
