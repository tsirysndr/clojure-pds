const $ = id => document.getElementById(id);
let state = {}, busy = false, passkeysAvailable = false;
const messages = {
  AuthenticationRequired: 'That account or password was not recognized.',
  InvalidPasskey: 'The passkey could not be verified. Please try again.',
  InvalidToken: 'That code is incorrect, expired, or already used. Try a new code.',
  RateLimitExceeded: 'Too many attempts. Wait a few minutes before trying again.',
  BrowserSessionRequired: 'Your session expired. Sign in again to continue.',
};
function notice(message) { $('notice').textContent = message; $('notice').hidden = !message; }
function render(next) {
  state = next;
  if ('passkeys-available' in next) passkeysAvailable = next['passkeys-available'];
  if (next.origin) { $('server-origin').textContent = next.origin; $('server-name').textContent = `${new URL(next.origin).hostname} PDS`; }
  document.body.dataset.stage = next.stage;
  for (const stage of ['login', 'factor', 'settings']) $(stage).hidden = stage !== (next.stage === 'authenticated' ? 'settings' : next.stage);
  $('logout').hidden = next.stage === 'login';
  $('heading').textContent = next.stage === 'authenticated' ? 'Account security' : next.stage === 'factor' ? 'Verify it’s you' : 'Sign in';
  $('subtitle').textContent = next.handle ? next.handle : 'Enter your username and password';
  $('factor-help').textContent = next.factor === 'totp' ? 'Enter a code from your authenticator app, or one of your saved recovery codes.' : 'Enter the sign-in token sent to your email.';
  $('passkey-login').disabled = !passkeysAvailable || !window.PublicKeyCredential;
  $('passkey-form').hidden = !passkeysAvailable || !window.PublicKeyCredential;
  if (next.stage !== 'authenticated') {
    $('totp-secret').textContent = ''; $('recovery-codes').textContent = '';
    $('totp-enrollment').hidden = true; $('recovery').hidden = true;
    return;
  }
  $('totp-status').textContent = next.factor === 'totp' ? 'Your authenticator is enabled. Keep your recovery codes in a safe place.' : next.factor === 'email' ? 'Email verification is enabled. Turn it off before switching to an authenticator app.' : 'Add an extra code at sign-in using Google Authenticator or another compatible app.';
  $('totp-begin').hidden = !!next.factor;
  $('email-disable').hidden = next.factor !== 'email';
  $('totp-disable-form').hidden = next.factor !== 'totp';
  if (next.factor === 'totp') { $('totp-enrollment').hidden = true; $('totp-secret').textContent = ''; }
  $('passkey-list').replaceChildren();
  if (!next.passkeys?.length) { const item = document.createElement('li'); item.className = 'text-sm text-muted'; item.textContent = 'No passkeys yet.'; $('passkey-list').append(item); }
  for (const key of next.passkeys ?? []) {
    const item = document.createElement('li'); item.className = 'flex items-center justify-between gap-3 rounded-lg border border-line p-3';
    const name = document.createElement('span'); name.className = 'min-w-0 break-words text-sm font-medium'; name.textContent = key.name;
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'shrink-0 rounded px-2 py-2 text-sm font-medium text-danger hover:bg-muted-surface'; remove.textContent = 'Remove'; remove.setAttribute('aria-label', `Remove ${key.name}`);
    remove.addEventListener('click', () => run(async () => { if (confirm(`Remove “${key.name}”?`)) { await action('passkeys/remove', {id: key.id}); notice('Passkey removed.'); } }));
    item.append(name, remove); $('passkey-list').append(item);
  }
}
async function load() { const response = await fetch('/account/session', {credentials: 'same-origin', cache: 'no-store'}); const data = await response.json(); if (!response.ok) throw new Error(data.message || 'Unable to load your account.'); render(data); }
async function action(name, body = {}) {
  const response = await fetch(`/account/action/${name}`, {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': state.csrf}, body: JSON.stringify(body)});
  const data = await response.json();
  if (data.stage) render(data);
  if (!response.ok) {
    const error = data.error || data.result?.error;
    if (error === 'BrowserSessionRequired') await load();
    throw new Error(messages[error] || data.message || 'That request could not be completed. Please try again.');
  }
  return data.result;
}
async function run(task) {
  if (busy) return;
  busy = true; notice(''); document.querySelectorAll('button').forEach(b => b.disabled = true);
  try { await task(); } catch (error) { notice(error.name === 'NotAllowedError' ? 'Passkey request canceled or unavailable. You can try again or use your password.' : error.message); }
  finally { busy = false; document.querySelectorAll('button').forEach(b => b.disabled = false); $('passkey-login').disabled = !passkeysAvailable || !window.PublicKeyCredential; }
}
function form(id, callback) { $(id).addEventListener('submit', event => { event.preventDefault(); const fields = Object.fromEntries(new FormData(event.currentTarget)); event.currentTarget.querySelectorAll('input[name=password], input[name=code]').forEach(input => input.value = ''); run(() => callback(fields)); }); }
const unb64 = value => Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
const b64 = buffer => btoa(String.fromCharCode(...new Uint8Array(buffer))).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
function options(value, create) {
  const result = structuredClone(value.publicKey); result.challenge = unb64(result.challenge);
  if (create) result.user.id = unb64(result.user.id);
  for (const key of create ? result.excludeCredentials ?? [] : result.allowCredentials ?? []) key.id = unb64(key.id);
  return {publicKey: result};
}
function credentialJSON(credential) {
  const response = {clientDataJSON: b64(credential.response.clientDataJSON)};
  for (const key of ['attestationObject', 'authenticatorData', 'signature', 'userHandle']) if (credential.response[key]) response[key] = b64(credential.response[key]);
  if (credential.response.getTransports) response.transports = credential.response.getTransports();
  return JSON.stringify({id: credential.id, rawId: b64(credential.rawId), type: credential.type, clientExtensionResults: credential.getClientExtensionResults(), response});
}
$('password-visibility').addEventListener('click', () => {
  const visible = $('password').type === 'password';
  $('password').type = visible ? 'text' : 'password';
  $('password-visibility').setAttribute('aria-pressed', String(visible));
  $('password-visibility').setAttribute('aria-label', visible ? 'Hide password' : 'Show password');
});
form('password-form', fields => action('login/password', fields));
form('factor-form', fields => action('login/factor', fields));
$('passkey-login').addEventListener('click', () => run(async () => {
  if (!$('identifier').reportValidity()) return;
  const started = await action('login/passkey/begin', {identifier: $('identifier').value});
  const credential = await navigator.credentials.get(options(started.options, false));
  await action('login/passkey/finish', {id: started.id, response: credentialJSON(credential)});
}));
form('passkey-form', async fields => {
  const started = await action('passkeys/begin', fields);
  const credential = await navigator.credentials.create(options(started.options, true));
  await action('passkeys/finish', {id: started.id, response: credentialJSON(credential)}); $('passkey-form').reset(); notice('Passkey added. You can use it the next time you sign in.');
});
$('totp-begin').addEventListener('click', () => run(async () => { const result = await action('totp/begin'); $('totp-secret').textContent = result.secret; $('totp-enrollment').hidden = false; }));
form('totp-confirm-form', async fields => { const result = await action('totp/confirm', fields); $('recovery-codes').textContent = result['recovery-codes'].join('\n'); $('recovery').hidden = false; $('recovery').scrollIntoView({behavior: 'smooth', block: 'nearest'}); notice('Authenticator enabled. Save your recovery codes before leaving.'); });
form('totp-disable-form', async fields => { if (confirm('Remove your authenticator and invalidate its recovery codes?')) { await action('totp/disable', fields); notice('Authenticator removed.'); } });
$('email-disable').addEventListener('click', () => run(async () => { if (confirm('Turn off email verification for sign-in?')) { await action('email/disable'); notice('Email verification turned off. You can now add an authenticator.'); } }));
$('recovery-saved').addEventListener('click', () => { $('recovery-codes').textContent = ''; $('recovery').hidden = true; notice('Recovery codes hidden. Keep your saved copy safe.'); });
$('logout').addEventListener('click', () => run(async () => { await action('logout'); location.replace('/account'); }));
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
await run(load);
