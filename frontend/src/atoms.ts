import { atom } from "jotai";
import { atomWithStorage } from "jotai/utils";

export type NoticeTone = "info" | "danger";

export const noticeAtom = atom<{ text: string; tone: NoticeTone } | null>(null);

export const signupModeAtom = atom(false);

export const busyAtom = atom(false);

export const totpEnrollmentAtom = atom<{ secret: string; uri: string } | null>(null);

export const recoveryCodesAtom = atom<string[] | null>(null);

/// The chosen language, remembered per browser. Empty until chosen, so the
/// browser's own preference wins on a first visit.
export const languageAtom = atomWithStorage("clojure-pds.language", "");
