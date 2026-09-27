#!/usr/bin/env python3
"""Whole-database PostgreSQL backup/restore. Connection settings use libpq env."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys

FORMAT = "clojure-pds-postgres-backup"
EMPTY_DATABASE = """
SELECT NOT (
  EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
          WHERE nspname !~ '^pg_' AND nspname NOT IN ('public', 'information_schema'))
  OR EXISTS (SELECT 1 FROM pg_catalog.pg_depend d
             JOIN pg_catalog.pg_namespace n ON d.refobjid = n.oid
             WHERE d.refclassid = 'pg_catalog.pg_namespace'::regclass
               AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema')
  OR EXISTS (SELECT 1 FROM pg_catalog.pg_extension WHERE extname <> 'plpgsql')
);
"""


def tool(name):
    return str(Path(os.environ["PG_BIN"]) / name) if os.environ.get("PG_BIN") else name


def run(name, *args, stdout=subprocess.PIPE):
    env = dict(os.environ, PGAPPNAME="clojure-pds-backup")
    env.setdefault("PGCONNECT_TIMEOUT", "10")
    result = subprocess.run([tool(name), *args], env=env, stdout=stdout,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode:
        # Driver diagnostics may contain connection details or database content.
        raise ValueError(f"{name} failed (exit {result.returncode}); no completed restore/backup was published")
    return result.stdout


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def backup(folder):
    # mkdir is exclusive; never overwrite an existing backup or follow a symlink.
    folder.mkdir(mode=0o700)
    dump = folder / "database.dump"
    with dump.open("xb") as output:
        run("pg_dump", "--format=custom", "--no-password", "--no-owner",
            "--no-acl", "--no-tablespaces", stdout=output)
        output.flush()
        os.fsync(output.fileno())
    # The manifest is the completion marker, published only after a successful dump.
    manifest = {"format": FORMAT, "version": 1,
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "pgDumpVersion": run("pg_dump", "--version").decode().strip(),
                "size": dump.stat().st_size, "sha256": digest(dump)}
    temporary = folder / "manifest.partial"
    with temporary.open("x") as output:
        json.dump(manifest, output, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.rename(folder / "manifest.json")
    directory_fd = os.open(folder, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
    print(f"PostgreSQL backup completed: {folder}")


def verify(folder):
    manifest_path = folder / "manifest.json"
    dump = folder / "database.dump"
    if (folder.is_symlink() or manifest_path.is_symlink() or dump.is_symlink()
            or not manifest_path.is_file() or not dump.is_file()):
        raise ValueError("Backup requires regular manifest.json and database.dump files")
    if manifest_path.stat().st_size > 16384:
        raise ValueError("Invalid backup manifest")
    manifest = json.loads(manifest_path.read_text())
    if (not isinstance(manifest, dict) or manifest.get("format") != FORMAT
            or manifest.get("version") != 1 or type(manifest.get("size")) is not int
            or not isinstance(manifest.get("sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"])):
        raise ValueError("Invalid backup manifest")
    if manifest["size"] != dump.stat().st_size or manifest["sha256"] != digest(dump):
        raise ValueError("Backup checksum or size mismatch")
    with dump.open("rb") as source:
        if source.read(5) != b"PGDMP":
            raise ValueError("Expected a PostgreSQL custom-format archive")
    return dump


def restore(folder):
    dump = verify(folder)
    empty = run("psql", "-X", "--no-password", "-v", "ON_ERROR_STOP=1", "-Atqc", EMPTY_DATABASE)
    if empty.strip() != b"t":
        raise ValueError("Restore requires an empty database; existing user objects were found")
    # PGDATABASE is explicit, avoiding a dump's source database name. Never use
    # --create or --clean: restore cannot drop a database or existing objects.
    run("pg_restore", "--no-password", "--single-transaction", "--exit-on-error",
        "--no-owner", "--no-acl", "--no-tablespaces", "--dbname", os.environ["PGDATABASE"], str(dump.absolute()))
    print("PostgreSQL restore completed; verify the PDS before enabling traffic or workers")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["backup", "verify", "restore"])
    parser.add_argument("folder", type=Path, help="New backup directory, or existing backup to verify/restore")
    args = parser.parse_args()
    os.umask(0o077)
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        if args.action != "verify" and not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{0,62}", os.environ.get("PGDATABASE", "")):
            raise ValueError("Set PGDATABASE to an explicit database name (letters, digits, underscores or hyphens)")
        if args.action == "verify":
            verify(args.folder)
            print("Backup checksum verified (not an authenticity or restore test)")
        else:
            (backup if args.action == "backup" else restore)(args.folder)
        return 0
    except (OSError, ValueError) as error:
        print(f"Backup tool: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        # subprocess.run kills and waits for its child before propagating this.
        print("Backup tool interrupted; verify artifacts before retrying", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
