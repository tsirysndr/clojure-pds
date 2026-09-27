# Getting started

Install the Clojure CLI, [mise](https://mise.jdx.dev/), and PostgreSQL 14+ (or Docker).
The JDK is pinned to Temurin `25.0.3+9.0.LTS` in `mise.toml`.

```sh
mise trust
mise install
export PDS_DATABASE_PASSWORD='choose-a-local-password'
docker compose up -d postgres

# Generate ONCE and save securely; reuse this key across restarts.
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
mise exec -- clojure -M:run
```

The master key encrypts repository signing keys and derives a distinct session
signing key. Back it up separately from PostgreSQL. Losing/changing it without a
key migration makes existing private keys unreadable and invalidates sessions.
Use the [offline master-key rotation command](MASTER-KEY.md) to re-encrypt
stored secrets atomically; it invalidates legacy sessions and app passwords.
See the [backup/restore workflow](BACKUP.md) for checksummed PostgreSQL
archives, coordinated S3 backups, recovery steps and the isolated restore drill.

`GET /` returns the ASCII PDS banner. Health is liveness, not federation readiness:

```sh
curl http://127.0.0.1:3000/xrpc/_health
curl http://127.0.0.1:3000/xrpc/com.atproto.server.describeServer
```

Stop with Ctrl-C. Configuration comes from exported environment variables;
`.env` files are not automatically loaded. See [.env.example](../.env.example)
and [the configuration reference](CONFIGURATION.md).

Visit `/account` to create an account (when signup is enabled), sign in and
manage optional passkeys and Google Authenticator-compatible two-factor
authentication. The Tailwind interface uses the selfhosted/Witchcraft PDS card
style with purple accents and system light/dark themes; its stylesheet is
bundled locally. See the
[UI build instructions](ACCOUNT-SECURITY.md#building-the-interface) when editing it.
