import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Lexicons, jsonToLex } from '@atproto/lexicon'

const root = new URL('../../resources/lexicons/', import.meta.url)
const index = JSON.parse(await readFile(new URL('index.json', root), 'utf8'))
const docs = await Promise.all(Object.keys(index.schemas).map(async id =>
  JSON.parse(await readFile(new URL(`${id}.json`, root), 'utf8'))))
const lexicons = new Lexicons(docs)
const observations = JSON.parse(await readFile(process.argv[2], 'utf8'))
for (const item of observations) {
  const { id, kind, value, header } = item
  try {
    if (kind === 'output') {
      const encoding = lexicons.getDefOrThrow(id).output?.encoding
      assert.equal(item.encoding, encoding ?? null)
      if (encoding === 'application/json') {
        assert.equal(item['content-type'], encoding)
        lexicons.assertValidXrpcOutput(id, jsonToLex(value))
      } else if (!encoding) assert.equal(item.empty, true)
      else {
        assert.equal(item.binary, true)
        assert.equal(typeof item['content-type'], 'string')
        if (encoding !== '*/*') assert.equal(item['content-type'], encoding)
      }
    } else if (kind === 'message') {
      assert.equal(Object.hasOwn(value, '$type'), false)
      if (header.op === 1) lexicons.assertValidXrpcMessage(id, { ...jsonToLex(value), $type: `${id}${header.t}` })
      else {
        assert.equal(header.op, -1)
        assert.equal(Object.hasOwn(header, 't'), false)
        assert.equal(typeof value.error, 'string')
        if (Object.hasOwn(value, 'message')) assert.equal(typeof value.message, 'string')
      }
    } else assert.fail('Malformed observation')
  } catch (error) {
    // Avoid printing tokens or email proofs from captured test responses.
    throw new Error(`${id} ${kind} ${header?.t ?? ''}: ${error.message}`)
  }
}
console.log(`Verified ${observations.length} actual PDS responses and frames`)
