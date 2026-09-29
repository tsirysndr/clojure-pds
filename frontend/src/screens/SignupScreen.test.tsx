import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { scenario } from "../mocks/handlers";
import { loginSession } from "../test/fixtures";

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

async function openSignup(user: ReturnType<typeof userEvent.setup>) {
  render(<App client={client()} />);
  await user.click(await screen.findByRole("button", { name: /create one/i }));
  await screen.findByRole("heading", { name: /create your account/i });
}

describe("SignupScreen", () => {
  it("derives the full handle from the hosted domain", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    expect(screen.getByText(/username\.example\.com/i)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/username/i), "Carol");
    await user.type(screen.getByLabelText(/email address/i), "carol@example.com");
    await user.type(screen.getByLabelText(/^password/i), "a strong password");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("heading", { name: "carol.example.com" })).toBeInTheDocument();
    const signup = scenario.requests.find((entry) => entry.action === "signup");
    expect(signup?.body).toMatchObject({ handle: "carol.example.com", email: "carol@example.com" });
    expect(signup?.body).not.toHaveProperty("inviteCode");
  });

  it("hides the invite code field when the server does not require one", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    expect(screen.queryByLabelText(/invite code/i)).not.toBeInTheDocument();
  });

  it("collects an invite code when the server requires one", async () => {
    scenario.session = { ...loginSession, "invite-required": true };
    const user = userEvent.setup();
    await openSignup(user);

    await user.type(screen.getByLabelText(/username/i), "dave");
    await user.type(screen.getByLabelText(/email address/i), "dave@example.com");
    await user.type(screen.getByLabelText(/^password/i), "a strong password");
    await user.type(screen.getByLabelText(/invite code/i), "invite-123");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("heading", { name: "dave.example.com" })).toBeInTheDocument();
    const signup = scenario.requests.find((entry) => entry.action === "signup");
    expect(signup?.body).toMatchObject({ inviteCode: "invite-123" });
  });

  it("surfaces server-side registration conflicts", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    await user.type(screen.getByLabelText(/username/i), "taken");
    await user.type(screen.getByLabelText(/email address/i), "taken@example.com");
    await user.type(screen.getByLabelText(/^password/i), "a strong password");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/already exists/i);
  });

  it("keeps invalid usernames local", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    await user.type(screen.getByLabelText(/username/i), "no dots");
    await user.type(screen.getByLabelText(/email address/i), "carol@example.com");
    await user.type(screen.getByLabelText(/^password/i), "a strong password");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/letters, numbers/i)).toBeInTheDocument();
    expect(scenario.requests).toHaveLength(0);
  });
});
