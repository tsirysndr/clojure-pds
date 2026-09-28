import { useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button, Chip, Snippet } from "@heroui/react";
import { useAtom } from "jotai";
import {
  IconDeviceMobile,
  IconFingerprint,
  IconKey,
  IconLogout,
  IconMail,
  IconPlugConnected,
  IconRefresh,
  IconShieldLock,
  IconTrash,
  IconUserCircle,
} from "@tabler/icons-react";
import type { RecoveryPending, Session, SessionList } from "../api";
import { useAction } from "../api";
import {
  passkeyNameSchema,
  recoveryKeysSchema,
  totpCodeSchema,
  type PasskeyNameValues,
  type RecoveryKeysValues,
  type TotpCodeValues,
} from "../schemas";
import { busyAtom, recoveryCodesAtom, totpEnrollmentAtom } from "../atoms";
import { Alert } from "../components/Alert";
import { TextField, TextAreaField } from "../components/Field";
import { ceremonyOptions, credentialJSON, type ServerOptions } from "../webauthn";
import { useNotice } from "../notice";

function Section({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-xl border border-default-200 bg-content1 p-5">
      <h2 className="flex items-center gap-2 text-sm font-semibold">
        <span className="flex size-8 items-center justify-center rounded-lg bg-brand-soft text-brand dark:bg-primary-500/15">
          {icon}
        </span>
        {title}
      </h2>
      {children}
    </section>
  );
}

function timestamp(value: string | null | undefined): string {
  if (!value) return "never";
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? value : parsed.toLocaleString();
}

