import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button, Divider } from "@heroui/react";
import { useSetAtom } from "jotai";
import { IconAt, IconFingerprint, IconLock } from "@tabler/icons-react";
import type { Session } from "../api";
import { useAction } from "../api";
import { loginSchema, type LoginValues } from "../schemas";
import { signupModeAtom } from "../atoms";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { ClientPanel } from "../components/ClientPanel";
import { TextField, PasswordField } from "../components/Field";
import { ceremonyOptions, credentialJSON, type ServerOptions } from "../webauthn";
import { usePending } from "../pending";

export function LoginScreen({
  session,
  clientId,
  loginHint,
  onAuthenticated,
}: {
  session: Session;
  clientId?: string;
  loginHint?: string;
  onAuthenticated: () => Promise<void>;
}) {
  const action = useAction();
  const setSignup = useSetAtom(signupModeAtom);
  const { run, buttonProps, notice } = usePending();

  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    mode: "onSubmit",
    defaultValues: { identifier: loginHint ?? "", password: "" },
  });

  const submit = form.handleSubmit((values) =>
    run("password", async () => {
      await action.mutateAsync({ action: "login/password", body: values });
      await onAuthenticated();
    }),
  );

  const passkeyLogin = () =>
    run("passkey", async () => {
      const identifier = form.getValues("identifier").trim();
      if (!identifier) {
        form.setError("identifier", { message: "Enter your username or email address first" });
        return;
      }
      const started = await action.mutateAsync({ action: "login/passkey/begin", body: { identifier } });
      const credential = (await navigator.credentials.get(
        ceremonyOptions(started.options as ServerOptions, false),
      )) as PublicKeyCredential | null;
      if (!credential) return;
      await action.mutateAsync({
        action: "login/passkey/finish",
        body: { id: started.id, response: credentialJSON(credential) },
      });
      await onAuthenticated();
    });

  return (
    <AuthCard title="Sign in" service={session.origin} subtitle={clientId ? undefined : "Use your PDS account"}>
      {clientId ? <ClientPanel clientId={clientId} /> : null}

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4" noValidate>
        <TextField
          label="Username or email address"
          placeholder={`alice.${session["user-domain"]}`}
          registration={form.register("identifier")}
          error={form.formState.errors.identifier}
          startContent={<IconAt size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          autoComplete="username webauthn"
          autoFocus={!loginHint}
          maxLength={2048}
        />

        <PasswordField
          label="Password"
          placeholder="Your password"
          registration={form.register("password")}
          error={form.formState.errors.password}
          startContent={<IconLock size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          autoComplete="current-password"
          autoFocus={Boolean(loginHint)}
          maxLength={1024}
        />

        <p className="flex items-center gap-1.5 text-xs text-default-500">
          <IconLock size={14} stroke={1.75} aria-hidden />
          Verify the address bar before entering your password.
        </p>

        <Button type="submit" color="primary" size="lg" radius="sm" {...buttonProps("password")} className="mt-1 font-medium">
          Sign in
        </Button>
      </form>

      {session["passkeys-available"] ? (
        <>
          <div className="flex items-center gap-3">
            <Divider className="flex-1" />
            <span className="text-xs text-default-400">or</span>
            <Divider className="flex-1" />
          </div>

          <Button
            variant="bordered"
            size="lg"
            radius="sm"
            fullWidth
            {...buttonProps("passkey")}
            startContent={<IconFingerprint size={18} stroke={1.75} />}
            onPress={() => void passkeyLogin()}
          >
            Sign in with a passkey
          </Button>
        </>
      ) : null}

      {session["signup-enabled"] ? (
        <p className="text-center text-sm text-default-500">
          No account yet?{" "}
          <button type="button" onClick={() => setSignup(true)} className="font-medium text-primary hover:underline">
            Create one
          </button>
        </p>
      ) : null}
    </AuthCard>
  );
}
