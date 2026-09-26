import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { decodeAll } from '@atproto/lex-cbor'
import { verifyRepoCar } from '@atproto/repo'

const fixture = JSON.parse(await readFile(process.argv[2], 'utf8'))
let seq = 0
const types = []
for (const raw of fixture.frames) {
  const parts = [...decodeAll(Buffer.from(raw, 'base64url'))]
  assert.equal(parts.length, 2)
  const [header, payload] = parts
  assert.equal(header.op, 1)
  assert.ok(payload.seq > seq)
  seq = payload.seq
  types.push(header.t)
  if (header.t === '#commit') {
    await verifyRepoCar(payload.blocks, fixture.did, fixture.didKey)
  } else assert.equal(payload.did, fixture.did)
}
assert.deepEqual(types, ['#identity', '#account', '#commit'])
console.log('Upstream decoded real WebSocket frames and verified their signed repository')
