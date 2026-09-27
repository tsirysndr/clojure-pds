# Private preferences and migration

The PDS implements `app.bsky.actor.getPreferences` and
`app.bsky.actor.putPreferences` using PostgreSQL private account storage. These
endpoints support synchronization between devices and transfer to an inactive
migration destination. They require the account's own access token; preferences
never enter public repositories, CAR exports or firehose events.

`getPreferences` returns an ordered `preferences` array. `putPreferences` replaces
the caller's writable preferences atomically. Order, duplicate preference types,
unknown preference types within `app.bsky.*`, and extension fields are preserved.
Known preference types are validated with the pinned Bluesky Lexicons. Unknown
types still require a valid `$type` and AT Protocol data. Other namespaces are
rejected. Storage is limited to 1,000 preferences and 1 MiB of serialized JSON,
including any protected preferences retained during replacement.

## Authentication and personal details

Active primary sessions, app-password sessions and authorized OAuth sessions can
read and update ordinary preferences. Inactive migration accounts can use primary
sessions for both operations. Taken-down accounts can export preferences with a
primary session but cannot update them. Revoked, expired and deleted-account
sessions cannot access preferences.

`app.bsky.actor.defs#personalDetailsPref` is accessible only with a primary-password
session. App passwords, including privileged app passwords, and OAuth grants
cannot read or submit it. A replacement by a restricted caller preserves existing
personal details, even when the caller submits an empty preferences array.

When personal details include a birth date, reads append the derived
`declaredAgePref` flags for ages 13, 16 and 18. These flags are visible to restricted
callers without revealing the birth date. Ages use UTC calendar dates, including
birthday boundaries. Supplied `declaredAgePref` values are ignored when writing;
clients cannot forge the derived flags. A primary-session replacement can clear
personal details, which removes the derived preference too.

## OAuth and alternate AppViews

Preference access requires the corresponding RPC method permission and audience.
With `PDS_APPVIEW_SERVICE` configured, that service reference is the audience even
though these two methods store their values locally, matching the reference PDS
behavior. For example:

```text
atproto rpc:app.bsky.actor.getPreferences?aud=did:web:appview.example.com%23bsky_appview
```

Without an AppView configuration, the local audience is the service DID followed
by `#atproto_pds`, such as `did:web:pds.example.com#atproto_pds`. The broad
`transition:generic` scope also grants ordinary preference access. Neither RPC
permissions nor transitional scopes grant access to personal details.

An explicit `atproto-proxy` header matching the local audience uses local storage.
A different audience routes through the authenticated service proxy instead,
with its normal method/audience checks and replacement service credentials. Local
preferences are not read or modified for these requests. Reads are returned with
`Cache-Control: no-store`.

## Transfer and lifecycle

Export preferences using `getPreferences` on the source PDS and submit that array
to `putPreferences` on the destination. Use primary sessions to include personal
details. The derived age preference in an export is ignored on import and
recomputed on subsequent reads. This transfers only PDS-hosted private state;
external chat services and other applications may have separate transfer APIs.

`com.atproto.server.checkAccountStatus.privateStateValues` counts stored preference
entries, including duplicates and personal details, but excluding derived age
flags. An empty array has a count of zero. Account deletion erases the preference
row while retaining the existing DID/handle tombstone behavior.

Tests cover HTTP round trips, account isolation, validation failure rollback,
duplicate/future preferences, personal-detail filtering and preservation, derived
age flags, inactive transfer counts, taken-down access, OAuth method/audience and
revocation checks, explicit alternate-service proxying, and deletion. Input and
observed output validation include both endpoints in the pinned upstream suite.
Deployed reference-PDS migration remains unverified.

Sources: [migration guide](https://atproto.com/guides/account-migration#preferences),
[pinned reference preference storage](https://github.com/bluesky-social/atproto/tree/7a857989751ae31518509d69ab7194a922064f3d/packages/pds/src/actor-store/preference).
