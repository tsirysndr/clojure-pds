# Offline master-key rotation

The operator CLI re-encrypts stored secrets under a replacement `PDS_MASTER_KEY`
without changing their plaintext. It covers repository signing keys, PLC control
keys, queued control/signing-key replacements, and pending/confirmed TOTP secrets.
It does not change public DID keys, repository heads, record contents, account
status, blobs, passkeys or TOTP recovery codes, and does not submit PLC operations
or emit repository events.

Legacy sessions and refresh tokens are deleted. App passwords are also deleted:
their one-way digests depend on the old master key and cannot be re-encrypted.
Users must sign in again and recreate app passwords. OAuth grants, browser sessions,
primary-password hashes and passkeys use independent credentials and remain valid.
DPoP server nonces change: OAuth clients receive `use_dpop_nonce` and retry with
the returned nonce, without having to authorize a new grant.
This is key maintenance, not complete compromise recovery; use account revocation
and public signing/control-key rotation separately when their credentials were
exposed.

## Preparation and commands

Upgrade every instance and worker to support migration 040. Stop all old-version
writers before first adoption. A serving process registers a fingerprint of the
configured key after verifying existing encrypted secrets, then rejects a
mismatched key on subsequent starts. For an offline database that has not yet
registered its key, use:

```sh
mise exec -- clojure -M:master-key register
mise exec -- clojure -M:master-key status
```

`register` requires the current `PDS_MASTER_KEY`; `status` needs only database
configuration. Neither command exports private material. A new, empty database
is bound to its first registered key. Do not delete the registry to bypass a key
mismatch: use the key matching the database or restore its matching backup.

Before rewrapping:

1. Stop every PDS instance, identity CLI/worker, and other writer. This command is
   offline only; do not use a rolling configuration change.
2. Make and verify a [database backup](BACKUP.md), retaining the current master key
   separately. Ensure sufficient PostgreSQL WAL and disk capacity for one transaction.
3. Generate and retain a new independent base64url-encoded 32-byte key in your
   secret store. Supply it as `PDS_NEW_MASTER_KEY` only to the maintenance command;
   `PDS_MASTER_KEY` must still contain the current key. Do not pass keys as command
   arguments or store them in the repository.
4. Supply the current fingerprint from `status` explicitly:

```sh
mise exec -- clojure -M:master-key rewrap "$EXPECTED_OLD_FINGERPRINT"
```

On success, install the replacement as `PDS_MASTER_KEY` in every instance and
worker, remove `PDS_NEW_MASTER_KEY` from their environment, and restart them.
Check `status` against the returned fingerprint/generation and exercise sign-in,
repository writes and any configured factors. Retain the old key for backups
created before the rotation; it must not remain the running server's key.

The command returns JSON with public fingerprints, generation, counts and state.
Exit codes are 0 for success, 1 for an operational failure, and 64 for invalid
syntax. `--help` requires no dependencies. Unexpected driver failures are sanitized.
Both keys must be valid and different. A previously used key cannot be reused.

## Atomicity, retries and process coordination

Rewrapping holds an exclusive PostgreSQL advisory lock and locks the affected
tables. All five encrypted columns, credential invalidation, current fingerprint
and a public receipt commit in one transaction. Secrets are read in batches of
100; temporary decrypted byte arrays are cleared after use. The transaction still
covers the entire PDS schema and may take substantial time on a large installation.
A malformed secret or any failed update rolls everything back.

Serving processes and identity-rotation CLI commands hold shared maintenance
leases using [PostgreSQL advisory locks](https://www.postgresql.org/docs/18/explicit-locking.html#ADVISORY-LOCKS). An ordinary attempt while they run returns `PdsRunning`; a new server
cannot start during maintenance. The server checks its lease every second and
stops HTTP and workers if the connection/lock is lost. Each serving process owns
one additional dedicated database connection, outside the configured request
pool. It must connect through a session-affine PostgreSQL endpoint; transaction
pooling is incompatible with this session-level lease. See [connection sizing](DATABASE.md).

These leases are a guard against accidental overlap, not permission for online
rewrapping. Stop every writer and confirm shutdown, including older versions,
custom programs or SQL clients that do not participate in the lease protocol.
An administrator can terminate database sessions, and failure detection takes
time. Table locks protect the transaction itself; they cannot change keys already
loaded into an unrelated process.

If the command is interrupted, inspect `status`. Retry the exact original command
with both original key values and the original expected fingerprint. A committed
rotation returns its existing receipt without re-encrypting again or deleting new
sessions. Receipts describe historical completion, so use `status` for the current
key after subsequent rotations. An uncommitted attempt rolls back and can retry.
There is no partial-progress state or reverse-rotation command. Never restore a
pre-rotation database while using a post-rotation key.

Queued PLC operations keep their original operation bytes and public keys. Their
replacement private keys are re-encrypted, so workers resume with the new master
key after restart. Prepared and provisioning accounts are included, as are
inactive accounts; selection does not depend on account availability.

## Verification

Real PostgreSQL tests cover all encrypted columns, unconfirmed PLC provisioning,
queued control/signing rotations, TOTP verification, credential invalidation,
OAuth resource access and refresh, multi-batch traversal, concurrent maintenance,
wrong keys, corrupt ciphertext, late transactional rollback, idempotent receipts
and retired-key rejection. Separate JVMs run the shipped CLI and the normal server;
maintenance is rejected while the server runs, and loss of its lease stops it.
HTTP sign-in, subsequent signed writes and CAR verification exercise the replacement
configuration. Production database failover and operator-managed secret-store
cutover still require deployment-specific verification.
