import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { request as httpRequest } from 'node:http';
import { NodeOAuthClient, JoseKey, requestLocalLock } from '@atproto/oauth-client-node';

const fixture = JSON.parse(await readFile(process.argv[2], 'utf8'));
const origin = fixture.origin;
const calls = [];
// Only transport is redirected. The SDK still generates PKCE, assertions and
// DPoP proofs for the advertised HTTPS origin and verifies discovery/identity.
async function localFetch(input, init) {
  const request = new Request(input, init);
  const url = new URL(request.url);
  assert.ok([origin, `https://${fixture.handle}`].includes(url.origin), 'Unexpected external request');
  const headers = new Headers(request.headers);
  headers.set('Host', url.host);
  const body = ['GET', 'HEAD'].includes(request.method) ? undefined : await request.arrayBuffer();
  const response = await new Promise((resolve, reject) => {
    const outgoing = httpRequest(`${fixture.transport}${url.pathname}${url.search}`, {
      method: request.method, headers: Object.fromEntries(headers), signal: request.signal,
    }, incoming => {
      const chunks = [];
      incoming.on('data', chunk => chunks.push(chunk));
      incoming.on('error', reject);
      incoming.on('end', () => {
        const responseHeaders = new Headers();
        for (let i = 0; i < incoming.rawHeaders.length; i += 2) {
          responseHeaders.append(incoming.rawHeaders[i], incoming.rawHeaders[i + 1]);
        }
        resolve(new Response([204, 304].includes(incoming.statusCode) ? null : Buffer.concat(chunks), {
          status: incoming.statusCode, headers: responseHeaders,
        }));
      });
    });
    outgoing.on('error', reject);
    outgoing.end(body ? Buffer.from(body) : undefined);
  });
  // Preserve the logical response URL, just as a DNS/TLS loopback fixture would.
  Object.defineProperty(response, 'url', { value: url.href });
  const form = body && headers.get('Content-Type')?.startsWith('application/x-www-form-urlencoded')
    ? new URLSearchParams(new TextDecoder().decode(body)) : null;
  calls.push({ path: url.pathname, status: response.status, grant: form?.get('grant_type'),
    assertion: form?.has('client_assertion'), nonce: response.headers.has('DPoP-Nonce') });
  return response;
}
function store() {
  const values = new Map();
  return { async get(key) { return values.get(key); }, async set(key, value) { values.set(key, value); },
    async del(key) { values.delete(key); } };
}
const client = new NodeOAuthClient({
  clientMetadata: fixture.metadata, fetch: localFetch, requestLock: requestLocalLock,
  stateStore: store(), sessionStore: store(),
  keyset: fixture.privateJwk ? [await JoseKey.fromJWK(fixture.privateJwk)] : undefined,
});
const authorization = await client.authorize(origin, {
  scope: fixture.metadata.scope, prompt: fixture.signup ? 'create' : 'login', state: 'fixture-state',
});
assert.equal(authorization.origin, origin);
assert.equal(authorization.pathname, '/oauth/authorize');
assert.ok(authorization.searchParams.has('request_uri'));

