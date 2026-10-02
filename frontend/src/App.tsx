import { HeroUIProvider } from "@heroui/react";
import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { Provider as JotaiProvider } from "jotai";
import { useAtom } from "jotai";
import { currentFlowId, useAction, useFlow, useFlowAction, useSession, type Flow, type Session } from "./api";
import { signupModeAtom } from "./atoms";
import { LoginScreen } from "./screens/LoginScreen";
import { FactorScreen } from "./screens/FactorScreen";
import { SignupScreen } from "./screens/SignupScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { AuthorizeScreen, ContinueScreen } from "./screens/AuthorizeScreen";
import { MessageScreen } from "./screens/MessageScreen";
import { ResetScreen } from "./screens/ResetScreen";
import { ConfirmScreen } from "./screens/ConfirmScreen";

function Flows({ session, flow }: { session: Session; flow: Flow | null }) {
  const [signupMode, setSignupMode] = useAtom(signupModeAtom);
  const action = useAction();
  const flowAction = useFlowAction();
  const queryClient = useQueryClient();

  const attach = async () => {
    if (!flow || flow.did) return;
    const session = queryClient.getQueryData<Session>(["session"]);
    if (session?.stage === "authenticated") await flowAction.mutateAsync({ action: "attach", body: { accountCsrf: session.csrf } });
  };
  const switchAccount = async () => {
    await action.mutateAsync({ action: "logout" });
    setSignupMode(false);
  };

  if (flow?.did) return <AuthorizeScreen flow={flow} session={session} />;

  if (flow && session.stage === "authenticated")
    return <ContinueScreen flow={flow} session={session} onContinue={attach} onSwitchAccount={switchAccount} />;

  if (session.stage === "factor") return <FactorScreen session={session} onAuthenticated={attach} />;

  const clientId = flow?.["client-id"];
  const wantsSignup =
    session["signup-enabled"] && (signupMode || (flow?.parameters.prompt === "create" && session.stage === "login"));

  if (wantsSignup) return <SignupScreen session={session} clientId={clientId} onAuthenticated={attach} />;

  if (session.stage === "login")
    return (
      <LoginScreen
        session={session}
        clientId={clientId}
        loginHint={typeof flow?.parameters.login_hint === "string" ? flow.parameters.login_hint : undefined}
        onAuthenticated={attach}
      />
    );

  return <SettingsScreen session={session} />;
}

export function Root() {
  const session = useSession();

  // The reset link from the email: its token is in the path, and the page
  // needs no session at all, so it renders before anything else loads.
  const resetPath = window.location.pathname.match(/^\/account\/reset(?:\/(.*))?$/);
  if (resetPath) return <ResetScreen token={decodeURIComponent(resetPath[1] ?? "")} />;

  // The confirmation link works the same way: token in the path, no session.
  const confirmPath = window.location.pathname.match(/^\/account\/confirm\/(.+)$/);
  if (confirmPath) return <ConfirmScreen token={decodeURIComponent(confirmPath[1] ?? "")} />;

  const flow = useFlow();
  const flowId = currentFlowId();

  if (flowId && flow.isError)
    return (
      <MessageScreen
        title="Authorization"
        text={flow.error instanceof Error ? flow.error.message : "Restart authorization from your application."}
      />
    );

  if (session.isError)
    return (
      <MessageScreen
        title="Account"
        text={session.error instanceof Error ? session.error.message : "This page could not load."}
        link={{ href: "/account", label: "Try again" }}
      />
    );

  if (!session.data || (flowId !== null && !flow.data)) return null;

  return <Flows session={session.data} flow={flow.data ?? null} />;
}

export function App({ client }: { client?: QueryClient }) {
  const queryClient = client ?? new QueryClient();

  return (
    <QueryClientProvider client={queryClient}>
      <JotaiProvider>
        <HeroUIProvider>
          <Root />
        </HeroUIProvider>
      </JotaiProvider>
    </QueryClientProvider>
  );
}
