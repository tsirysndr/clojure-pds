import { delay, http, HttpResponse } from "msw";
import type { Flow, Session } from "../api";
import { anonymousFlow, attachedFlow, authenticatedSession, factorSession, loginSession } from "../test/fixtures";

/** Mutable backend double: tests reshape it, handlers serve and evolve it. */
export const scenario: {
  session: Session;
  flow: Flow;
  password: string;
  factorCode: string;
  latency: number;
  requests: Array<{ action: string; body: Record<string, unknown> }>;
} = {
  session: loginSession,
  flow: anonymousFlow,
  password: "correct horse battery",
  factorCode: "123456",
  latency: 0,
  requests: [],
};

export function resetScenario() {
  scenario.session = loginSession;
  scenario.flow = anonymousFlow;
  scenario.password = "correct horse battery";
  scenario.factorCode = "123456";
  scenario.latency = 0;
  scenario.requests = [];
}

const view = () => ({ ...scenario.session });
const failure = (status: number, error: string) => HttpResponse.json({ ...view(), result: { error, status } }, { status });
const success = (result: Record<string, unknown> = {}) => HttpResponse.json({ ...view(), result });

export const handlers = [
  http.get("/account/session", () => HttpResponse.json(view())),

  http.post("/account/action/*", async ({ request }) => {
    const action = new URL(request.url).pathname.slice("/account/action/".length);
    const body = (await request.json()) as Record<string, unknown>;
    scenario.requests.push({ action, body });
    if (scenario.latency > 0) await delay(scenario.latency);
    if (request.headers.get("x-csrf-token") !== scenario.session.csrf)
      return failure(403, "InvalidCsrf");

    switch (action) {
      case "login/password":
        if (body.identifier === "alice.example.com" && body.password === scenario.password) {
          scenario.session = scenario.session.factor ? factorSession : authenticatedSession;
          return success();
        }
        return failure(401, "AuthenticationRequired");
      case "login/factor":
        if (body.code === scenario.factorCode) {
          scenario.session = authenticatedSession;
          return success();
        }
        return failure(401, "InvalidToken");
      case "signup":
        if (body.handle === "taken.example.com") return failure(400, "HandleNotAvailable");
        scenario.session = { ...authenticatedSession, handle: body.handle as string };
        return success();
      case "logout":
        scenario.session = loginSession;
        return HttpResponse.json({ stage: "login" });
      case "totp/begin":
        return success({ secret: "JBSWY3DPEHPK3PXP", uri: "otpauth://totp/pds.example.com:alice.example.com?secret=JBSWY3DPEHPK3PXP&issuer=pds.example.com", "expires-in": 600 });
      case "totp/confirm":
        scenario.session = { ...scenario.session, factor: "totp" };
        return success({ "recovery-codes": ["AAAA-BBBB", "CCCC-DDDD"] });
      case "totp/disable":
        scenario.session = { ...scenario.session, factor: null };
        return success({ disabled: true });
      case "oauth/list":
        return success({ "oauth-sessions": scenario.session["oauth-sessions"] ?? { items: [] } });
      case "oauth/revoke":
        return success({ revoked: true });
      case "passkeys/remove":
        scenario.session = { ...scenario.session, passkeys: [] };
        return success({ removed: true });
      case "identity/recovery/status":
        return success();
      case "identity/recovery/email":
        return success({ sent: true });
      case "identity/recovery/change":
        return success({ state: "completed" });
      default:
        return failure(404, "NotFound");
    }
  }),

  http.get("/oauth/flow/:id/state", () => HttpResponse.json({ ...scenario.flow })),

  http.post("/oauth/flow/:id/:action", async ({ params, request }) => {
    const action = String(params.action);
    const body = (await request.json()) as Record<string, unknown>;
    scenario.requests.push({ action: `flow/${action}`, body });
    if (scenario.latency > 0) await delay(scenario.latency);
    if (request.headers.get("x-csrf-token") !== scenario.flow.csrf)
      return HttpResponse.json({ error: "InvalidCsrf" }, { status: 403 });

    if (action === "attach") {
      scenario.flow = { ...attachedFlow, csrf: scenario.flow.csrf };
      return HttpResponse.json({ ...scenario.flow });
    }
    if (action === "decide")
      return HttpResponse.json({
        location: body.approve
          ? "https://app.example.com/callback?code=granted"
          : "https://app.example.com/callback?error=access_denied",
      });
    return HttpResponse.json({ error: "NotFound" }, { status: 404 });
  }),
];
