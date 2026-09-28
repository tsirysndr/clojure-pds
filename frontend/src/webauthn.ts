// Base64url encoding for WebAuthn ceremonies, matching the server contract.

export const unb64 = (value: string): Uint8Array =>
  Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

export const b64 = (buffer: ArrayBuffer | Uint8Array): string =>
  btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");

export type ServerOptions = { publicKey: Record<string, unknown> };

export function ceremonyOptions(value: ServerOptions, create: boolean): CredentialCreationOptions & CredentialRequestOptions {
  const result = structuredClone(value.publicKey) as Record<string, unknown>;
  result.challenge = unb64(String(result.challenge));
  if (create) {
    const user = result.user as Record<string, unknown>;
    user.id = unb64(String(user.id));
  }
  const keys = (create ? result.excludeCredentials : result.allowCredentials) as
    | Array<Record<string, unknown>>
    | undefined;
  for (const key of keys ?? []) key.id = unb64(String(key.id));
  return { publicKey: result } as unknown as CredentialCreationOptions & CredentialRequestOptions;
}

export function credentialJSON(credential: PublicKeyCredential): string {
  const assertion = credential.response as AuthenticatorAssertionResponse & AuthenticatorAttestationResponse;
  const response: Record<string, string> = { clientDataJSON: b64(assertion.clientDataJSON) };
  for (const key of ["attestationObject", "authenticatorData", "signature", "userHandle"] as const) {
    const value = assertion[key];
    if (value) response[key] = b64(value);
  }
  return JSON.stringify({
    id: credential.id,
    rawId: b64(credential.rawId),
    type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults(),
    response,
  });
}