export function SettingsScreen({ session }: { session: Session }) {
  const action = useAction();
  const [busy, setBusy] = useAtom(busyAtom);
  const { notice, show, showError, clear } = useNotice();
  const [enrollment, setEnrollment] = useAtom(totpEnrollmentAtom);
  const [recoveryCodes, setRecoveryCodes] = useAtom(recoveryCodesAtom);
  const [sessions, setSessions] = useState<SessionList | null>(null);

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

  const passkeyForm = useForm<PasskeyNameValues>({
    resolver: zodResolver(passkeyNameSchema),
    defaultValues: { name: "" },
  });
  const totpForm = useForm<TotpCodeValues>({ resolver: zodResolver(totpCodeSchema), defaultValues: { code: "" } });
  const disableForm = useForm<TotpCodeValues>({ resolver: zodResolver(totpCodeSchema), defaultValues: { code: "" } });
  const recoveryForm = useForm<RecoveryKeysValues>({
    resolver: zodResolver(recoveryKeysSchema),
    defaultValues: { keys: "", code: "" },
  });

  const addPasskey = passkeyForm.handleSubmit((values) =>
    run(async () => {
      const started = await action.mutateAsync({ action: "passkeys/begin", body: values });
      const credential = (await navigator.credentials.create(
        ceremonyOptions(started.options as ServerOptions, true),
      )) as PublicKeyCredential | null;
      if (!credential) return;
      await action.mutateAsync({
        action: "passkeys/finish",
        body: { id: started.id, response: credentialJSON(credential) },
      });
      passkeyForm.reset();
      show("Passkey added. You can use it the next time you sign in.");
    }),
  );

  const beginTotp = () =>
    run(async () => {
      const result = await action.mutateAsync({ action: "totp/begin" });
      setEnrollment({ secret: String(result.secret), uri: String(result.uri) });
    });

  const confirmTotp = totpForm.handleSubmit((values) =>
    run(async () => {
      const result = await action.mutateAsync({ action: "totp/confirm", body: values });
      setEnrollment(null);
      totpForm.reset();
      setRecoveryCodes((result["recovery-codes"] as string[]) ?? []);
      show("Authenticator enabled. Save your recovery codes before leaving.");
    }),
  );

  const disableTotp = disableForm.handleSubmit((values) =>
    run(async () => {
      if (!window.confirm("Remove your authenticator and invalidate its recovery codes?")) return;
      await action.mutateAsync({ action: "totp/disable", body: values });
      disableForm.reset();
      show("Authenticator removed.");
    }),
  );

  const listSessions = (cursor?: string) =>
    run(async () => {
      const result = await action.mutateAsync({ action: "oauth/list", body: cursor ? { cursor } : {} });
      const page = result["oauth-sessions"] as SessionList;
      setSessions((current) =>
        cursor && current ? { ...page, items: [...current.items, ...page.items] } : page,
      );
    });

  const recoveryState = session["recovery-keys"];
  const changeRecovery = recoveryForm.handleSubmit((values) =>
    run(async () => {
      const keys = values.keys
        .split(/\r?\n/)
        .map((key) => key.trim())
        .filter(Boolean);
      const question = keys.length
        ? "Replace your identity recovery keys with this ordered list?"
        : "Remove all independent identity recovery keys? The server keeps its control key.";
      if (!window.confirm(question)) return;
      const result = await action.mutateAsync({
        action: "identity/recovery/change",
        body: { previousCid: recoveryState?.operationCid, recoveryKeys: keys, code: values.code },
      });
      recoveryForm.reset();
      show(
        result.state === "completed"
          ? "Recovery keys updated."
          : result.state === "unchanged"
            ? "These recovery keys are already configured."
            : "Your change is saved. Refresh its status or retry the saved change.",
      );
    }),
  );

  const pending = recoveryState?.pending ?? null;
  const retryable = (value: RecoveryPending | null): value is Extract<RecoveryPending, { kind: "recovery" }> =>
    value?.kind === "recovery" && "previousCid" in value;

  const shown = sessions ?? session["oauth-sessions"] ?? null;

  return (
    <div className="mx-auto flex min-h-svh w-full max-w-3xl flex-col gap-4 px-4 py-8 sm:py-12">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-xl bg-brand-soft text-brand dark:bg-primary-500/15">
            <IconUserCircle size={22} stroke={1.75} aria-hidden />
          </span>
          <div>
            <h1 className="text-lg font-semibold">{session.handle}</h1>
            <p className="text-xs text-default-500">{session.origin}</p>
          </div>
        </div>
        <Button
          variant="bordered"
          radius="sm"
          isDisabled={busy}
          startContent={<IconLogout size={16} stroke={1.75} />}
          onPress={() =>
            void run(async () => {
              await action.mutateAsync({ action: "logout" });
              window.location.replace("/account");
            })
          }
        >
          Sign out
        </Button>
      </header>

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      {recoveryCodes ? (
        <section className="flex flex-col gap-3 rounded-xl border border-primary-300 bg-brand-soft/60 p-5 dark:bg-primary-500/10">
          <h2 className="text-sm font-semibold">Recovery codes</h2>
          <p className="text-sm text-default-600">
            Save these one-time codes somewhere safe. Each unlocks your account once if you lose the authenticator.
          </p>
          <Snippet symbol="" radius="sm" className="font-mono text-sm" codeString={recoveryCodes.join("\n")}>
            <div className="flex flex-col">
              {recoveryCodes.map((code) => (
                <span key={code}>{code}</span>
              ))}
            </div>
          </Snippet>
          <Button size="sm" variant="bordered" radius="sm" className="self-start" onPress={() => setRecoveryCodes(null)}>
            I saved them
          </Button>
        </section>
      ) : null}

      {session["passkeys-available"] ? (
        <Section icon={<IconFingerprint size={18} stroke={1.75} aria-hidden />} title="Passkeys">
          {(session.passkeys ?? []).length === 0 ? (
            <p className="text-sm text-default-500">No passkeys yet. Add one to sign in without a password.</p>
          ) : (
            <ul className="flex flex-col divide-y divide-default-100">
              {(session.passkeys ?? []).map((passkey) => (
                <li key={passkey.id} className="flex items-center justify-between gap-3 py-2.5">
                  <div className="min-w-0">
                    <p className="text-sm font-medium wrap-anywhere">{passkey.name}</p>
                    <p className="text-xs text-default-500">
                      Added {timestamp(passkey["created-at"])} · Last used {timestamp(passkey["last-used-at"])}
                    </p>
                  </div>
                  <Button
                    isIconOnly
                    size="sm"
                    variant="light"
                    color="danger"
                    radius="full"
                    aria-label={`Remove passkey ${passkey.name}`}
                    isDisabled={busy}
                    onPress={() =>
                      void run(async () => {
                        if (!window.confirm(`Remove the passkey "${passkey.name}"?`)) return;
                        await action.mutateAsync({ action: "passkeys/remove", body: { id: passkey.id } });
                        show("Passkey removed.");
                      })
                    }
                  >
                    <IconTrash size={16} stroke={1.75} />
                  </Button>
                </li>
              ))}
            </ul>
          )}

          <form onSubmit={(event) => void addPasskey(event)} className="flex items-end gap-2" noValidate>
            <div className="flex-1">
              <TextField
                label="New passkey name"
                placeholder="This device"
                registration={passkeyForm.register("name")}
                error={passkeyForm.formState.errors.name}
                maxLength={64}
              />
            </div>
            <Button type="submit" color="primary" radius="sm" className="h-12" isLoading={busy}>
              Add passkey
            </Button>
          </form>
        </Section>
      ) : null}

      <Section icon={<IconShieldLock size={18} stroke={1.75} aria-hidden />} title="Two-factor authentication">
        {session.factor === "totp" ? (
          <>
            <div className="flex items-center gap-2 text-sm text-default-600">
              <Chip size="sm" color="success" variant="flat">
                Enabled
              </Chip>
              An authenticator app protects your sign-in.
            </div>
            <form onSubmit={(event) => void disableTotp(event)} className="flex items-end gap-2" noValidate>
              <div className="flex-1">
                <TextField
                  label="Current code"
                  placeholder="123456"
                  registration={disableForm.register("code")}
                  error={disableForm.formState.errors.code}
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  maxLength={26}
                />
              </div>
              <Button type="submit" color="danger" variant="flat" radius="sm" className="h-12" isLoading={busy}>
                Remove
              </Button>
            </form>
          </>
        ) : session.factor === "email" ? (
          <>
            <p className="flex items-center gap-2 text-sm text-default-600">
              <IconMail size={16} stroke={1.75} aria-hidden />
              Email verification codes protect your sign-in.
            </p>
            <Button
              color="danger"
              variant="flat"
              radius="sm"
              className="self-start"
              isDisabled={busy}
              onPress={() =>
                void run(async () => {
                  if (!window.confirm("Turn off email verification for sign-in?")) return;
                  await action.mutateAsync({ action: "email/disable" });
                  show("Email verification turned off. You can now add an authenticator.");
                })
              }
            >
              Turn off email verification
            </Button>
          </>
        ) : enrollment ? (
          <div className="flex flex-col gap-3">
            <p className="text-sm text-default-600">
              Add this secret to Google Authenticator or a compatible app, then confirm with a code.
            </p>
            <Snippet symbol="" radius="sm" className="font-mono text-sm" codeString={enrollment.secret}>
              {enrollment.secret}
            </Snippet>
            <form onSubmit={(event) => void confirmTotp(event)} className="flex items-end gap-2" noValidate>
              <div className="flex-1">
                <TextField
                  label="Code from your app"
                  placeholder="123456"
                  registration={totpForm.register("code")}
                  error={totpForm.formState.errors.code}
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  maxLength={26}
                />
              </div>
              <Button type="submit" color="primary" radius="sm" className="h-12" isLoading={busy}>
                Confirm
              </Button>
            </form>
          </div>
        ) : (
          <>
            <p className="text-sm text-default-500">
              Protect sign-in with one-time codes from an authenticator app.
            </p>
            <Button
              color="primary"
              variant="flat"
              radius="sm"
              className="self-start"
              isDisabled={busy}
              startContent={<IconDeviceMobile size={16} stroke={1.75} />}
              onPress={() => void beginTotp()}
            >
              Add an authenticator
            </Button>
          </>
        )}
      </Section>

      <Section icon={<IconPlugConnected size={18} stroke={1.75} aria-hidden />} title="Connected applications">
        {!shown || shown.items.length === 0 ? (
          <p className="text-sm text-default-500">No applications are connected through OAuth.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-default-100">
            {shown.items.map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-3 py-2.5">
                <div className="min-w-0">
                  <p className="text-sm font-medium wrap-anywhere">{entry["client-id"]}</p>
                  <p className="text-xs text-default-500">Expires {timestamp(entry["expires-at"])}</p>
                </div>
                <Button
                  size="sm"
                  variant="flat"
                  color="danger"
                  radius="sm"
                  isDisabled={busy}
                  onPress={() =>
                    void run(async () => {
                      await action.mutateAsync({ action: "oauth/revoke", body: { id: entry.id } });
                      setSessions((current) =>
                        current ? { ...current, items: current.items.filter((item) => item.id !== entry.id) } : current,
                      );
                      show("Application access revoked.");
                    })
                  }
                >
                  Revoke
                </Button>
              </li>
            ))}
          </ul>
        )}
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="bordered"
            radius="sm"
            isDisabled={busy}
            startContent={<IconRefresh size={14} stroke={1.75} />}
            onPress={() => void listSessions()}
          >
            Refresh
          </Button>
          {shown?.cursor ? (
            <Button size="sm" variant="bordered" radius="sm" isDisabled={busy} onPress={() => void listSessions(shown.cursor)}>
              Load more
            </Button>
          ) : null}
        </div>
      </Section>

      {recoveryState ? (
        <Section icon={<IconKey size={18} stroke={1.75} aria-hidden />} title="Identity recovery keys">
          <p className="text-sm text-default-500">
            Independent public keys that can recover your did:plc identity if this server disappears. The server
            always keeps its own control key.
          </p>

          {recoveryState.recoveryKeys.length > 0 ? (
            <ul className="flex flex-col gap-1">
              {recoveryState.recoveryKeys.map((key) => (
                <li key={key} className="font-mono text-xs text-default-600 wrap-anywhere">
                  {key}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-default-500">No independent recovery keys are configured.</p>
          )}

          {pending ? (
            <Alert tone="info">
              A {pending.kind} identity change is {pending.state}.{" "}
              {retryable(pending) ? "You can retry the saved change." : "Finish it before making another change."}
            </Alert>
          ) : null}

          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="bordered"
              radius="sm"
              isDisabled={busy}
              startContent={<IconRefresh size={14} stroke={1.75} />}
              onPress={() => void run(async () => void (await action.mutateAsync({ action: "identity/recovery/status" })))}
            >
              Refresh status
            </Button>
            <Button
              size="sm"
              variant="bordered"
              radius="sm"
              isDisabled={busy || !session["email-enabled"]}
              startContent={<IconMail size={14} stroke={1.75} />}
              onPress={() =>
                void run(async () => {
                  await action.mutateAsync({ action: "identity/recovery/email" });
                  show("Verification email requested. Use the latest identity-operation code from your inbox.");
                })
              }
            >
              Email me a code
            </Button>
            {retryable(pending) ? (
              <Button
                size="sm"
                color="primary"
                variant="flat"
                radius="sm"
                isDisabled={busy}
                onPress={() =>
                  void run(async () => {
                    const result = await action.mutateAsync({
                      action: "identity/recovery/change",
                      body: { previousCid: pending.previousCid, recoveryKeys: pending.recoveryKeys },
                    });
                    show(
                      result.state === "completed"
                        ? "Recovery keys updated."
                        : "Your change is saved. Refresh its status or retry the saved change.",
                    );
                  })
                }
              >
                Retry saved change
              </Button>
            ) : null}
          </div>

          <form onSubmit={(event) => void changeRecovery(event)} className="flex flex-col gap-3" noValidate>
            <TextAreaField
              label="Replacement keys (one did:key per line, empty to remove all)"
              placeholder="did:key:zQ3sh..."
              registration={recoveryForm.register("keys")}
              error={recoveryForm.formState.errors.keys}
            />
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <TextField
                  label="Emailed identity code"
                  placeholder="Code from your email"
                  registration={recoveryForm.register("code")}
                  error={recoveryForm.formState.errors.code}
                  autoComplete="one-time-code"
                  maxLength={64}
                />
              </div>
              <Button type="submit" color="primary" radius="sm" className="h-12" isLoading={busy}>
                Save keys
              </Button>
            </div>
          </form>
        </Section>
      ) : null}
    </div>
  );
}
