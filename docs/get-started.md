# Get started

Run clojure-pds on your own machine and make your first request. This is the
short path; [Installation](installation.md) covers the toolchain and packaged
alternatives, and [Deployment](DEPLOYMENT.md) is the checklist for a real
server.

## 1. Install the toolchain

Install the Clojure CLI and [mise](https://mise.jdx.dev/); `mise.toml` pins
the JDK (Temurin `25.0.3+9.0.LTS`):

```sh
mise trust
mise install
```

## 2. Generate the master key

The master key encrypts repository signing keys and derives the session
signing key. Generate it **once** and save it securely — losing or changing
it without a [key migration](MASTER-KEY.md) makes existing private keys
unreadable and invalidates sessions. Back it up separately from the database.

```sh
export PDS_MASTER_KEY="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
```

## 3. Start the server

With no database configured, the server stores everything in a single
[SQLite file](SQLITE.md) at `data/clojure-pds.sqlite3`:

```sh
mise exec -- clojure -M:run
```

For PostgreSQL (recommended beyond a single small host), export credentials
and start the bundled container first; any `PDS_DATABASE_*` variable selects
the PostgreSQL backend:

```sh
export PDS_DATABASE_PASSWORD='choose-a-local-password'
docker compose up -d postgres
mise exec -- clojure -M:run
```

Startup runs checksummed, transactional migrations before binding HTTP.

## 4. Make your first requests

`GET /` returns the ASCII banner. Health is liveness, not federation
readiness:

```sh
curl http://127.0.0.1:3000/xrpc/_health
curl http://127.0.0.1:3000/xrpc/com.atproto.server.describeServer
```

Stop with Ctrl-C. Configuration comes from exported environment variables;
`.env` files are not loaded automatically — see
[the configuration reference](CONFIGURATION.md) and
[.env.example](../.env.example).

## 5. Create an account in the browser

Set `PDS_ENABLE_SIGNUP=true` and visit `/account` to create an account, sign
in, and manage optional passkeys and Google Authenticator-compatible
two-factor authentication. The interface is a React application served under
a strict same-origin CSP; see
[building the interface](ACCOUNT-SECURITY.md#building-the-interface) when
editing it.

## Where next

- [Configuration](CONFIGURATION.md) for hosted handles, did:plc identities,
  invites, email delivery, and rate limits.
- [Development](DEVELOPMENT.md) for the test suites and the REPL.
- [Deployment](DEPLOYMENT.md) before pointing real DNS at it.
