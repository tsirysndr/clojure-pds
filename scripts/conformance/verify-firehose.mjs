// Consumes a running PDS firehose with the official subscription client, the
// way a relay does: every commit's MST inclusion proofs and signature are
// verified by upstream against the signing key published in our DID document,
// and identity events are checked for a bidirectional handle binding.
//
//   node scripts/conformance/verify-firehose.mjs <fixture.json>
//
// The fixture supplies {service, did, handle, signingKey, expect}. Output is a
// JSON summary on stdout; a non-zero exit means interop failed.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Firehose } from '@atproto/sync'

const fixture = JSON.parse(await readFile(process.argv[2], 'utf8'))
const service = fixture.service.replace(/^http/, 'ws')

const xrpc = async (method, params) => {
  const url = new URL(`/xrpc/${method}`, fixture.service)
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value)
  const response = await fetch(url)
  if (!response.ok) return null
  return response.json()
}

const resolveDocument = async (did) => (await xrpc('com.atproto.identity.resolveDid', { did }))?.didDoc ?? null

// The key that verifies commits comes from the published document, so a
// mismatch between what we sign with and what we advertise fails here.
const resolveKey = async (did) => {
  const document = await resolveDocument(did)
  const method = document?.verificationMethod?.find((entry) => entry.id.endsWith('#atproto'))
  assert.ok(method, `no #atproto verification method for ${did}`)
  const key = `did:key:${method.publicKeyMultibase}`
  assert.equal(key, fixture.signingKey, 'published key differs from the repository signing key')
  return key
}

const events = []
const kinds = new Set()
let failure = null

const firehose = new Firehose({
  service,
  // Replay from the start of the window so writes made before this consumer
  // connected are included; without a cursor the subscription starts live.
  getCursor: () => 0,
  idResolver: {
    did: { resolve: resolveDocument, resolveAtprotoKey: resolveKey },
    handle: {
      resolve: async (handle) => (await xrpc('com.atproto.identity.resolveHandle', { handle }))?.did,
    },
  },
  handleEvent: async (event) => {
    kinds.add(event.event)
    if (['create', 'update', 'delete'].includes(event.event)) {
      events.push({ event: event.event, collection: event.collection, rkey: event.rkey })
    } else if (event.event === 'identity') {
      events.push({ event: event.event, handle: event.handle })
    }
  },
  onError: (error) => {
    failure ??= error
  },
})

firehose.start()

const records = () => events.filter((entry) => entry.event !== 'identity')
const deadline = Date.now() + 30_000
while (records().length < fixture.expect && Date.now() < deadline && !failure) {
  await new Promise((resolve) => setTimeout(resolve, 100))
}
await firehose.destroy()

if (failure) throw failure
assert.ok(
  records().length >= fixture.expect,
  `expected at least ${fixture.expect} record events, saw ${records().length}`,
)
assert.ok(records().every((e) => typeof e.collection === 'string' && typeof e.rkey === 'string'))
// An identity event that resolved its handle proves the bidirectional binding.
assert.ok(
  events.some((entry) => entry.event === 'identity' && entry.handle === fixture.handle),
  'no identity event verified the handle binding',
)

process.stdout.write(JSON.stringify({ events, kinds: [...kinds] }))
