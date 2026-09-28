import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { navigate } from "../navigation";
import { scenario } from "../mocks/handlers";
import { attachedFlow, authenticatedSession, flowPath } from "../test/fixtures";

vi.mock("../navigation", () => ({ navigate: vi.fn() }));

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe("AuthorizeScreen", () => {
  it("lists the requested permissions and permission sets", async () => {
    window.history.replaceState({}, "", flowPath);
    scenario.session = authenticatedSession;
    scenario.flow = attachedFlow;
    render(<App client={client()} />);

    expect(await screen.findByRole("heading", { name: /authorize access/i })).toBeInTheDocument();
    expect(screen.getByText(/confirm your account identity/i)).toBeInTheDocument();
    expect(screen.getByText(/full bluesky access/i)).toBeInTheDocument();
  });

  it("approves through the flow API and follows the redirect", async () => {
    window.history.replaceState({}, "", flowPath);
    scenario.session = authenticatedSession;
    scenario.flow = attachedFlow;
    render(<App client={client()} />);

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /allow access/i }));

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith("https://app.example.com/callback?code=granted"),
    );
    const decision = scenario.requests.find((entry) => entry.action === "flow/decide");
    expect(decision?.body).toMatchObject({ approve: true });
  });

  it("confirms an already signed-in account before consent", async () => {
    window.history.replaceState({}, "", flowPath);
    scenario.session = authenticatedSession;
    render(<App client={client()} />);

    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /continue as alice\.example\.com/i }));

    expect(await screen.findByRole("heading", { name: /authorize access/i })).toBeInTheDocument();
    const attach = scenario.requests.find((entry) => entry.action === "flow/attach");
    expect(attach?.body).toMatchObject({ accountCsrf: authenticatedSession.csrf });
  });

  it("sends new visitors through the login screen with the requested hint", async () => {
    window.history.replaceState({}, "", flowPath);
    scenario.flow = { ...scenario.flow, parameters: { login_hint: "alice.example.com" } };
    render(<App client={client()} />);

    expect(await screen.findByLabelText(/username or email address/i)).toHaveValue("alice.example.com");
    expect(screen.getByText(/wants to access your account/i)).toBeInTheDocument();
  });
});
