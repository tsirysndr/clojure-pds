import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleRequest, deliver } from './handler.mjs';

test('auth, validation, and durable deduplication', async () => {
  const values = new Map();
  let sends = 0;
  const storage = { get: async k => values.get(k), put: async (k, v) => values.set(k, v) };
  const env = { PDS_EMAIL_TOKEN: 'test-secret', PDS_EMAIL_FROM: 'pds@example.com',
    EMAIL: { send: async () => { sends++; } },
    DELIVERY: { idFromName: x => x, get: () => ({ fetch: req => deliver(req, env, storage) }) } };
  const message = { to: 'user@example.com', from: env.PDS_EMAIL_FROM, subject: 'Verify', text: 'code',
    idempotencyKey: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' };
  const request = (body = message, token = 'test-secret') => new Request('https://email.example.com/', {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
    'Idempotency-Key': message.idempotencyKey }, body: JSON.stringify(body),
  });
  assert.equal((await handleRequest(request(message, 'wrong'), env)).status, 401);
  assert.equal((await handleRequest(request({ ...message, from: 'other@example.com' }), env)).status, 400);
  assert.equal((await handleRequest(request(), env)).status, 200);
  assert.equal((await handleRequest(request(), env)).status, 200);
  assert.equal(sends, 1);
  assert.equal((await handleRequest(request({ ...message, text: 'changed' }), env)).status, 409);
  assert.equal(sends, 1);
});
