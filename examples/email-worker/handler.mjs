const json = (status, value) => Response.json(value, { status });
const address = value => typeof value === 'string' && value.length <= 320 && /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(value);
const hash = async value => [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].map(b => b.toString(16).padStart(2, '0')).join('');

export async function handleRequest(request, env) {
  if (request.method !== 'POST') return new Response(null, { status: 405, headers: { Allow: 'POST' } });
  if (!env.PDS_EMAIL_TOKEN || request.headers.get('Authorization') !== `Bearer ${env.PDS_EMAIL_TOKEN}`) {
    return json(401, { error: 'Unauthorized' });
  }
  if (request.headers.get('Content-Type')?.split(';')[0] !== 'application/json') return json(415, { error: 'InvalidContentType' });
  const reader = request.body?.getReader();
  if (!reader) return json(400, { error: 'InvalidMessage' });
  let size = 0;
  const chunks = [];
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 100000) { await reader.cancel(); return json(413, { error: 'TooLarge' }); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  let message;
  try { message = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { return json(400, { error: 'InvalidMessage' }); }
  if (!message || typeof message !== 'object' || Array.isArray(message)) return json(400, { error: 'InvalidMessage' });
  const { to, from, subject, text, idempotencyKey } = message;
  if (!address(to) || !address(from) || from !== env.PDS_EMAIL_FROM ||
      typeof subject !== 'string' || subject.length < 1 || subject.length > 200 || /[\r\n]/.test(subject) ||
      typeof text !== 'string' || text.length < 1 || text.length > 16000 ||
      typeof idempotencyKey !== 'string' || !/^[0-9a-f-]{36}$/.test(idempotencyKey) ||
      request.headers.get('Idempotency-Key') !== idempotencyKey) return json(400, { error: 'InvalidMessage' });
  const id = env.DELIVERY.idFromName(idempotencyKey);
  return env.DELIVERY.get(id).fetch(new Request('https://delivery.internal/', {
    method: 'POST', body: JSON.stringify({ to, from, subject, text }),
  }));
}

export async function deliver(request, env, storage) {
  const body = await request.text();
  const fingerprint = await hash(body);
  const prior = await storage.get('receipt');
  if (prior) return prior.fingerprint === fingerprint
    ? json(200, { accepted: true }) : json(409, { error: 'IdempotencyConflict' });
  try {
    await env.EMAIL.send(JSON.parse(body));
    await storage.put('receipt', { fingerprint });
    return json(200, { accepted: true });
  } catch {
    return json(503, { error: 'DeliveryFailed' });
  }
}
