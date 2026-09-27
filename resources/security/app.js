const $ = id => document.getElementById(id);
let state = {}, busy = false, passkeysAvailable = false, signupMode = false, signupEnabled = false, userDomain = '';
const flowId = location.pathname.match(/^\/oauth\/flow\/([A-Za-z0-9_-]{43})$/)?.[1];
let flow = null, sessionCursor = null;
let emailEnabled = false, identityEditorVersion = null;
const scopeLabels = {
  atproto: 'Confirm your account identity.',
  'transition:generic': 'Create, change, and delete public records; upload media; access preferences and app services.',
  'transition:email': 'Read your email address.',
  'transition:chat.bsky': 'Read and send your private Bluesky chat messages.',
};
function permissionText(set, field) {
  const translations = Object.entries(set[`${field}:lang`] || {});
  for (const language of navigator.languages || [navigator.language]) {
    let candidate = language.toLowerCase();
    while (candidate) {
      const match = translations.find(([tag]) => tag.toLowerCase() === candidate);
      if (match) return {text: match[1], lang: match[0]};
      const separator = candidate.lastIndexOf('-');
      candidate = separator < 0 ? '' : candidate.slice(0, separator);
    }
  }
  return {text: set[field] || (field === 'title' ? set.nsid : ''), lang: ''};
}
function permissionItems(labels, sets = []) {
  const items = labels.map(label => { const item = document.createElement('li'); item.className = 'wrap-anywhere'; item.textContent = label; return item; });
  for (const set of sets) {
    const item = document.createElement('li'); item.className = 'space-y-2 wrap-anywhere';
    const title = document.createElement('p'); title.className = 'font-medium';
    const titleText = permissionText(set, 'title'); title.textContent = titleText.text; if (titleText.lang) title.lang = titleText.lang;
    const namespace = document.createElement('p'); namespace.className = 'text-xs text-muted'; namespace.textContent = set.nsid;
    const detail = document.createElement('p'); detail.className = 'text-sm text-muted';
    const detailText = permissionText(set, 'detail'); detail.textContent = detailText.text; if (detailText.lang) detail.lang = detailText.lang;
    const expansion = document.createElement('details'); expansion.className = 'space-y-2';
    const summary = document.createElement('summary'); summary.className = 'cursor-pointer text-sm font-medium text-brand'; summary.textContent = 'View permissions';
    const permissions = document.createElement('ul'); permissions.className = 'list-disc space-y-2 pl-5 text-sm';
    permissions.append(...permissionItems(set.permissions.length ? set.permissions : ['No additional permissions in this set.']));
    expansion.append(summary, permissions); item.append(title, namespace, detail, expansion); items.push(item);
  }
  if (sets.length) {
    const note = document.createElement('li'); note.className = 'list-none text-xs text-muted';
    note.textContent = 'Permissions in these sets can change within their namespaces when this app refreshes its session.'; items.push(note);
  }
  return items;
}
const messages = {
  AuthenticationRequired: 'That account or password was not recognized.',
  InvalidPasskey: 'The passkey could not be verified. Please try again.',
  InvalidToken: 'That code is incorrect, expired, or already used. Try a new code.',
  RateLimitExceeded: 'Too many attempts. Wait a few minutes before trying again.',
  BrowserSessionRequired: 'Your session expired. Sign in again to continue.',
  IdentityMismatch: 'Your identity changed since this page was loaded. Refresh its status. If this persists, ask your server operator to reconcile the identity.',
};
function notice(message) { $('notice').textContent = message; $('notice').hidden = !message; }
function render(next) {
  state = next;
  if ('signup-enabled' in next) signupEnabled = next['signup-enabled'];
  if ('email-enabled' in next) emailEnabled = next['email-enabled'];
  if (next['user-domain']) userDomain = next['user-domain'];
  if ('invite-required' in next) $('signup-invite').required = next['invite-required'];
  $('signup-invite-title').textContent = $('signup-invite').required ? 'Invite code' : 'Invite code (optional)';
  $('signup-domain').textContent = userDomain ? `Your handle will be username.${userDomain}` : '';
  $('show-signup').hidden = !signupEnabled;
  $('signup-unavailable').hidden = signupEnabled;
  $('signup-form').hidden = !signupEnabled;
  let screen = next.stage === 'authenticated' ? (flowId ? 'oauth-account' : 'settings') : next.stage;
  if (signupMode) screen = 'signup';
  if (flow?.did) screen = 'consent';
  if ('passkeys-available' in next) passkeysAvailable = next['passkeys-available'];
  if (next.origin) { $('server-origin').textContent = next.origin; $('server-name').textContent = `${new URL(next.origin).hostname} PDS`; }
  document.body.dataset.stage = screen === 'settings' ? 'authenticated' : screen;
  for (const stage of ['login', 'factor', 'settings', 'signup', 'oauth-account', 'consent']) $(stage).hidden = stage !== screen;
  $('logout').hidden = next.stage === 'login';
  $('oauth-cancel').hidden = !flowId || !!flow?.did;
  $('heading').textContent = next.stage === 'authenticated' ? 'Account security' : next.stage === 'factor' ? 'Verify it’s you' : 'Sign in';
  $('subtitle').textContent = next.handle ? next.handle : 'Enter your username and password';
  if (screen === 'signup') { $('heading').textContent = 'Create your account'; $('subtitle').textContent = 'Choose your username and password'; }
  if (screen === 'oauth-account') { $('heading').textContent = 'Continue with your account'; $('oauth-continue').textContent = flow.parameters.prompt === 'login' ? 'Sign in again' : `Continue as ${next.handle}`; }
  if (screen === 'consent') {
    $('heading').textContent = 'Authorize application'; $('subtitle').textContent = 'Review the access you are granting';
    $('oauth-client').textContent = flow['client-id']; $('oauth-did').textContent = flow.did;
    $('oauth-scopes').replaceChildren(...permissionItems(flow.permissions || flow.parameters.scope.split(' ').map(scope => scopeLabels[scope] || scope), flow['permission-sets']));
  }
  document.title = `${$('heading').textContent} · ${$('server-name').textContent}`;
  if (flowId) $('session-note').textContent = 'Authorization requests expire after ten minutes';
  $('factor-help').textContent = next.factor === 'totp' ? 'Enter a code from your authenticator app, or one of your saved recovery codes.' : 'Enter the sign-in token sent to your email.';
  $('passkey-login').disabled = !passkeysAvailable || !window.PublicKeyCredential;
  $('passkey-form').hidden = !passkeysAvailable || !window.PublicKeyCredential;
  renderIdentity(next.stage === 'authenticated' ? next['recovery-keys'] : null);
  if (next.stage !== 'authenticated') {
    $('oauth-session-list').replaceChildren(); sessionCursor = null; $('oauth-session-next').hidden = true;
    $('totp-secret').textContent = ''; $('recovery-codes').textContent = '';
    $('totp-enrollment').hidden = true; $('recovery').hidden = true;
    return;
  }
  renderSessions(next.result?.['oauth-sessions'] || next['oauth-sessions']);
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
function keyItems(keys) {
  return (keys.length ? keys : ['No independent recovery keys.']).map(key => {
    const item = document.createElement('li'); item.className = 'break-all font-mono text-xs leading-5'; item.textContent = key; return item;
  });
}
function renderIdentity(identity) {
  $('identity-recovery').hidden = !identity;
  if (!identity) {
    identityEditorVersion = null; $('identity-key-form').reset();
    $('identity-key-list').replaceChildren(); $('identity-key-pending').replaceChildren(); return;
  }
  const pending = identity.pending;
  $('identity-key-list').replaceChildren(...keyItems(identity.recoveryKeys));
  $('identity-key-form').hidden = !!pending || !emailEnabled;
  $('identity-key-retry').hidden = pending?.kind !== 'recovery';
  $('identity-key-pending').hidden = pending?.kind !== 'recovery';
  $('identity-key-pending').replaceChildren(...(pending?.kind === 'recovery' ? keyItems(pending.recoveryKeys) : []));
  $('identity-key-status').textContent = pending?.kind === 'recovery'
    ? `Saved change: ${pending.state}. The requested keys below will replace your recovery keys once confirmed. You can refresh or retry this same change without another email code.`
    : pending ? 'Another identity change is in progress. Finish it before editing recovery keys.'
    : !emailEnabled ? 'Email delivery must be enabled by your server operator before you can change these keys.'
    : 'Verify your email to replace this list. Changes are confirmed with the PLC directory.';
  if (identityEditorVersion !== identity.operationCid) {
    identityEditorVersion = identity.operationCid;
    $('identity-key-form').reset(); $('identity-key-input').value = identity.recoveryKeys.join('\n');
  }
}
function renderSessions(page) {
  const list = $('oauth-session-list'); list.replaceChildren();
  sessionCursor = page?.cursor || null; $('oauth-session-next').hidden = !sessionCursor;
  $('oauth-session-first').textContent = page?.['first-page'] === false ? 'Back to first page' : 'Refresh list';
  if (!page?.items?.length) { const item = document.createElement('li'); item.className = 'text-sm text-muted'; item.textContent = 'No active sessions on this page.'; list.append(item); }
  for (const session of page?.items || []) {
    const item = document.createElement('li'); item.className = 'space-y-3 rounded-lg border border-line p-3';
    const client = document.createElement('p'); client.className = 'break-all text-sm font-medium'; client.textContent = session['client-id'];
    const dates = document.createElement('p'); dates.className = 'text-xs text-muted'; dates.textContent = `Connected ${new Date(session['created-at']).toLocaleString()} · Expires ${new Date(session['expires-at']).toLocaleString()}`;
    const scopes = document.createElement('ul'); scopes.className = 'list-disc space-y-2 pl-5 text-sm';
    scopes.append(...permissionItems(session.permissions || session.scope.split(' ').map(scope => scopeLabels[scope] || scope), session['permission-sets']));
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'secondary text-danger'; remove.textContent = 'Disconnect'; remove.setAttribute('aria-label', `Disconnect session for ${session['client-id']}`);
    remove.addEventListener('click', () => run(async () => { if (confirm(`Disconnect this session for ${session['client-id']}?`)) { await action('oauth/revoke', {id: session.id}); notice('App session disconnected.'); } }));
    item.append(client, dates, scopes, remove); list.append(item);
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
async function flowAction(action, body) {
  const response = await fetch(`/oauth/flow/${flowId}/${action}`, {method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json', 'X-CSRF-Token': flow.csrf}, body: JSON.stringify(body)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || 'Authorization could not be completed. Restart from your application.');
  return data;
}
async function finishLogin() {
  if (flowId && state.stage === 'authenticated' && !flow.did) { flow = await flowAction('attach', {accountCsrf: state.csrf}); render(state); }
}
async function chooseOther() { await action('logout'); signupMode = false; await load(); }
$('show-signup').addEventListener('click', () => { signupMode = true; render(state); notice(''); });
$('show-login').addEventListener('click', () => { signupMode = false; render(state); notice(''); });
$('oauth-other').addEventListener('click', () => run(chooseOther));
$('oauth-continue').addEventListener('click', () => run(flow.parameters.prompt === 'login' ? chooseOther : finishLogin));
for (const [id, approve] of [['oauth-approve', true], ['oauth-deny', false], ['oauth-cancel', false]]) $(id).addEventListener('click', () => run(async () => { const result = await flowAction('decide', {approve}); location.assign(result.location); }));
form('signup-form', async fields => {
  if (state.stage !== 'login') { await action('logout'); await load(); }
  const body = {handle: `${fields.username.toLowerCase()}.${userDomain}`, email: fields.email, password: fields.password};
  if (fields.inviteCode) body.inviteCode = fields.inviteCode;
  await action('signup', body); signupMode = false; $('signup-form').reset(); render(state); await finishLogin();
});
form('password-form', async fields => { await action('login/password', fields); await finishLogin(); });
form('factor-form', async fields => { await action('login/factor', fields); await finishLogin(); });
$('passkey-login').addEventListener('click', () => run(async () => {
  if (!$('identifier').reportValidity()) return;
  const started = await action('login/passkey/begin', {identifier: $('identifier').value});
  const credential = await navigator.credentials.get(options(started.options, false));
  await action('login/passkey/finish', {id: started.id, response: credentialJSON(credential)}); await finishLogin();
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
$('oauth-session-first').addEventListener('click', () => run(() => action('oauth/list')));
$('oauth-session-next').addEventListener('click', () => run(() => action('oauth/list', {cursor: sessionCursor})));
$('identity-key-refresh').addEventListener('click', () => run(() => action('identity/recovery/status')));
$('identity-key-email').addEventListener('click', () => run(async () => {
  await action('identity/recovery/email'); notice('Verification email requested. Use the latest identity-operation code sent to your email.');
}));
function identityNotice(result) {
  notice(result.state === 'completed' ? 'Recovery keys updated.' : result.state === 'unchanged' ? 'These recovery keys are already configured.' : 'Your change is saved. Refresh its status or retry the saved change.');
}
form('identity-key-form', async fields => {
  const keys = fields.keys.split(/\r?\n/).map(key => key.trim()).filter(Boolean);
  if (keys.length > 4 || new Set(keys).size !== keys.length || keys.some(key => !key.startsWith('did:key:'))) throw new Error('Enter up to four distinct public did:key values, one per line.');
  const prompt = keys.length ? 'Replace your identity recovery keys with this ordered list?' : 'Remove all independent identity recovery keys? The server will retain its control key.';
  if (confirm(prompt)) identityNotice(await action('identity/recovery/change', {previousCid: identityEditorVersion, recoveryKeys: keys, code: fields.code}));
});
$('identity-key-retry').addEventListener('click', () => run(async () => {
  const pending = state['recovery-keys']?.pending;
  if (pending?.kind === 'recovery') identityNotice(await action('identity/recovery/change', {previousCid: pending.previousCid, recoveryKeys: pending.recoveryKeys}));
}));
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
await run(async () => {
  if (flowId) {
    const response = await fetch(`/oauth/flow/${flowId}/state`, {credentials: 'same-origin', cache: 'no-store'});
    flow = await response.json();
    if (!response.ok) throw new Error(flow.message || 'Restart authorization from your application.');
    signupMode = !flow.did && flow.parameters.prompt === 'create';
  }
  await load();
  if (flow?.parameters.login_hint && state.stage === 'login') $('identifier').value = flow.parameters.login_hint;
});
