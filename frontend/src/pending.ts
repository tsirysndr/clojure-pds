import { useState } from "react";
import { useAtom } from "jotai";
import { busyAtom } from "./atoms";
import { useNotice } from "./notice";

/** One in-flight action per screen: the clicked control shows a spinner
 *  immediately, everything else disables until the request settles. */
export function usePending() {
  const [busy, setBusy] = useAtom(busyAtom);
  const [pending, setPending] = useState<string | null>(null);
  const { notice, show, showError, clear } = useNotice();

  const run = async (label: string, work: () => Promise<void>) => {
    if (busy) return;
    setBusy(true);
    setPending(label);
    clear();
    try {
      await work();
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
      setPending(null);
    }
  };

  const buttonProps = (label: string) => ({
    isLoading: pending === label,
    isDisabled: busy && pending !== label,
  });

  return { busy, pending, run, buttonProps, notice, show, showError, clear };
}
