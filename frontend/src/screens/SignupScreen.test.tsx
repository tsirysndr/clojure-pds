import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { App } from "../App";
import { scenario } from "../mocks/handlers";
import { loginSession } from "../test/fixtures";

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

type User = ReturnType<typeof userEvent.setup>;

async function openSignup(user: User) {
  render(<App client={client()} />);
  await user.click(await screen.findByRole("button", { name: /create one/i }));
  await screen.findByRole("heading", { name: /create your account/i });
}

async function fill(
  user: User,
  {
    username,
    email,
    password = "a strong password",
    confirm = password,
  }: { username: string; email: string; password?: string; confirm?: string },
) {
  await user.type(screen.getByLabelText(/username/i), username);
  await user.type(screen.getByLabelText(/email address/i), email);
  await user.type(screen.getByLabelText(/^password/i), password);
  await user.type(screen.getByLabelText(/confirm password/i), confirm);
}

describe("SignupScreen", () => {
  it("derives the full handle from the hosted domain", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    expect(screen.getByText(/username\.example\.com/i)).toBeInTheDocument();

    await fill(user, { username: "Carol", email: "carol@example.com" });
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("heading", { name: "carol.example.com" })).toBeInTheDocument();
    const signup = scenario.requests.find((entry) => entry.action === "signup");
    expect(signup?.body).toMatchObject({ handle: "carol.example.com", email: "carol@example.com" });
    expect(signup?.body).not.toHaveProperty("inviteCode");
    expect(signup?.body).not.toHaveProperty("confirmPassword");
  });

  it("shows the server's domain beside the field and previews the handle as you type", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    expect(screen.getByText(".example.com")).toBeInTheDocument();
    expect(screen.getByText(/your handle will be username\.example\.com/i)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/username/i), "Carol");

    expect(screen.getByText(/your handle will be carol\.example\.com/i)).toBeInTheDocument();
  });

  it("will not submit until the confirmation matches", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    await fill(user, {
      username: "erin",
      email: "erin@example.com",
      password: "a strong password",
      confirm: "a strong passward",
    });
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/do not match/i)).toBeInTheDocument();
    expect(scenario.requests).toHaveLength(0);

    await user.clear(screen.getByLabelText(/confirm password/i));
    await user.type(screen.getByLabelText(/confirm password/i), "a strong password");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("heading", { name: "erin.example.com" })).toBeInTheDocument();
  });

  it("spins the create button while the request is in flight", async () => {
    scenario.latency = 200;
    const user = userEvent.setup();
    await openSignup(user);

    await fill(user, { username: "Frank", email: "frank@example.com" });
    const button = screen.getByRole("button", { name: /create account/i });
    await user.click(button);

    expect(button).toHaveAttribute("data-loading", "true");
    expect(await screen.findByRole("heading", { name: "frank.example.com" })).toBeInTheDocument();
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

    await fill(user, { username: "dave", email: "dave@example.com" });
    await user.type(screen.getByLabelText(/invite code/i), "invite-123");
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("heading", { name: "dave.example.com" })).toBeInTheDocument();
    const signup = scenario.requests.find((entry) => entry.action === "signup");
    expect(signup?.body).toMatchObject({ inviteCode: "invite-123" });
  });

  it("surfaces server-side registration conflicts", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    await fill(user, { username: "taken", email: "taken@example.com" });
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/already exists/i);
  });

  it("keeps invalid usernames local", async () => {
    const user = userEvent.setup();
    await openSignup(user);

    await fill(user, { username: "no dots", email: "carol@example.com" });
    await user.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/letters, numbers/i)).toBeInTheDocument();
    expect(scenario.requests).toHaveLength(0);
  });
});
