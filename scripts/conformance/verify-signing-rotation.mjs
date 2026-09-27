import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { decodeAll } from '@atproto/lex-cbor'
import { MemoryBlockstore, Repo, readCarWithRoot, verifyCommitSig, verifyRepoCar } from '@atproto/repo'

const input = JSON.parse(await readFile(process.argv[2], 'utf8'))
const bytes = s => Buffer.from(s, 'base64url')
await verifyRepoCar(bytes(input.before), input.did, input.oldKey)
await verifyRepoCar(bytes(input.after), input.did, input.newKey)
const before = await readCarWithRoot(bytes(input.before))
const after = await readCarWithRoot(bytes(input.after))
const oldRepo = await Repo.load(new MemoryBlockstore(before.blocks), before.root)
const newRepo = await Repo.load(new MemoryBlockstore(after.blocks), after.root)
assert.equal(oldRepo.commit.data.toString(), newRepo.commit.data.toString())
assert.ok(newRepo.commit.rev > oldRepo.commit.rev)
assert.equal(await verifyCommitSig(newRepo.commit, input.oldKey), false)
assert.equal(await verifyCommitSig(newRepo.commit, input.newKey), true)
const frames = input.frames.map(raw => [...decodeAll(bytes(raw))])
assert.deepEqual(frames.map(([header]) => header.t), ['#identity', '#sync'])
assert.ok(frames[1][1].seq > frames[0][1].seq)
for (const [, payload] of frames) assert.equal(payload.did, input.did)
const checkpoint = await readCarWithRoot(frames[1][1].blocks)
assert.equal(checkpoint.root.toString(), after.root.toString())
assert.deepEqual(Buffer.from(checkpoint.blocks.get(checkpoint.root)), Buffer.from(after.blocks.get(after.root)))
assert.equal(frames[1][1].rev, newRepo.commit.rev)
console.log('Verified key rollover, unchanged MST root and real identity/sync WebSocket frames')
