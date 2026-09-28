import { IconApps, IconUserCircle } from "@tabler/icons-react";

export function ClientPanel({ clientId, handle }: { clientId: string; handle?: string | null }) {
  return (
    <section
      aria-label="Application"
      className="flex flex-col gap-3 rounded-xl border border-default-200 bg-default-50/60 p-4"
    >
      <div className="flex items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-brand dark:bg-primary-500/15">
          <IconApps size={20} stroke={1.75} aria-hidden />
        </span>
        <div className="min-w-0">
          <p className="text-sm font-semibold wrap-anywhere">{clientId}</p>
          <p className="text-xs text-default-500">wants to access your account</p>
        </div>
      </div>

      {handle ? (
        <div className="flex items-start gap-3 border-t border-default-200 pt-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-default-100 text-default-500">
            <IconUserCircle size={20} stroke={1.75} aria-hidden />
          </span>
          <p className="min-w-0 text-sm font-semibold wrap-anywhere">{handle}</p>
        </div>
      ) : null}
    </section>
  );
}
