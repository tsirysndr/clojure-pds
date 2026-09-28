import { IconShieldCheck } from "@tabler/icons-react";
import type { PermissionSet } from "../api";

export function PermissionList({
  permissions,
  permissionSets,
}: {
  permissions: string[];
  permissionSets: PermissionSet[];
}) {
  const entries = permissions.filter(Boolean);

  return (
    <section aria-label="Requested permissions" className="flex flex-col gap-3">
      {entries.length > 0 ? (
        <ul className="flex flex-col gap-2">
          {entries.map((entry) => (
            <li key={entry} className="flex items-start gap-2 text-sm">
              <IconShieldCheck size={18} stroke={1.75} className="mt-0.5 shrink-0 text-brand" aria-hidden />
              <span className="wrap-anywhere">{entry}</span>
            </li>
          ))}
        </ul>
      ) : null}

      {permissionSets.map((set) => (
        <details
          key={set.nsid ?? set.title ?? set.permissions.join(" ")}
          className="rounded-xl border border-default-200 bg-default-50/60 p-3"
        >
          <summary className="cursor-pointer text-sm font-medium">
            {set.title ?? set.nsid ?? "Permission set"}
          </summary>
          <ul className="mt-2 flex flex-col gap-1.5 border-t border-default-200 pt-2">
            {set.permissions.map((entry) => (
              <li key={entry} className="flex items-start gap-2 text-sm text-default-600">
                <IconShieldCheck size={16} stroke={1.75} className="mt-0.5 shrink-0 text-brand" aria-hidden />
                <span className="wrap-anywhere">{entry}</span>
              </li>
            ))}
          </ul>
        </details>
      ))}

      {entries.length === 0 && permissionSets.length === 0 ? (
        <p className="text-sm text-default-500">
          This application only confirms your account identity.
        </p>
      ) : null}
    </section>
  );
}
