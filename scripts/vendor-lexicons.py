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
ROOTS = [
    "app.bsky.actor.profile", "app.bsky.actor.status",
    "app.bsky.feed.post", "app.bsky.feed.like", "app.bsky.feed.repost",
    "app.bsky.feed.generator", "app.bsky.feed.threadgate", "app.bsky.feed.postgate",
    "app.bsky.graph.follow", "app.bsky.graph.block", "app.bsky.graph.list",
    "app.bsky.graph.listitem", "app.bsky.graph.listblock",
    "app.bsky.graph.starterpack", "app.bsky.graph.verification",
    "app.bsky.labeler.service", "chat.bsky.actor.declaration",
]


def fetch(path):
    with urllib.request.urlopen(BASE + path, timeout=30) as response:
        return response.read()


def refs(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "ref":
                yield child
            elif key == "refs":
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
    index = {"revision": REVISION, "roots": ROOTS, "schemas": {
        nsid: hashlib.sha256(data).hexdigest() for nsid, data in sorted(catalog.items())}}
    (DEST / "index.json").write_text(json.dumps(index, indent=2) + "\n")
    for license in ["LICENSE.txt", "LICENSE-MIT.txt", "LICENSE-APACHE.txt"]:
        (DEST / license).write_bytes(fetch(license))
    print(f"Vendored {len(catalog)} schemas at {REVISION}")


if __name__ == "__main__":
    main()
