import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseCid } from '@atproto/lex-data'
import { MemoryBlockstore, MST, Repo, readCarWithRoot, verifyCommitSig } from '@atproto/repo'

const fixture = JSON.parse(await readFile(process.argv[2], 'utf8'))
let previousRev = null
let previousData = null
for (const event of fixture.events) {
  const car = await readCarWithRoot(Buffer.from(event.blocks, 'base64url'))
  const store = new MemoryBlockstore(car.blocks)
  const repo = await Repo.load(store, car.root)
  assert.equal(repo.did, fixture.did)
  assert.ok(await verifyCommitSig(repo.commit, fixture.didKey))
  assert.equal(repo.commit.rev, event.rev)
  assert.equal(event.since, previousRev)
  assert.equal(event.prevData ?? null, previousData)
  let inverted = repo.data
  for (const op of [...event.ops].reverse()) {
    const current = await inverted.get(op.path)
    assert.equal(current?.toString() ?? null, op.cid)
    if (op.action === 'create') inverted = await inverted.delete(op.path)
    else if (op.action === 'update') inverted = await inverted.update(op.path, parseCid(op.prev))
    else if (op.action === 'delete') inverted = await inverted.add(op.path, parseCid(op.prev))
    else assert.fail(`Unknown operation ${op.action}`)
  }
  const expected = previousData ?? (await (await MST.create(new MemoryBlockstore())).getPointer()).toString()
  assert.equal((await inverted.getPointer()).toString(), expected)
  previousData = repo.commit.data.toString()
  previousRev = event.rev
}
console.log('Upstream verified every commit signature and inverted each diff to its previous MST root')
