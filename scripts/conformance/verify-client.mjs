// Drives a running PDS with the official AT Protocol client, the way a real
// application would. The client validates our responses with its own bundled
// Lexicons, so shape errors surface here rather than in our own assertions.
//
//   node scripts/conformance/verify-client.mjs <fixture.json>
//
// The fixture supplies {service, handle, email, password, inviteCode}. Output
// is a JSON summary on stdout; a non-zero exit means interop failed.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { AtpAgent } from '@atproto/api'

const fixture = JSON.parse(await readFile(process.argv[2], 'utf8'))
const agent = new AtpAgent({ service: fixture.service })
const collection = 'app.bsky.feed.post'
const summary = {}

// --- discovery -------------------------------------------------------------
const described = await agent.com.atproto.server.describeServer()
assert.ok(described.success)
assert.equal(typeof described.data.did, 'string')
summary.serverDid = described.data.did

// --- account creation ------------------------------------------------------
await agent.createAccount({
  handle: fixture.handle,
  email: fixture.email,
  password: fixture.password,
  ...(fixture.inviteCode ? { inviteCode: fixture.inviteCode } : {}),
})
assert.equal(agent.session?.handle, fixture.handle)
const did = agent.session.did
summary.did = did

// --- session lifecycle -----------------------------------------------------
const session = await agent.com.atproto.server.getSession()
assert.equal(session.data.did, did)

await agent.login({ identifier: fixture.handle, password: fixture.password })
assert.equal(agent.session.did, did)

// --- record writes, reads and pagination ------------------------------------
const created = []
for (let n = 0; n < 3; n++) {
  const result = await agent.com.atproto.repo.createRecord({
    repo: did,
    collection,
    record: {
      $type: collection,
      text: `interop post ${n}`,
      createdAt: new Date(Date.now() + n).toISOString(),
    },
  })
  assert.match(result.data.uri, /^at:\/\//)
  assert.equal(typeof result.data.cid, 'string')
  created.push(result.data)
}
summary.created = created.length

const rkey = created[0].uri.split('/').pop()
const fetched = await agent.com.atproto.repo.getRecord({ repo: did, collection, rkey })
assert.equal(fetched.data.uri, created[0].uri)
assert.equal(fetched.data.value.text, 'interop post 0')

const firstPage = await agent.com.atproto.repo.listRecords({ repo: did, collection, limit: 2 })
assert.equal(firstPage.data.records.length, 2)
assert.equal(typeof firstPage.data.cursor, 'string')
const secondPage = await agent.com.atproto.repo.listRecords({
  repo: did,
  collection,
  limit: 2,
  cursor: firstPage.data.cursor,
})
assert.equal(secondPage.data.records.length, 1)
const listed = [...firstPage.data.records, ...secondPage.data.records].map((r) => r.uri)
assert.deepEqual([...listed].sort(), created.map((r) => r.uri).sort())

// --- blob upload and a record that references it ---------------------------
const bytes = new Uint8Array(6 * 1024).fill(7)
const uploaded = await agent.com.atproto.repo.uploadBlob(bytes, { encoding: 'image/png' })
assert.equal(uploaded.data.blob.mimeType, 'image/png')
assert.equal(uploaded.data.blob.size, bytes.length)
summary.blobCid = uploaded.data.blob.ref.toString()

await agent.com.atproto.repo.putRecord({
  repo: did,
  collection: 'app.bsky.actor.profile',
  rkey: 'self',
  record: { $type: 'app.bsky.actor.profile', displayName: 'Interop', avatar: uploaded.data.blob },
})

const blob = await agent.com.atproto.sync.getBlob({ did, cid: summary.blobCid })
assert.deepEqual(new Uint8Array(blob.data), bytes)

const missing = await agent.com.atproto.repo.listMissingBlobs()
assert.deepEqual(missing.data.blobs, [])

// --- repository description and sync surface -------------------------------
const describedRepo = await agent.com.atproto.repo.describeRepo({ repo: did })
assert.equal(describedRepo.data.did, did)
assert.ok(describedRepo.data.collections.includes(collection))
assert.equal(describedRepo.data.didDoc.id, did)

const head = await agent.com.atproto.sync.getLatestCommit({ did })
assert.equal(typeof head.data.cid, 'string')
assert.equal(typeof head.data.rev, 'string')

const car = await agent.com.atproto.sync.getRepo({ did })
assert.ok(car.data.byteLength > 0)
summary.carBytes = car.data.byteLength

const repos = await agent.com.atproto.sync.listRepos()
assert.ok(repos.data.repos.some((repo) => repo.did === did))

// --- deletion and its error shape ------------------------------------------
await agent.com.atproto.repo.deleteRecord({ repo: did, collection, rkey })
await assert.rejects(
  () => agent.com.atproto.repo.getRecord({ repo: did, collection, rkey }),
  (error) => {
    assert.equal(error.status, 400)
    assert.equal(typeof error.error, 'string')
    return true
  },
)

// --- identity resolution ----------------------------------------------------
const resolved = await agent.com.atproto.identity.resolveHandle({ handle: fixture.handle })
assert.equal(resolved.data.did, did)

process.stdout.write(JSON.stringify(summary))
