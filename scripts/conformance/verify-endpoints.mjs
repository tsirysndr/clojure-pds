import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { Lexicons } from '@atproto/lexicon'

const root = new URL('../../resources/lexicons/', import.meta.url)
const index = JSON.parse(await readFile(new URL('index.json', root), 'utf8'))
const docs = await Promise.all(Object.keys(index.schemas).map(async id =>
  JSON.parse(await readFile(new URL(`${id}.json`, root), 'utf8'))))
const lexicons = new Lexicons(docs)
const fixtures = JSON.parse(await readFile(process.argv[2], 'utf8'))
for (const { id, kind, value, valid } of fixtures) {
  let accepted = true
  try {
    if (kind === 'input') lexicons.assertValidXrpcInput(id, value)
    else lexicons.assertValidXrpcParams(id, value)
  } catch { accepted = false }
  assert.equal(accepted, valid, `${id} ${kind}: ${JSON.stringify(value)}`)
}
console.log(`Verified ${fixtures.length} endpoint input cases against @atproto/lexicon`)
