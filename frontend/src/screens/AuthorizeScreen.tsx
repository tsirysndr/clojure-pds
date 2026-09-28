import { Button } from "@heroui/react";
import type { Flow, Session } from "../api";
import { useFlowAction } from "../api";
import { navigate } from "../navigation";
import { AuthCard } from "../components/AuthCard";
import { Alert } from "../components/Alert";
import { ClientPanel } from "../components/ClientPanel";
import { PermissionList } from "../components/PermissionList";
import { usePending } from "../pending";

export function AuthorizeScreen({ flow, session }: { flow: Flow; session: Session }) {
  const flowAction = useFlowAction();
  const { run, buttonProps, notice } = usePending();

  const decide = (approve: boolean) =>
    run(approve ? "approve" : "deny", async () => {
      const result = await flowAction.mutateAsync({ action: "decide", body: { approve } });
      navigate(result.location as string);
    });

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
          {...buttonProps("approve")}
          onPress={() => void decide(true)}
        >
          Allow access
        </Button>
        <Button
          variant="bordered"
          size="lg"
          radius="sm"
          className="sm:flex-1"
          {...buttonProps("deny")}
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
  const { run, buttonProps, notice } = usePending();
  const freshLogin = flow.parameters.prompt === "login";

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
          {...buttonProps("continue")}
          onPress={() => void run("continue", freshLogin ? onSwitchAccount : onContinue)}
        >
          {freshLogin ? "Sign in again" : `Continue as ${session.handle ?? "this account"}`}
        </Button>
        <Button variant="bordered" size="lg" radius="sm" {...buttonProps("switch")} onPress={() => void run("switch", onSwitchAccount)}>
          Use another account
        </Button>
      </div>
    </AuthCard>
  );
}
