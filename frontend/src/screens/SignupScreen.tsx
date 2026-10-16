import { useTranslation } from "react-i18next";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@heroui/react";
import { useSetAtom } from "jotai";
import { IconAt, IconMail, IconTicket } from "@tabler/icons-react";
import type { Session } from "../api";
import { useAction } from "../api";
import { signupSchema, type SignupValues } from "../schemas";
import { signupModeAtom } from "../atoms";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { ClientPanel } from "../components/ClientPanel";
import { TextField, PasswordField } from "../components/Field";
import { usePending } from "../pending";

export function SignupScreen({
  session,
  clientId,
  onAuthenticated,
}: {
  session: Session;
  clientId?: string;
  onAuthenticated: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const action = useAction();
  const setSignup = useSetAtom(signupModeAtom);
  const { run, buttonProps, notice, pending } = usePending();
  const domain = session["user-domain"];

  const form = useForm<SignupValues>({
    resolver: zodResolver(signupSchema(t)),
    defaultValues: { username: "", email: "", password: "", confirmPassword: "", inviteCode: "" },
  });

  const username = form.watch("username");
  const suffix = domain ? `.${domain.replace(/^\./, "")}` : "";
  const expandHandle = (value: string) => {
    const handle = value.trim().toLowerCase();
    return handle.includes(".") ? handle : `${handle}${suffix}`;
  };
  const showSuffix = Boolean(suffix) && !username.trim().includes(".");

  const submit = form.handleSubmit((values) =>
    run("signup", async () => {
      const body: Record<string, unknown> = {
        handle: expandHandle(values.username),
        email: values.email,
        password: values.password,
      };
      if (values.inviteCode) body.inviteCode = values.inviteCode;
      await action.mutateAsync({ action: "signup", body });
      setSignup(false);
      await onAuthenticated();
    }),
  );

  return (
    <AuthCard title={t("signup.title")} service={session.origin}>
      {clientId ? <ClientPanel clientId={clientId} /> : null}

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4" noValidate>
        <TextField
          label={t("signup.username")}
          placeholder="alice"
          description={t("signup.usernameDescription", { handle: expandHandle(username || t("signup.usernameFallback")) })}
          registration={form.register("username")}
          error={form.formState.errors.username}
          startContent={<IconAt size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          endContent={
            showSuffix ? (
              <span className="shrink-0 text-sm text-default-400" aria-hidden>
                {suffix}
              </span>
            ) : undefined
          }
          autoComplete="username"
          autoFocus
          maxLength={253}
        />

        <TextField
          label={t("signup.email")}
          placeholder={t("signup.emailPlaceholder")}
          registration={form.register("email")}
          error={form.formState.errors.email}
          startContent={<IconMail size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          type="email"
          inputMode="email"
          autoComplete="email"
          maxLength={320}
        />

        <PasswordField
          label={t("common.password")}
          placeholder={t("signup.passwordPlaceholder")}
          registration={form.register("password")}
          error={form.formState.errors.password}
          autoComplete="new-password"
          maxLength={1024}
        />

        <PasswordField
          label={t("signup.confirmPassword")}
          placeholder={t("signup.confirmPlaceholder")}
          registration={form.register("confirmPassword")}
          error={form.formState.errors.confirmPassword}
          autoComplete="new-password"
          maxLength={1024}
        />

        {session["invite-required"] ? (
          <TextField
            label={t("signup.inviteCode")}
            placeholder={t("signup.inviteCode")}
            registration={form.register("inviteCode")}
            error={form.formState.errors.inviteCode}
            startContent={<IconTicket size={18} stroke={1.75} className="text-default-400" aria-hidden />}
            isRequired
            maxLength={256}
          />
        ) : null}

        <Button
          type="submit"
          color="primary"
          size="lg"
          radius="sm"
          {...buttonProps("signup")}
          /* Validation runs before the request starts, so the click would
             otherwise sit with no feedback until it finishes. isSubmitting
             covers the whole submit, from the click until the work settles. */
          isLoading={form.formState.isSubmitting || pending === "signup"}
          className="mt-1 font-medium"
        >
          {t("signup.create")}
        </Button>
      </form>

      <p className="text-center text-sm text-default-500">
        {t("signup.haveAccount")}{" "}
        <button type="button" onClick={() => setSignup(false)} className="font-medium text-primary hover:underline">
          {t("common.signIn")}
        </button>
      </p>
    </AuthCard>
  );
}
