import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { IncludeScope, RepoPermission, RpcPermission } from '@atproto/oauth-scopes';

const normalize = (permissions) => [...new Set(permissions.flatMap((p) => {
  if (p instanceof RepoPermission) return p.collection.flatMap((c) => p.action.map((a) => JSON.stringify(['repo', c, a])));
  if (p instanceof RpcPermission) return p.lxm.map((m) => JSON.stringify(['rpc', m, p.aud]));
  throw new Error('Unexpected resource permission');
}))].sort();
const fixtures = JSON.parse(await readFile(process.argv[2], 'utf8'));
for (const fixture of fixtures) {
  const include = IncludeScope.fromString(fixture.scope);
  assert.ok(include);
  const expected = fixture.permissions.map((p) => RepoPermission.fromString(p) || RpcPermission.fromString(p));
  assert.deepEqual(normalize(include.toPermissions(fixture.set)), normalize(expected));
}
console.log(`Validated ${fixtures.length} permission-set expansions against the upstream implementation`);
