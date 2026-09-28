import type { Flow, Session } from "../api";

export const csrf = "test-csrf-token-test-csrf-token-test-csrf-t";

export const loginSession: Session = {
  stage: "login",
  csrf,
  "passkeys-available": true,
  origin: "https://pds.example.com",
  "signup-enabled": true,
  "email-enabled": true,
  "invite-required": false,
  "user-domain": "example.com",
};

export const factorSession: Session = {
  ...loginSession,
  stage: "factor",
  handle: "alice.example.com",
  factor: "totp",
};

export const authenticatedSession: Session = {
  ...loginSession,
  stage: "authenticated",
  handle: "alice.example.com",
  factor: null,
  passkeys: [
    {
      id: "cGFzc2tleS1vbmU",
      name: "Laptop",
      "created-at": "2026-09-01T10:00:00Z",
      "last-used-at": null,
    },
  ],
  "recovery-keys": {
    operationCid: "bafyoperation",
    rotationKey: "did:key:zServerRotation",
    recoveryKeys: ["did:key:zExistingRecovery"],
    pending: null,
  },
  "oauth-sessions": {
    items: [
      {
        id: "session-one",
        "client-id": "https://app.example.com/client-metadata.json",
        scope: "atproto transition:generic",
        "created-at": "2026-09-20T10:00:00Z",
        "expires-at": "2026-10-20T10:00:00Z",
        permissions: [],
        "permission-sets": [],
      },
    ],
    "first-page": true,
  },
};

export const flowPath = "/oauth/flow/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

export const anonymousFlow: Flow = {
  "client-id": "https://app.example.com/client-metadata.json",
  parameters: { scope: "atproto transition:generic" },
  did: null,
  csrf: "flow-csrf-token-flow-csrf-token-flow-csrf-t",
  permissions: [],
  "permission-sets": [],
};

export const attachedFlow: Flow = {
  ...anonymousFlow,
  did: "did:web:alice.example.com",
  permissions: [
    "Confirm your account identity.",
    "Create, change, and delete public records; upload media; access preferences and app services.",
  ],
  "permission-sets": [
    { nsid: "app.bsky.authFull", title: "Full Bluesky access", permissions: ["Read and send your private Bluesky chat messages."] },
  ],
};
