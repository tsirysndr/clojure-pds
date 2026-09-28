import { z } from "zod";

const password = z
  .string()
  .min(8, "Passwords need at least 8 characters")
  .max(1024, "That password is too long");

export const loginSchema = z.object({
  identifier: z
    .string()
    .trim()
    .min(1, "Enter your username, email address, or DID")
    .max(2048, "That identifier is too long"),
  password: z.string().min(1, "Enter your password").max(1024, "That password is too long"),
});

export type LoginValues = z.infer<typeof loginSchema>;

export const factorSchema = z.object({
  code: z
    .string()
    .trim()
    .min(6, "Enter the code you received")
    .max(64, "That code is too long"),
});

export type FactorValues = z.infer<typeof factorSchema>;

export const signupSchema = z.object({
  username: z
    .string()
    .trim()
    .min(1, "Choose a username")
    .max(63, "That username is too long")
    .regex(/^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$/, "Use letters, numbers, and inner hyphens"),
  email: z.email("Enter a valid email address").max(320, "That email address is too long"),
  password,
  inviteCode: z.string().trim().max(256, "That invite code is too long").or(z.literal("")),
});

export type SignupValues = z.infer<typeof signupSchema>;

export const totpCodeSchema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^(?:[0-9]{6}|[A-Z2-7]{26})$/, "Enter a 6-digit code or a recovery code"),
});

export type TotpCodeValues = z.infer<typeof totpCodeSchema>;

export const passkeyNameSchema = z.object({
  name: z.string().trim().min(1, "Name this passkey").max(64, "That name is too long"),
});

export type PasskeyNameValues = z.infer<typeof passkeyNameSchema>;

export const recoveryKeysSchema = z
  .object({
    keys: z.string().max(4096, "That key list is too long"),
    code: z.string().trim().min(1, "Enter the emailed identity code").max(64),
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
        message: "Enter up to four distinct public did:key values, one per line",
      });
  });

export type RecoveryKeysValues = z.infer<typeof recoveryKeysSchema>;