// HTTP-only controller for the browser protocol: independent cookies, CSRF,
// primary authentication, attachment, then explicit approval. No client tokens
// or signed proof construction is implemented by this controller.
const cookies = new Map();
async function browser(path, body, csrf) {
  const headers = { 'Sec-Fetch-Mode': body ? 'cors' : 'navigate', 'Sec-Fetch-Dest': body ? 'empty' : 'document', Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
  if (body) Object.assign(headers, { Origin: origin, 'Content-Type': 'application/json', 'X-CSRF-Token': csrf });
  const response = await localFetch(new URL(path, origin), {
    method: body ? 'POST' : 'GET', headers, body: body ? JSON.stringify(body) : undefined,
  });
  for (const cookie of response.headers.getSetCookie()) {
    const [name, value] = cookie.split(';')[0].split('=');
    cookies.set(name, value);
  }
  assert.equal(response.headers.has('Access-Control-Allow-Origin'), false, 'Cookie routes must not expose wildcard CORS');
  assert.ok(response.status === 200 || response.status === 303, `Browser ${new URL(path, origin).pathname}: HTTP ${response.status}`);
  return { response, json: response.headers.get('Content-Type')?.startsWith('application/json') ? await response.json() : null };
}
const start = await browser(authorization);
assert.equal(start.response.status, 303);
const flow = start.response.headers.get('Location');
const initial = (await browser(`${flow}/state`)).json;
assert.equal(initial.parameters.prompt, fixture.signup ? 'create' : 'login');
const anonymous = (await browser('/account/session')).json;
const owner = (await browser(`/account/action/${fixture.signup ? 'signup' : 'login/password'}`,
  fixture.signup ? { handle: fixture.handle, email: fixture.email, password: fixture.password }
    : { identifier: fixture.did, password: fixture.password }, anonymous.csrf)).json;
assert.equal(owner.stage, 'authenticated');
assert.equal(owner.accessJwt, undefined);
const consent = (await browser(`${flow}/attach`, { accountCsrf: owner.csrf }, initial.csrf)).json;
assert.equal(consent.did, fixture.did);
assert.ok(Array.isArray(consent.permissions));
if (fixture.metadata.scope.includes('repo:com.example.note?action=create')) {
  assert.ok(consent.permissions.includes('Create public records in com.example.note.'));
}
if (fixture.metadata.scope.includes('include:')) {
  assert.equal(consent['permission-sets'][0].title, 'Post and connect');
  assert.deepEqual(consent['permission-sets'][0].permissions, ['Create public records in com.example.note.']);
}
const decision = (await browser(`${flow}/decide`, { approve: true }, consent.csrf)).json;
const callback = new URL(decision.location);
assert.equal(callback.origin + callback.pathname, 'https://app.example.com/callback');
assert.equal(callback.searchParams.get('source'), 'login');
assert.equal(callback.searchParams.get('iss'), origin);
const { session, state } = await client.callback(callback.searchParams);
assert.equal(state, 'fixture-state');
assert.equal(session.did, fixture.did);
assert.equal(session.serverMetadata.issuer, origin);
async function identity() {
  const response = await session.fetchHandler('/xrpc/com.atproto.server.getSession');
  assert.equal(response.status, 200);
  const result = await response.json();
  assert.equal(result.did, fixture.did);
  assert.equal(result.email, (fixture.metadata.scope.includes('transition:email') || fixture.metadata.scope.includes('account:email')) ? fixture.email : undefined);
}
await identity();
const written = await session.fetchHandler('/xrpc/com.atproto.repo.createRecord', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
    repo: fixture.did, collection: 'com.example.note', rkey: 'upstream-oauth', validate: false,
    record: { $type: 'com.example.note', text: 'Written by the upstream OAuth client' },
  }),
});
assert.equal(written.status, 200);
assert.equal((await written.json()).uri, `at://${fixture.did}/com.example.note/upstream-oauth`);
const refreshed = await session.getTokenInfo(true);
assert.equal(refreshed.sub, fixture.did);
assert.equal(refreshed.expired, false);
await identity();
await session.signOut();
// signOut deliberately swallows endpoint errors upstream: assert the wire result.
assert.ok(calls.some(c => c.path === '/oauth/revoke' && c.status === 200));
assert.ok(calls.some(c => c.path === '/oauth/token' && c.grant === 'refresh_token' && c.status === 200));
for (const path of ['/.well-known/oauth-protected-resource', '/.well-known/oauth-authorization-server', '/.well-known/did.json']) {
  assert.ok(calls.some(c => c.path === path && c.status === 200), `Missing discovery ${path}`);
}
assert.ok(calls.some(c => c.path === '/oauth/par' && c.status === 400), 'Initial nonce challenge');
for (const call of calls.filter(c => ['/oauth/par', '/oauth/token', '/oauth/revoke'].includes(c.path))) {
  assert.equal(call.nonce, true);
  assert.equal(call.assertion, Boolean(fixture.privateJwk));
}
process.stdout.write(JSON.stringify({ did: session.did, refreshed: true, revoked: true,
  signup: fixture.signup, confidential: Boolean(fixture.privateJwk) }) + '\n');
