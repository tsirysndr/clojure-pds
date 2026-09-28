import { useAtom } from "jotai";
import { noticeAtom } from "./atoms";
import { ApiError } from "./api";

export function useNotice() {
  const [notice, setNotice] = useAtom(noticeAtom);

  return {
    notice,
    clear: () => setNotice(null),
    show: (text: string) => setNotice({ text, tone: "info" }),
    showError: (error: unknown) => {
      const text =
        error instanceof ApiError
          ? error.message
          : error instanceof DOMException && error.name === "NotAllowedError"
            ? "The passkey request was cancelled."
            : error instanceof Error && error.message
              ? error.message
              : "Something went wrong. Try again.";
      setNotice({ text, tone: "danger" });
    },
  };
}
