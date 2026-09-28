import { Button } from "@heroui/react";
import { useAtom } from "jotai";
import type { Flow, Session } from "../api";
import { useFlowAction } from "../api";
import { busyAtom } from "../atoms";
import { navigate } from "../navigation";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { ClientPanel } from "../components/ClientPanel";
import { PermissionList } from "../components/PermissionList";
import { useNotice } from "../notice";

export function AuthorizeScreen({ flow, session }: { flow: Flow; session: Session }) {
  const flowAction = useFlowAction();
  const [busy, setBusy] = useAtom(busyAtom);
  const { notice, showError, clear } = useNotice();

  const decide = async (approve: boolean) => {
    if (busy) return;
    setBusy(true);
    clear();
    try {
      const result = await flowAction.mutateAsync({ action: "decide", body: { approve } });
      navigate(result.location as string);
    } catch (error) {
      showError(error);
      setBusy(false);
    }
  };

  return (
    <AuthCard title="Authorize access" service={session.origin} width="wide">
      <ClientPanel clientId={flow["client-id"]} handle={session.handle ?? flow.did} />

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      <PermissionList permissions={flow.permissions} permissionSets={flow["permission-sets"]} />

      <div className="flex flex-col gap-2 sm:flex-row-reverse">
        <Button
          color="primary"
          size="lg"
          radius="sm"
          className="font-medium sm:flex-1"
          isLoading={busy}
          onPress={() => void decide(true)}
        >
          Allow access
        </Button>
        <Button
          variant="bordered"
          size="lg"
          radius="sm"
          className="sm:flex-1"
          isDisabled={busy}
          onPress={() => void decide(false)}
        >
          Deny
        </Button>
      </div>

      <p className="text-center text-xs text-default-400">
        You can revoke this application later from your account page.
      </p>
    </AuthCard>
  );
}

export function ContinueScreen({
  flow,
  session,
  onContinue,
  onSwitchAccount,
}: {
  flow: Flow;
  session: Session;
  onContinue: () => Promise<void>;
  onSwitchAccount: () => Promise<void>;
}) {
  const [busy, setBusy] = useAtom(busyAtom);
  const { notice, showError, clear } = useNotice();
  const freshLogin = flow.parameters.prompt === "login";

  const run = async (work: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    clear();
    try {
      await work();
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthCard title="Continue to the application" service={session.origin}>
      <ClientPanel clientId={flow["client-id"]} handle={session.handle} />

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      {freshLogin ? (
        <Alert tone="info">This application asks you to sign in again to continue.</Alert>
      ) : null}

      <div className="flex flex-col gap-2">
        <Button
          color="primary"
          size="lg"
          radius="sm"
          className="font-medium"
          isLoading={busy}
          onPress={() => void run(freshLogin ? onSwitchAccount : onContinue)}
        >
          {freshLogin ? "Sign in again" : `Continue as ${session.handle ?? "this account"}`}
        </Button>
        <Button variant="bordered" size="lg" radius="sm" isDisabled={busy} onPress={() => void run(onSwitchAccount)}>
          Use another account
        </Button>
      </div>
    </AuthCard>
  );
}
