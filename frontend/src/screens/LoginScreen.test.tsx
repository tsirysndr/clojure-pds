import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { scenario } from "../mocks/handlers";

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe("LoginScreen", () => {
  it("asks for a username or email address and a password", async () => {
    render(<App client={client()} />);

    expect(await screen.findByLabelText(/username or email address/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^password/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /sign in$/i })).toBeInTheDocument();
  });

  it("signs in through the JSON action API and lands on settings", async () => {
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.type(await screen.findByLabelText(/username or email address/i), "alice.example.com");
    await user.type(screen.getByLabelText(/^password/i), "correct horse battery");
    await user.click(screen.getByRole("button", { name: /sign in$/i }));

    expect(await screen.findByRole("heading", { name: "alice.example.com" })).toBeInTheDocument();
    const login = scenario.requests.find((entry) => entry.action === "login/password");
    expect(login?.body).toMatchObject({ identifier: "alice.example.com" });
  });

  it("shows a friendly error for rejected credentials without leaving the form", async () => {
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.type(await screen.findByLabelText(/username or email address/i), "alice.example.com");
    await user.type(screen.getByLabelText(/^password/i), "wrong password");
    await user.click(screen.getByRole("button", { name: /sign in$/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/sign-in failed/i);
    expect(screen.getByLabelText(/username or email address/i)).toBeInTheDocument();
  });

  it("validates locally before any network request", async () => {
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.type(await screen.findByLabelText(/username or email address/i), "alice.example.com");
    await user.click(screen.getByRole("button", { name: /sign in$/i }));

    await waitFor(() => expect(screen.getByText(/enter your password/i)).toBeInTheDocument());
    expect(scenario.requests).toHaveLength(0);
  });

  it("routes two-factor accounts through the factor stage", async () => {
    scenario.session = { ...scenario.session, factor: "totp" };
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.type(await screen.findByLabelText(/username or email address/i), "alice.example.com");
    await user.type(screen.getByLabelText(/^password/i), "correct horse battery");
    await user.click(screen.getByRole("button", { name: /sign in$/i }));

    await user.type(await screen.findByLabelText(/authenticator code/i), "123456");
    await user.click(screen.getByRole("button", { name: /continue/i }));

    expect(await screen.findByRole("heading", { name: "alice.example.com" })).toBeInTheDocument();
  });

  it("offers signup only when the server enables it", async () => {
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.click(await screen.findByRole("button", { name: /create one/i }));
    expect(await screen.findByRole("heading", { name: /create your account/i })).toBeInTheDocument();
  });
});

describe("request feedback", () => {
  it("spins the sign-in button immediately and settles after the response", async () => {
    scenario.latency = 200;
    const user = userEvent.setup();
    render(<App client={client()} />);

    await user.type(await screen.findByLabelText(/username or email address/i), "alice.example.com");
    await user.type(screen.getByLabelText(/^password/i), "correct horse battery");
    const button = screen.getByRole("button", { name: /sign in$/i });
    await user.click(button);

    expect(button).toHaveAttribute("data-loading", "true");
    expect(screen.getByRole("button", { name: /passkey/i })).toBeDisabled();
    expect(await screen.findByRole("heading", { name: "alice.example.com" })).toBeInTheDocument();
  });

  it("spins only the clicked consent button", async () => {
    const { attachedFlow, authenticatedSession, flowPath } = await import("../test/fixtures");
    window.history.replaceState({}, "", flowPath);
    scenario.session = authenticatedSession;
    scenario.flow = attachedFlow;
    scenario.latency = 200;
    const user = userEvent.setup();
    render(<App client={client()} />);

    const allow = await screen.findByRole("button", { name: /allow access/i });
    await user.click(allow);

    expect(allow).toHaveAttribute("data-loading", "true");
    expect(screen.getByRole("button", { name: /deny/i })).toBeDisabled();
  });
});
