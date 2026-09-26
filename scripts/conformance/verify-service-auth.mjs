import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { verifyJwt } from '@atproto/xrpc-server';
import { P256Keypair } from '@atproto/crypto';

const fixtures = JSON.parse(await readFile(process.argv[2], 'utf8'));
const wrong = await P256Keypair.create();
for (const { token, issuer, audience, method, didKey } of fixtures) {
  const resolve = async (iss) => {
    assert.equal(iss, issuer);
    return didKey;
  };
  const claims = await verifyJwt(token, audience, method ?? null, resolve);
  assert.equal(claims.iss, issuer);
  assert.equal(claims.aud, audience);
  assert.equal(claims.lxm, method ?? undefined);
  assert.equal(typeof claims.jti, 'string');
  assert.equal(claims.exp - claims.iat, 60);
  await assert.rejects(verifyJwt(token, 'did:web:wrong.example.com', method ?? null, resolve));
  await assert.rejects(verifyJwt(token, audience, 'com.example.wrongMethod', resolve));
  await assert.rejects(verifyJwt(token, audience, method ?? null, async () => wrong.did()));
}
process.stdout.write('Verified service JWT signatures, audiences, and methods');
