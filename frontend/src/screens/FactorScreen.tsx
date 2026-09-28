import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@heroui/react";
import { useAtom } from "jotai";
import { IconShieldLock } from "@tabler/icons-react";
import type { Session } from "../api";
import { useAction } from "../api";
import { factorSchema, type FactorValues } from "../schemas";
import { busyAtom } from "../atoms";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { TextField } from "../components/Field";
import { useNotice } from "../notice";

export function FactorScreen({
  session,
  onAuthenticated,
}: {
  session: Session;
  onAuthenticated: () => Promise<void>;
}) {
  const action = useAction();
  const [busy, setBusy] = useAtom(busyAtom);
  const { notice, showError, clear } = useNotice();
  const email = session.factor === "email";

  const form = useForm<FactorValues>({
    resolver: zodResolver(factorSchema),
    defaultValues: { code: "" },
  });

  const submit = form.handleSubmit(async (values) => {
    if (busy) return;
    setBusy(true);
    clear();
    try {
      await action.mutateAsync({ action: "login/factor", body: values });
      await onAuthenticated();
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  });

  return (
    <AuthCard
      title="Confirm it's you"
      service={session.origin}
      subtitle={
        email
          ? `We emailed a one-time code for ${session.handle ?? "your account"}.`
          : "Enter the code from your authenticator app or a recovery code."
      }
    >
      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4" noValidate>
        <TextField
          label={email ? "Email code" : "Authenticator code"}
          placeholder={email ? "Code from your email" : "123456"}
          registration={form.register("code")}
          error={form.formState.errors.code}
          startContent={<IconShieldLock size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          autoComplete="one-time-code"
          inputMode={email ? "text" : "numeric"}
          maxLength={64}
          autoFocus
        />

        <Button type="submit" color="primary" size="lg" radius="sm" isLoading={busy} className="font-medium">
          Continue
        </Button>
      </form>
    </AuthCard>
  );
}
