import { useTranslation } from "react-i18next";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@heroui/react";
import { IconShieldLock } from "@tabler/icons-react";
import type { Session } from "../api";
import { useAction } from "../api";
import { factorSchema, type FactorValues } from "../schemas";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { TextField } from "../components/Field";
import { usePending } from "../pending";

export function FactorScreen({
  session,
  onAuthenticated,
}: {
  session: Session;
  onAuthenticated: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const action = useAction();
  const { run, buttonProps, notice } = usePending();
  const email = session.factor === "email";

  const form = useForm<FactorValues>({
    resolver: zodResolver(factorSchema(t)),
    defaultValues: { code: "" },
  });

  const submit = form.handleSubmit((values) =>
    run("factor", async () => {
      await action.mutateAsync({ action: "login/factor", body: values });
      await onAuthenticated();
    }),
  );

  return (
    <AuthCard
      title={t("factor.title")}
      service={session.origin}
      subtitle={
        email
          ? t("factor.emailSubtitle", { handle: session.handle ?? t("factor.yourAccount") })
          : t("factor.totpSubtitle")
      }
    >
      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-4" noValidate>
        <TextField
          label={email ? t("factor.emailCode") : t("factor.totpCode")}
          placeholder={email ? t("factor.emailPlaceholder") : "123456"}
          registration={form.register("code")}
          error={form.formState.errors.code}
          startContent={<IconShieldLock size={18} stroke={1.75} className="text-default-400" aria-hidden />}
          autoComplete="one-time-code"
          inputMode={email ? "text" : "numeric"}
          maxLength={64}
          autoFocus
        />

        <Button type="submit" color="primary" size="lg" radius="sm" {...buttonProps("factor")} className="font-medium">
          {t("common.continue")}
        </Button>
      </form>
    </AuthCard>
  );
}
