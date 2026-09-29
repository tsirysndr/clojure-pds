import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { scenario } from "../mocks/handlers";
import { authenticatedSession } from "../test/fixtures";

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe("SettingsScreen", () => {
  it("lists passkeys, connected applications, and recovery keys", async () => {
    scenario.session = authenticatedSession;
    render(<App client={client()} />);

    expect(await screen.findByRole("heading", { name: "alice.example.com" })).toBeInTheDocument();
    expect(screen.getByText("Laptop")).toBeInTheDocument();
    expect(screen.getByText("https://app.example.com/client-metadata.json")).toBeInTheDocument();
    expect(screen.getByText("did:key:zExistingRecovery")).toBeInTheDocument();
  });

  it("walks through authenticator enrollment and shows recovery codes", async () => {
    scenario.session = { ...authenticatedSession, factor: null };
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.click(await screen.findByRole("button", { name: /add an authenticator/i }));
    expect(await screen.findByText("JBSWY3DPEHPK3PXP")).toBeInTheDocument();

    // The scannable code and the typed secret are both offered: a camera is not
    // always available, and the QR carries the same enrollment either way.
    const qr = await screen.findByTitle(/authenticator setup code/i);
    expect(qr).toBeInTheDocument();
    expect(qr.closest("svg")).toBeInTheDocument();

    await user.type(screen.getByLabelText(/code from your app/i), "123456");
    await user.click(screen.getByRole("button", { name: /confirm/i }));

    expect(await screen.findByText("AAAA-BBBB")).toBeInTheDocument();
    expect(screen.getByText(/save your recovery codes/i)).toBeInTheDocument();
  });

  it("revokes a connected application", async () => {
    scenario.session = authenticatedSession;
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.click(await screen.findByRole("button", { name: /revoke/i }));

    await waitFor(() =>
      expect(scenario.requests.find((entry) => entry.action === "oauth/revoke")?.body).toMatchObject({
        id: "session-one",
      }),
    );
    expect(await screen.findByText(/application access revoked/i)).toBeInTheDocument();
  });

  it("requires the CSRF token the server issued", async () => {
    scenario.session = authenticatedSession;
    const user = userEvent.setup();
    render(<App client={client()} />);
    await screen.findByRole("heading", { name: "alice.example.com" });

    // The server rotates the token after the page loaded (another tab acted).
    scenario.session = { ...authenticatedSession, csrf: "rotated-by-another-tab-rotated-by-another-t" };
    await user.click(screen.getByRole("button", { name: /revoke/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/reload/i);
  });
});
