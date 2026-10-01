import { useTranslation } from "react-i18next";
import { useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button, Chip, Snippet } from "@heroui/react";
import { QRCodeSVG } from "qrcode.react";
import { useAtom } from "jotai";
import { usePending } from "../pending";
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
import { recoveryCodesAtom, totpEnrollmentAtom } from "../atoms";
import { Alert } from "../components/Alert";
import { TextField, TextAreaField } from "../components/Field";
import { ceremonyOptions, credentialJSON, type ServerOptions } from "../webauthn";

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
  const { t } = useTranslation();
  const action = useAction();
  const { busy, pending: pendingAction, run, buttonProps, notice, show } = usePending();
  const pendingIs = (label: string) => pendingAction === label;
  const [enrollment, setEnrollment] = useAtom(totpEnrollmentAtom);
  const [recoveryCodes, setRecoveryCodes] = useAtom(recoveryCodesAtom);
  const [sessions, setSessions] = useState<SessionList | null>(null);

  const passkeyForm = useForm<PasskeyNameValues>({
    resolver: zodResolver(passkeyNameSchema(t)),
    defaultValues: { name: "" },
  });
  const totpForm = useForm<TotpCodeValues>({ resolver: zodResolver(totpCodeSchema(t)), defaultValues: { code: "" } });
  const disableForm = useForm<TotpCodeValues>({ resolver: zodResolver(totpCodeSchema(t)), defaultValues: { code: "" } });
  const recoveryForm = useForm<RecoveryKeysValues>({
    resolver: zodResolver(recoveryKeysSchema(t)),
    defaultValues: { keys: "", code: "" },
  });

  const addPasskey = passkeyForm.handleSubmit((values) =>
    run("passkey-add", async () => {
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
      show(t("settings.passkeyAdded"));
    }),
  );

  const beginTotp = () =>
    run("totp-begin", async () => {
      const result = await action.mutateAsync({ action: "totp/begin" });
      setEnrollment({ secret: String(result.secret), uri: String(result.uri) });
    });

  const confirmTotp = totpForm.handleSubmit((values) =>
    run("totp-confirm", async () => {
      const result = await action.mutateAsync({ action: "totp/confirm", body: values });
      setEnrollment(null);
      totpForm.reset();
      setRecoveryCodes((result["recovery-codes"] as string[]) ?? []);
      show(t("settings.totpEnabled"));
    }),
  );

  const disableTotp = disableForm.handleSubmit((values) =>
    run("totp-disable", async () => {
      if (!window.confirm(t("settings.totpOffConfirm"))) return;
      await action.mutateAsync({ action: "totp/disable", body: values });
      disableForm.reset();
      show(t("settings.totpRemoved"));
    }),
  );

  const listSessions = (cursor?: string) =>
    run(cursor ? "sessions-more" : "sessions-refresh", async () => {
      const result = await action.mutateAsync({ action: "oauth/list", body: cursor ? { cursor } : {} });
      const page = result["oauth-sessions"] as SessionList;
      setSessions((current) =>
        cursor && current ? { ...page, items: [...current.items, ...page.items] } : page,
      );
    });

  const recoveryState = session["recovery-keys"];
  const changeRecovery = recoveryForm.handleSubmit((values) =>
    run("recovery-save", async () => {
      const keys = values.keys
        .split(/\r?\n/)
        .map((key) => key.trim())
        .filter(Boolean);
      const question = keys.length
        ? t("settings.replaceConfirm")
        : t("settings.removeConfirm");
      if (!window.confirm(question)) return;
      const result = await action.mutateAsync({
        action: "identity/recovery/change",
        body: { previousCid: recoveryState?.operationCid, recoveryKeys: keys, code: values.code },
      });
      recoveryForm.reset();
      show(
        result.state === "completed"
          ? t("settings.keysUpdated")
          : result.state === "unchanged"
            ? t("settings.keysAlready")
            : t("settings.changeSaved"),
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
          {...buttonProps("logout")}
          startContent={<IconLogout size={16} stroke={1.75} />}
          onPress={() =>
            void run("logout", async () => {
              await action.mutateAsync({ action: "logout" });
              window.location.replace("/account");
            })
          }
        >
          {t("common.signOut")}
        </Button>
      </header>

      {notice ? <Alert tone={notice.tone}>{notice.text}</Alert> : null}

      {recoveryCodes ? (
        <section className="flex flex-col gap-3 rounded-xl border border-primary-300 bg-brand-soft/60 p-5 dark:bg-primary-500/10">
          <h2 className="text-sm font-semibold">{t("settings.recoveryCodes")}</h2>
          <p className="text-sm text-default-600">
            {t("settings.recoveryCodesBody")}
          </p>
          <Snippet symbol="" radius="sm" className="font-mono text-sm" codeString={recoveryCodes.join("\n")}>
            <div className="flex flex-col">
              {recoveryCodes.map((code) => (
                <span key={code}>{code}</span>
              ))}
            </div>
          </Snippet>
          <Button size="sm" variant="bordered" radius="sm" className="self-start" onPress={() => setRecoveryCodes(null)}>
            {t("settings.savedThem")}
          </Button>
        </section>
      ) : null}

      {session["passkeys-available"] ? (
        <Section icon={<IconFingerprint size={18} stroke={1.75} aria-hidden />} title={t("settings.passkeys")}>
          {(session.passkeys ?? []).length === 0 ? (
            <p className="text-sm text-default-500">{t("settings.noPasskeys")}</p>
          ) : (
            <ul className="flex flex-col divide-y divide-default-100">
              {(session.passkeys ?? []).map((passkey) => (
                <li key={passkey.id} className="flex items-center justify-between gap-3 py-2.5">
                  <div className="min-w-0">
                    <p className="text-sm font-medium wrap-anywhere">{passkey.name}</p>
                    <p className="text-xs text-default-500">
                      {t("settings.addedUsed", { added: timestamp(passkey["created-at"]), used: timestamp(passkey["last-used-at"]) })}
                    </p>
                  </div>
                  <Button
                    isIconOnly
                    size="sm"
                    variant="light"
                    color="danger"
                    radius="full"
                    aria-label={t("settings.removePasskeyAria", { name: passkey.name })}
                    {...buttonProps(`passkey-remove-${passkey.id}`)}
                    onPress={() =>
                      void run(`passkey-remove-${passkey.id}`, async () => {
                        if (!window.confirm(t("settings.removePasskeyConfirm", { name: passkey.name }))) return;
                        await action.mutateAsync({ action: "passkeys/remove", body: { id: passkey.id } });
                        show(t("settings.passkeyRemoved"));
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
                label={t("settings.newPasskeyName")}
                placeholder={t("settings.newPasskeyPlaceholder")}
                registration={passkeyForm.register("name")}
                error={passkeyForm.formState.errors.name}
                maxLength={64}
              />
            </div>
            <Button type="submit" color="primary" radius="sm" className="h-12" {...buttonProps("passkey-add")}>
              {t("settings.addPasskey")}
            </Button>
          </form>
        </Section>
      ) : null}

      <Section icon={<IconShieldLock size={18} stroke={1.75} aria-hidden />} title={t("settings.twoFactor")}>
        {session.factor === "totp" ? (
          <>
            <div className="flex items-center gap-2 text-sm text-default-600">
              <Chip size="sm" color="success" variant="flat">
                {t("settings.enabled")}
              </Chip>
              {t("settings.totpProtects")}
            </div>
            <form onSubmit={(event) => void disableTotp(event)} className="flex items-end gap-2" noValidate>
              <div className="flex-1">
                <TextField
                  label={t("settings.currentCode")}
                  placeholder="123456"
                  registration={disableForm.register("code")}
                  error={disableForm.formState.errors.code}
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  maxLength={26}
                />
              </div>
              <Button type="submit" color="danger" variant="flat" radius="sm" className="h-12" {...buttonProps("totp-disable")}>
                {t("common.remove")}
              </Button>
            </form>
          </>
        ) : session.factor === "email" ? (
          <>
            <p className="flex items-center gap-2 text-sm text-default-600">
              <IconMail size={16} stroke={1.75} aria-hidden />
              {t("settings.emailProtects")}
            </p>
            <Button
              color="danger"
              variant="flat"
              radius="sm"
              className="self-start"
              {...buttonProps("email-off")}
              onPress={() =>
                void run("email-off", async () => {
                  if (!window.confirm(t("settings.emailOffConfirm"))) return;
                  await action.mutateAsync({ action: "email/disable" });
                  show(t("settings.emailOffDone"));
                })
              }
            >
              {t("settings.turnOffEmail")}
            </Button>
          </>
        ) : enrollment ? (
          <div className="flex flex-col gap-3">
            <p className="text-sm text-default-600">
              {t("settings.scanThis")}
            </p>
            <figure className="flex flex-col items-center gap-2 self-start rounded-md bg-white p-3">
              <QRCodeSVG
                value={enrollment.uri}
                size={176}
                level="M"
                marginSize={0}
                title={t("settings.qrTitle")}
              />
            </figure>
            <p className="text-sm text-default-600">
              {t("settings.cannotScan")}
            </p>
            <Snippet symbol="" radius="sm" className="font-mono text-sm" codeString={enrollment.secret}>
              {enrollment.secret}
            </Snippet>
            <form onSubmit={(event) => void confirmTotp(event)} className="flex items-end gap-2" noValidate>
              <div className="flex-1">
                <TextField
                  label={t("settings.codeFromApp")}
                  placeholder="123456"
                  registration={totpForm.register("code")}
                  error={totpForm.formState.errors.code}
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  maxLength={26}
                />
              </div>
              <Button type="submit" color="primary" radius="sm" className="h-12" {...buttonProps("totp-confirm")}>
                {t("common.confirm")}
              </Button>
            </form>
          </div>
        ) : (
          <>
            <p className="text-sm text-default-500">
              {t("settings.protectHint")}
            </p>
            <Button
              color="primary"
              variant="flat"
              radius="sm"
              className="self-start"
              {...buttonProps("totp-begin")}
              startContent={<IconDeviceMobile size={16} stroke={1.75} />}
              onPress={() => void beginTotp()}
            >
              {t("settings.addAuthenticator")}
            </Button>
          </>
        )}
      </Section>

      <Section icon={<IconPlugConnected size={18} stroke={1.75} aria-hidden />} title={t("settings.connectedApps")}>
        {!shown || shown.items.length === 0 ? (
          <p className="text-sm text-default-500">{t("settings.noApps")}</p>
        ) : (
          <ul className="flex flex-col divide-y divide-default-100">
            {shown.items.map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-3 py-2.5">
                <div className="min-w-0">
                  <p className="text-sm font-medium wrap-anywhere">{entry["client-id"]}</p>
                  <p className="text-xs text-default-500">{t("settings.expires", { when: timestamp(entry["expires-at"]) })}</p>
                </div>
                <Button
                  size="sm"
                  variant="flat"
                  color="danger"
                  radius="sm"
                  {...buttonProps(`revoke-${entry.id}`)}
                  onPress={() =>
                    void run(`revoke-${entry.id}`, async () => {
                      await action.mutateAsync({ action: "oauth/revoke", body: { id: entry.id } });
                      setSessions((current) =>
                        current ? { ...current, items: current.items.filter((item) => item.id !== entry.id) } : current,
                      );
                      show(t("settings.revoked"));
                    })
                  }
                >
                  {t("settings.revoke")}
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
            {...buttonProps("sessions-refresh")}
            startContent={<IconRefresh size={14} stroke={1.75} />}
            onPress={() => void listSessions()}
          >
            {t("common.refresh")}
          </Button>
          {shown?.cursor ? (
            <Button size="sm" variant="bordered" radius="sm" {...buttonProps("sessions-more")} onPress={() => void listSessions(shown.cursor)}>
              {t("settings.loadMore")}
            </Button>
          ) : null}
        </div>
      </Section>

      {recoveryState ? (
        <Section icon={<IconKey size={18} stroke={1.75} aria-hidden />} title={t("settings.recoveryKeys")}>
          <p className="text-sm text-default-500">
            {t("settings.recoveryKeysBody")}
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
            <p className="text-sm text-default-500">{t("settings.noRecoveryKeys")}</p>
          )}

          {pending ? (
            <Alert tone="info">
              {t("settings.pendingChange", { kind: pending.kind, state: pending.state })}{" "}
              {retryable(pending) ? t("settings.retryHint") : t("settings.finishHint")}
            </Alert>
          ) : null}

          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="bordered"
              radius="sm"
              {...buttonProps("recovery-status")}
              startContent={<IconRefresh size={14} stroke={1.75} />}
              onPress={() => void run("recovery-status", async () => void (await action.mutateAsync({ action: "identity/recovery/status" })))}
            >
              {t("settings.refreshStatus")}
            </Button>
            <Button
              size="sm"
              variant="bordered"
              radius="sm"
              isLoading={pendingIs("recovery-email")}
              isDisabled={(busy && !pendingIs("recovery-email")) || !session["email-enabled"]}
              startContent={<IconMail size={14} stroke={1.75} />}
              onPress={() =>
                void run("recovery-email", async () => {
                  await action.mutateAsync({ action: "identity/recovery/email" });
                  show(t("settings.emailRequested"));
                })
              }
            >
              {t("settings.emailMe")}
            </Button>
            {retryable(pending) ? (
              <Button
                size="sm"
                color="primary"
                variant="flat"
                radius="sm"
                {...buttonProps("recovery-retry")}
                onPress={() =>
                  void run("recovery-retry", async () => {
                    const result = await action.mutateAsync({
                      action: "identity/recovery/change",
                      body: { previousCid: pending.previousCid, recoveryKeys: pending.recoveryKeys },
                    });
                    show(
                      result.state === "completed"
                        ? t("settings.keysUpdated")
                        : t("settings.changeSaved"),
                    );
                  })
                }
              >
                {t("settings.retrySaved")}
              </Button>
            ) : null}
          </div>

          <form onSubmit={(event) => void changeRecovery(event)} className="flex flex-col gap-3" noValidate>
            <TextAreaField
              label={t("settings.replacementKeys")}
              placeholder="did:key:zQ3sh..."
              registration={recoveryForm.register("keys")}
              error={recoveryForm.formState.errors.keys}
            />
            <div className="flex items-end gap-2">
              <div className="flex-1">
                <TextField
                  label={t("settings.emailedCode")}
                  placeholder={t("settings.emailedCodePlaceholder")}
                  registration={recoveryForm.register("code")}
                  error={recoveryForm.formState.errors.code}
                  autoComplete="one-time-code"
                  maxLength={64}
                />
              </div>
              <Button type="submit" color="primary" radius="sm" className="h-12" {...buttonProps("recovery-save")}>
                {t("settings.saveKeys")}
              </Button>
            </div>
          </form>
        </Section>
      ) : null}
    </div>
  );
}
