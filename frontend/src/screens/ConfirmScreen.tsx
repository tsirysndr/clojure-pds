import { useState } from "react";
import { Button } from "@heroui/react";
import { useTranslation } from "react-i18next";
import { IconCircleCheck, IconMailCheck } from "@tabler/icons-react";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";

/// The landing page of the confirmation email. The single-use token in the link
/// is the whole proof; confirming is a click rather than an automatic effect of
/// the GET, so a mail scanner prefetching the link cannot consume the token.
export function ConfirmScreen({ token }: { token: string }) {
  const { t } = useTranslation();
  const [state, setState] = useState<{ done: boolean; busy: boolean; error: string }>({
    done: false,
    busy: false,
    error: "",
  });

  const confirm = async () => {
    setState({ done: false, busy: true, error: "" });
    try {
      const response = await fetch("/account/confirm", {
        method: "POST",
        headers: { "content-type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ token }),
      });
      if (!response.ok) {
        setState({ done: false, busy: false, error: t("confirm.failed") });
        return;
      }
      setState({ done: true, busy: false, error: "" });
    } catch {
      setState({ done: false, busy: false, error: t("confirm.failed") });
    }
  };

  if (state.done) {
    return (
      <AuthCard title={t("confirm.title")}>
        <div className="flex items-center gap-2 text-success">
          <IconCircleCheck size={20} aria-hidden />
          <p className="text-sm font-medium">{t("confirm.done")}</p>
        </div>
        <Button as="a" href="/account" color="primary" size="lg" radius="sm" className="font-medium">
          {t("common.signIn")}
        </Button>
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t("confirm.title")} subtitle={t("confirm.subtitle")}>
      {state.error ? <Alert tone="danger">{state.error}</Alert> : null}
      <Button
        color="primary"
        size="lg"
        radius="sm"
        isLoading={state.busy}
        startContent={<IconMailCheck size={18} stroke={1.75} />}
        onPress={() => void confirm()}
        isDisabled={!token}
        className="font-medium"
      >
        {t("confirm.submit")}
      </Button>
    </AuthCard>
  );
}
