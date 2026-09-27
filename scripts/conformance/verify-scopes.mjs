import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { RepoPermission, BlobPermission, RpcPermission, AccountPermission, IdentityPermission } from '@atproto/oauth-scopes';
const fixtures = JSON.parse(await readFile(process.argv[2], 'utf8'));
const classes = { repo: RepoPermission, blob: BlobPermission, rpc: RpcPermission, account: AccountPermission, identity: IdentityPermission };
for (const { scope, permission: expected } of fixtures) {
  const actual = classes[expected.resource].fromString(scope);
  assert.ok(actual, scope);
  if (expected.resource === 'repo') {
    assert.deepEqual(new Set(actual.collection), new Set(expected.collections));
    assert.deepEqual(new Set(actual.action), new Set(expected.actions));
    for (const collection of ['com.example.note', 'com.example.other']) for (const action of ['create', 'update', 'delete']) {
      assert.equal(actual.matches({ collection, action }), expected.actions.includes(action)
        && (expected.collections.includes('*') || expected.collections.includes(collection)));
    }
  } else if (expected.resource === 'blob') {
    assert.deepEqual(new Set(actual.accept), new Set(expected.accept));
    for (const mime of ['image/png', 'text/html', 'video/mp4', 'application/ld+json']) {
      assert.equal(actual.matches({ mime }), expected.accept.some(value => value === '*/*' || value === mime
        || (value.endsWith('/*') && mime.startsWith(value.slice(0, -1)))));
    }
  } else if (expected.resource === 'rpc') {
    assert.equal(actual.aud, expected.audience);
    assert.deepEqual(new Set(actual.lxm), new Set(expected.methods));
    for (const aud of ['did:web:api.example.com#appview', 'did:web:other.example.com#appview']) {
      for (const lxm of ['com.example.getNote', 'com.example.other']) {
        assert.equal(actual.matches({ aud, lxm }), (expected.audience === '*' || expected.audience === aud)
          && (expected.methods.includes('*') || expected.methods.includes(lxm)));
      }
    }
  } else if (expected.resource === 'identity') {
    assert.equal(actual.attr, expected.attribute);
    assert.equal(actual.matches({ attr: '*' }), expected.attribute === '*');
    assert.equal(actual.matches({ attr: 'handle' }), true);
  } else {
    assert.equal(actual.attr, expected.attribute);
    assert.deepEqual(actual.action, [expected.action]);
  }
}
console.log(`Validated ${fixtures.length} permission scopes against the upstream parser and matchers`);
