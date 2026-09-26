import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServiceJwt, verifyJwt } from '@atproto/xrpc-server';
import { P256Keypair, Secp256k1Keypair } from '@atproto/crypto';

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
const generated = [];
for (const Keypair of [P256Keypair, Secp256k1Keypair]) {
  const keypair = await Keypair.create();
  const issuer = 'did:web:reference.example.com';
  const audience = 'did:web:destination.example.com#atproto_pds';
  const method = 'com.atproto.server.createAccount';
  const token = await createServiceJwt({ iss: issuer, aud: audience, lxm: method, keypair });
  generated.push({ token, issuer, audience, method, didKey: keypair.did() });
}
process.stdout.write(JSON.stringify(generated));
