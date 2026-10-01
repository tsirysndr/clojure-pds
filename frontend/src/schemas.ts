import { z } from "zod";

type T = (key: string) => string;

const password = (t: T) =>
  z
    .string()
  .min(8, t("schema.passwordMin"))
  .max(1024, t("schema.passwordLong"));

export const loginSchema = (t: T) =>
  z.object({
  identifier: z
    .string()
    .trim()
    .min(1, t("schema.identifierRequired"))
    .max(2048, t("schema.identifierLong")),
  password: z.string().min(1, t("schema.passwordRequired")).max(1024, t("schema.passwordLong")),
});

export type LoginValues = z.infer<ReturnType<typeof loginSchema>>;

export const factorSchema = (t: T) =>
  z.object({
  code: z
    .string()
    .trim()
    .min(6, t("schema.codeRequired"))
    .max(64, t("schema.codeLong")),
});

export type FactorValues = z.infer<ReturnType<typeof factorSchema>>;

export const signupSchema = (t: T) =>
  z
  .object({
    username: z
      .string()
      .trim()
      .min(1, t("schema.usernameRequired"))
      .max(63, t("schema.usernameLong"))
      .regex(/^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/, t("schema.usernameShape")),
    email: z.email(t("schema.emailValid")).max(320, t("schema.emailLong")),
    password: password(t),
    confirmPassword: z.string().max(1024, t("schema.passwordLong")),
    inviteCode: z.string().trim().max(256, t("schema.inviteLong")).or(z.literal("")),
  })
  .superRefine((value, context) => {
    if (value.confirmPassword !== value.password)
      context.addIssue({
        code: "custom",
        path: ["confirmPassword"],
        message: t("schema.noMatch"),
      });
  });

export type SignupValues = z.infer<ReturnType<typeof signupSchema>>;

export const totpCodeSchema = (t: T) =>
  z.object({
  code: z
    .string()
    .trim()
    .regex(/^(?:[0-9]{6}|[A-Z2-7]{26})$/, t("schema.totpShape")),
});

export type TotpCodeValues = z.infer<ReturnType<typeof totpCodeSchema>>;

export const passkeyNameSchema = (t: T) =>
  z.object({
  name: z.string().trim().min(1, t("schema.passkeyName")).max(64, t("schema.passkeyNameLong")),
});

export type PasskeyNameValues = z.infer<ReturnType<typeof passkeyNameSchema>>;

export const recoveryKeysSchema = (t: T) =>
  z
  .object({
    keys: z.string().max(4096, t("schema.keysLong")),
    code: z.string().trim().min(1, t("schema.identityCode")).max(64),
  })
  .superRefine((value, context) => {
    const keys = value.keys
      .split(/\r?\n/)
      .map((key) => key.trim())
      .filter(Boolean);
    if (keys.length > 4 || new Set(keys).size !== keys.length || keys.some((key) => !key.startsWith("did:key:")))
      context.addIssue({
        code: "custom",
        path: ["keys"],
        message: t("schema.keysShape"),
      });
  });

export type RecoveryKeysValues = z.infer<ReturnType<typeof recoveryKeysSchema>>;
