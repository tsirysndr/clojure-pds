// Test-only software authenticator. Node crypto generates/signs independently of
// the Java WebAuthn verifier. Private keys are ephemeral fixture data only.
import {generateKeyPairSync, createPrivateKey, createHash, randomBytes, sign} from 'node:crypto';
let input = '';
for await (const chunk of process.stdin) input += chunk;
const args = JSON.parse(input);
const b64 = b => Buffer.from(b).toString('base64url');
const unb64 = s => Buffer.from(s, 'base64url');
const hash = b => createHash('sha256').update(b).digest();
function head(major, n) {
  if (n < 24) return Buffer.from([(major << 5) | n]);
  if (n < 256) return Buffer.from([(major << 5) | 24, n]);
  if (n < 65536) { const b = Buffer.alloc(3); b[0] = (major << 5) | 25; b.writeUInt16BE(n, 1); return b; }
  throw new Error('Fixture CBOR length out of bounds');
}
function cbor(value) {
  if (typeof value === 'number') return value >= 0 ? head(0, value) : head(1, -1 - value);
  if (typeof value === 'string') { const b = Buffer.from(value); return Buffer.concat([head(3, b.length), b]); }
  if (Buffer.isBuffer(value)) return Buffer.concat([head(2, value.length), value]);
  if (value instanceof Map) return Buffer.concat([head(5, value.size), ...[...value].flatMap(([k, v]) => [cbor(k), cbor(v)])]);
  throw new Error('Unsupported fixture CBOR');
}
const options = args.options.publicKey;
const mode = args.mode ?? 'register';
let credential = args.credential;
if (mode === 'register') {
  const algorithm = args.algorithm ?? 'ES256';
  const pair = algorithm === 'EdDSA' ? generateKeyPairSync('ed25519') : algorithm === 'RS256'
    ? generateKeyPairSync('rsa', {modulusLength: 2048}) : generateKeyPairSync('ec', {namedCurve: 'prime256v1'});
  credential = {algorithm, id: b64(randomBytes(32)), privateKey: pair.privateKey.export({format: 'jwk'}),
                publicKey: pair.publicKey.export({format: 'jwk'}), userHandle: options.user.id};
}
const clientData = Buffer.from(JSON.stringify({type: mode === 'register' ? 'webauthn.create' : 'webauthn.get',
  challenge: options.challenge, origin: args.origin ?? 'https://pds.example.com', crossOrigin: false, ...(args.clientData ?? {})}));
const flags = args.flags ?? (mode === 'register' ? 0x45 : 0x05);
const counter = Buffer.alloc(4); counter.writeUInt32BE(args.counter ?? (mode === 'register' ? 0 : 1));
const rpId = args.rpId ?? options.rp?.id ?? options.rpId;
let authenticatorData = Buffer.concat([hash(rpId), Buffer.from([flags]), counter]);
const response = {id: credential.id, rawId: credential.id, type: 'public-key', clientExtensionResults: {},
                  response: {clientDataJSON: b64(clientData)}};
if (mode === 'register') {
  const jwk = credential.publicKey;
  const key = credential.algorithm === 'EdDSA'
    ? new Map([[1, 1], [3, -8], [-1, 6], [-2, unb64(jwk.x)]])
    : credential.algorithm === 'RS256' ? new Map([[1, 3], [3, -257], [-1, unb64(jwk.n)], [-2, unb64(jwk.e)]])
    : new Map([[1, 2], [3, -7], [-1, 1], [-2, unb64(jwk.x)], [-3, unb64(jwk.y)]]);
  const id = unb64(credential.id), length = Buffer.alloc(2); length.writeUInt16BE(id.length);
  authenticatorData = Buffer.concat([authenticatorData, Buffer.alloc(16), length, id, cbor(key)]);
  response.response.attestationObject = b64(cbor(new Map([['fmt', 'none'], ['attStmt', new Map()], ['authData', authenticatorData]])));
  response.response.transports = ['internal'];
  response.clientExtensionResults.credProps = {rk: true};
} else {
  const signature = sign(credential.algorithm === 'EdDSA' ? null : 'sha256', Buffer.concat([authenticatorData, hash(clientData)]),
                         createPrivateKey({key: credential.privateKey, format: 'jwk'}));
  if (args.corruptSignature) signature[signature.length - 1] ^= 1;
  response.response.authenticatorData = b64(authenticatorData);
  response.response.signature = b64(signature);
  response.response.userHandle = credential.userHandle;
}
process.stdout.write(JSON.stringify({response, credential}));
