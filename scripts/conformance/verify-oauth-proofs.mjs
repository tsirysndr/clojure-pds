import assert from 'node:assert/strict';
import { createHash, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const input = JSON.parse(await readFile(process.argv[2], 'utf8'));
const encode = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');
const thumbprint = ({ crv, kty, x, y }) => createHash('sha256').update(JSON.stringify({ crv, kty, x, y })).digest('base64url');
for (const token of input.tokens) {
  const [h, p, s] = token.split('.');
  const header = JSON.parse(Buffer.from(h, 'base64url'));
  assert.equal(header.typ, 'dpop+jwt');
  assert.equal(header.alg, 'ES256');
  assert.equal(thumbprint(header.jwk), input.jkt);
  assert(verify('sha256', Buffer.from(`${h}.${p}`), { key: createPublicKey({ key: header.jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')));
  assert(!verify('sha256', Buffer.from(`${h}.${p}x`), { key: createPublicKey({ key: header.jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, Buffer.from(s, 'base64url')));
}
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = publicKey.export({ format: 'jwk' });
const unsigned = `${encode({ typ: 'dpop+jwt', alg: 'ES256', jwk })}.${encode(input.claims)}`;
const sig = sign('sha256', Buffer.from(unsigned), { key: privateKey, dsaEncoding: 'ieee-p1363' });
const n = BigInt('0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
const s = BigInt(`0x${sig.subarray(32).toString('hex')}`);
const alternate = Buffer.concat([sig.subarray(0, 32), Buffer.from((n - s).toString(16).padStart(64, '0'), 'hex')]);
process.stdout.write(JSON.stringify({ jkt: thumbprint(jwk), tokens: [sig, alternate].map((bytes) => `${unsigned}.${bytes.toString('base64url')}`) }));
