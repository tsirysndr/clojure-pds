#!/usr/bin/env python3
"""Refresh the trusted, offline Lexicon catalog from an explicit upstream pin."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request

REVISION = "7a857989751ae31518509d69ab7194a922064f3d"
BASE = f"https://raw.githubusercontent.com/bluesky-social/atproto/{REVISION}/"
DEST = Path(__file__).resolve().parents[1] / "resources" / "lexicons"
RECORD_ROOTS = [
    "app.bsky.actor.profile", "app.bsky.actor.status",
    "app.bsky.feed.post", "app.bsky.feed.like", "app.bsky.feed.repost",
    "app.bsky.feed.generator", "app.bsky.feed.threadgate", "app.bsky.feed.postgate",
    "app.bsky.graph.follow", "app.bsky.graph.block", "app.bsky.graph.list",
    "app.bsky.graph.listitem", "app.bsky.graph.listblock",
    "app.bsky.graph.starterpack", "app.bsky.graph.verification",
    "app.bsky.labeler.service", "chat.bsky.actor.declaration",
]

ENDPOINT_ROOTS = [
    "app.bsky.actor.getPreferences",
    "app.bsky.actor.putPreferences",
    "com.atproto.admin.deleteAccount",
    "com.atproto.admin.disableAccountInvites",
    "com.atproto.admin.disableInviteCodes",
    "com.atproto.admin.enableAccountInvites",
    "com.atproto.admin.getAccountInfo",
    "com.atproto.admin.getAccountInfos",
    "com.atproto.admin.getInviteCodes",
    "com.atproto.admin.getSubjectStatus",
    "com.atproto.admin.searchAccounts",
    "com.atproto.admin.sendEmail",
    "com.atproto.admin.updateAccountEmail",
    "com.atproto.admin.updateAccountPassword",
    "com.atproto.admin.updateSubjectStatus",
    "com.atproto.identity.getRecommendedDidCredentials",
    "com.atproto.identity.refreshIdentity",
    "com.atproto.identity.requestPlcOperationSignature",
    "com.atproto.identity.resolveDid",
    "com.atproto.identity.resolveHandle",
    "com.atproto.identity.resolveIdentity",
    "com.atproto.identity.signPlcOperation",
    "com.atproto.identity.submitPlcOperation",
    "com.atproto.identity.updateHandle",
    "com.atproto.repo.applyWrites",
    "com.atproto.repo.createRecord",
    "com.atproto.repo.deleteRecord",
    "com.atproto.repo.describeRepo",
    "com.atproto.repo.getRecord",
    "com.atproto.repo.importRepo",
    "com.atproto.repo.listMissingBlobs",
    "com.atproto.repo.listRecords",
    "com.atproto.repo.putRecord",
    "com.atproto.repo.uploadBlob",
    "com.atproto.server.activateAccount",
    "com.atproto.server.checkAccountStatus",
    "com.atproto.server.confirmEmail",
    "com.atproto.server.createAccount",
    "com.atproto.server.createAppPassword",
    "com.atproto.server.createInviteCode",
    "com.atproto.server.createInviteCodes",
    "com.atproto.server.createSession",
    "com.atproto.server.deactivateAccount",
    "com.atproto.server.deleteAccount",
    "com.atproto.server.deleteSession",
    "com.atproto.server.describeServer",
    "com.atproto.server.getAccountInviteCodes",
    "com.atproto.server.getServiceAuth",
    "com.atproto.server.getSession",
    "com.atproto.server.listAppPasswords",
    "com.atproto.server.refreshSession",
    "com.atproto.server.requestAccountDelete",
    "com.atproto.server.requestEmailConfirmation",
    "com.atproto.server.requestEmailUpdate",
    "com.atproto.server.requestPasswordReset",
    "com.atproto.server.reserveSigningKey",
    "com.atproto.server.resetPassword",
    "com.atproto.server.revokeAppPassword",
    "com.atproto.server.updateEmail",
    "com.atproto.sync.getBlob",
    "com.atproto.sync.getBlocks",
    "com.atproto.sync.getLatestCommit",
    "com.atproto.sync.getRecord",
    "com.atproto.sync.getRepo",
    "com.atproto.sync.getRepoStatus",
    "com.atproto.sync.listBlobs",
    "com.atproto.sync.listRepos",
    "com.atproto.sync.subscribeRepos",
]
ROOTS = RECORD_ROOTS + ENDPOINT_ROOTS


def fetch(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as response:
        return response.read()


def refs(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "ref" and isinstance(child, str):
                yield child
            elif key == "refs" and isinstance(child, list) and all(isinstance(x, str) for x in child):
                yield from child
            else:
                yield from refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from refs(child)


def main():
    catalog, pending = {}, set(ROOTS)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        while pending:
            batch = sorted(pending)
            for nsid, data in zip(batch, pool.map(
                    fetch, ["lexicons/" + x.replace(".", "/") + ".json" for x in batch])):
                schema = json.loads(data)
                assert schema["id"] == nsid and schema["lexicon"] == 1
                catalog[nsid] = data
                pending.update(ref.split("#")[0] for ref in refs(schema) if not ref.startswith("#"))
            pending.difference_update(catalog)
    DEST.mkdir(parents=True, exist_ok=True)
    # All downloads succeed before replacing the checked-in catalog.
    for nsid, data in sorted(catalog.items()):
        (DEST / (nsid + ".json")).write_bytes(data)
    index = {"revision": REVISION, "roots": ROOTS, "endpointRoots": ENDPOINT_ROOTS, "schemas": {
        nsid: hashlib.sha256(data).hexdigest() for nsid, data in sorted(catalog.items())}}
    (DEST / "index.json").write_text(json.dumps(index, indent=2) + "\n")
    for license in ["LICENSE.txt", "LICENSE-MIT.txt", "LICENSE-APACHE.txt"]:
        (DEST / license).write_bytes(fetch(license))
    print(f"Vendored {len(catalog)} schemas at {REVISION}")


if __name__ == "__main__":
    main()
