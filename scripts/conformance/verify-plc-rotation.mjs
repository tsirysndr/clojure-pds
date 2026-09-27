import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import plc from '@did-plc/lib'

const input = JSON.parse(await readFile(process.argv[2], 'utf8'))
const resolved = await plc.validateOperationLog(input.did, input.operations)
assert.deepEqual(resolved, input.data)
assert.equal(resolved.rotationKeys[input.position], input.newKey)
assert.equal(resolved.rotationKeys.includes(input.oldKey), false)
console.log('Verified persisted PLC rotation and subsequent operation with @did-plc/lib')
