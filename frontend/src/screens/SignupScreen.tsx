import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@heroui/react";
import { useAtom, useSetAtom } from "jotai";
import { IconAt, IconMail, IconTicket } from "@tabler/icons-react";
import type { Session } from "../api";
import { useAction } from "../api";
import { signupSchema, type SignupValues } from "../schemas";
import { busyAtom, signupModeAtom } from "../atoms";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { ClientPanel } from "../components/ClientPanel";
import { TextField, PasswordField } from "../components/Field";
import { useNotice } from "../notice";

export function SignupScreen({
  session,
  clientId,
  onAuthenticated,
}: {
  session: Session;
  clientId?: string;
  onAuthenticated: () => Promise<void>;
}) {
  const action = useAction();
  const setSignup = useSetAtom(signupModeAtom);
  const [busy, setBusy] = useAtom(busyAtom);
  const { notice, showError, clear } = useNotice();
  const domain = session["user-domain"];

  const form = useForm<SignupValues>({
    resolver: zodResolver(signupSchema),
    defaultValues: { username: "", email: "", password: "", inviteCode: "" },
  });

  const submit = form.handleSubmit(async (values) => {
    if (busy) return;
    setBusy(true);
    clear();
    try {
      const body: Record<string, unknown> = {
        handle: `${values.username.toLowerCase()}.${domain}`,
        email: values.email,
        password: values.password,
      };
      if (values.inviteCode) body.inviteCode = values.inviteCode;
      await action.mutateAsync({ action: "signup", body });
      setSignup(false);
      await onAuthenticated();
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  });

  return (
    <AuthCard title="Create your account" service={session.origin}>
      {clientId ? <ClientPanel clientId={clientId} /> : null}

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4" noValidate>
        <TextField
          label="Username"
          placeholder="alice"
          description={domain ? `Your handle will be username.${domain}` : undefined}
          registration={form.register("username")}
          error={form.formState.errors.username}
          startContent={<IconAt size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          autoComplete="username"
          autoFocus
          maxLength={63}
        />

        <TextField
          label="Email address"
          placeholder="you@example.com"
          registration={form.register("email")}
          error={form.formState.errors.email}
          startContent={<IconMail size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          type="email"
          inputMode="email"
          autoComplete="email"
          maxLength={320}
        />

        <PasswordField
          label="Password"
          placeholder="At least 8 characters"
          registration={form.register("password")}
          error={form.formState.errors.password}
          autoComplete="new-password"
          maxLength={1024}
        />

        <TextField
          label={session["invite-required"] ? "Invite code" : "Invite code (optional)"}
          placeholder="Invite code"
          registration={form.register("inviteCode")}
          error={form.formState.errors.inviteCode}
          startContent={<IconTicket size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          isRequired={session["invite-required"]}
          maxLength={256}
        />

        <Button type="submit" color="primary" size="lg" radius="sm" isLoading={busy} className="mt-1 font-medium">
          Create account
        </Button>
      </form>

      <p className="text-center text-sm text-default-500">
        Already have an account?{" "}
        <button type="button" onClick={() => setSignup(false)} className="font-medium text-primary hover:underline">
          Sign in
        </button>
      </p>
    </AuthCard>
  );
}
