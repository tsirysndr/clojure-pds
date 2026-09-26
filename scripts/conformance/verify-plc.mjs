import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import plc from '@did-plc/lib';
import { Secp256k1Keypair, P256Keypair } from '@atproto/crypto';

// Resolve the library's matching CID utilities. Use current upstream signing:
// its bundled crypto 0.1.0 P-256 signer predates PLC's mandatory low-S rule.
// These packages are test oracles and never run in the Clojure PDS.
const require = createRequire(import.meta.url);
const plcRequire = createRequire(require.resolve('@did-plc/lib'));
const { cidForCbor } = plcRequire('@atproto/common');
const input = JSON.parse(await readFile(process.argv[2], 'utf8'));
assert.equal(await plc.didForCreateOp(input.operations[0]), input.did);
for (let i = 0; i < input.operations.length; i++) {
  assert.equal((await cidForCbor(input.operations[i])).toString(), input.cids[i]);
}
assert.deepEqual(await plc.validateOperationLog(input.did, input.operations.slice(0, -1)), input.activeData);
assert.equal(await plc.validateOperationLog(input.did, input.operations), null);
await assert.rejects(plc.validateOperationLog(input.did, [input.operations[0], input.operations.at(-1)]));

// The published 0.0.4 API uses Date.now() for recovery. Give it each directory
// timestamp so the same boundary case is compared without wall-clock drift.
let canonical = [];
const nullified = new Set();
const realNow = Date.now;
try {
  for (const row of input.audit) {
    Date.now = () => Date.parse(row.createdAt);
    const result = await plc.assureValidNextOp(row.did, canonical, row.operation);
    for (const cid of result.nullified) nullified.add(cid.toString());
    canonical = canonical.filter((entry) => !nullified.has(entry.cid.toString()));
    canonical.push({ ...row, cid: await cidForCbor(row.operation), createdAt: new Date(row.createdAt) });
  }
} finally { Date.now = realNow; }
assert.equal(canonical.at(-1).cid.toString(), input.auditHead);
for (const row of input.audit) assert.equal(nullified.has(row.cid), row.nullified);

const output = [];
for (const Keypair of [Secp256k1Keypair, P256Keypair]) {
  const key = await Keypair.create();
  const { op, did } = await plc.createOp({ signingKey: key.did(), rotationKeys: [key.did()],
    handle: 'reference.example.com', pds: 'https://reference-pds.example.com', signer: key });
  const updated = await plc.updateHandleOp(op, key, 'renamed.example.com');
  const tombstone = await plc.tombstoneOp(await cidForCbor(updated), key);
  output.push({ did, operations: [op, updated, tombstone], head: (await cidForCbor(tombstone)).toString() });
}
const legacyKey = await Secp256k1Keypair.create();
const legacy = await plc.deprecatedSignCreate({ type: 'create', prev: null, signingKey: legacyKey.did(),
  recoveryKey: legacyKey.did(), handle: 'legacy.example.com', service: 'https://legacy-pds.example.com' }, legacyKey);
const legacyUpdate = await plc.updateRotationKeysOp(legacy, legacyKey, [legacyKey.did()]);
output.push({ did: await plc.didForCreateOp(legacy), operations: [legacy, legacyUpdate], head: (await cidForCbor(legacyUpdate)).toString() });
process.stdout.write(JSON.stringify(output));
