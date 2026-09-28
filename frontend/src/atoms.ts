import { atom } from "jotai";

export type NoticeTone = "info" | "danger";

export const noticeAtom = atom<{ text: string; tone: NoticeTone } | null>(null);

export const signupModeAtom = atom(false);

export const busyAtom = atom(false);

export const totpEnrollmentAtom = atom<{ secret: string; uri: string } | null>(null);

export const recoveryCodesAtom = atom<string[] | null>(null);
