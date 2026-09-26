import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseCid } from '@atproto/lex-data'
import { readCar, verifyProofs, verifyRepoCar } from '@atproto/repo'

const fixture = JSON.parse(await readFile(process.argv[2], 'utf8'))
const bytes = (value) => Buffer.from(value, 'base64url')
const verified = await verifyRepoCar(bytes(fixture.repo), fixture.did, fixture.didKey)
assert.equal(verified.creates.length, fixture.recordCount)
for (const proof of fixture.proofs) {
  const claim = { collection: proof.collection, rkey: proof.rkey, cid: proof.cid ? parseCid(proof.cid) : null }
  const result = await verifyProofs(bytes(proof.car), [claim], fixture.did, fixture.didKey)
  assert.equal(result.verified.length, 1)
  assert.equal(result.unverified.length, 0)
  // The verifier must also reject a deliberately false claim.
  const wrong = { ...claim, cid: claim.cid ? null : parseCid(fixture.blockCids[1]) }
  const negative = await verifyProofs(bytes(proof.car), [wrong], fixture.did, fixture.didKey)
  assert.equal(negative.verified.length, 0)
  assert.equal(negative.unverified.length, 1)
}
const blocks = await readCar(bytes(fixture.blocks))
assert.equal(blocks.roots.length, 0)
for (const cid of fixture.blockCids) assert.ok(blocks.blocks.get(parseCid(cid)))
console.log('Upstream verified signed export, inclusion/absence proofs, and rootless block CAR')
