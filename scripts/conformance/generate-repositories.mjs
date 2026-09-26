import { writeFile } from 'node:fs/promises';
import { P256Keypair, Secp256k1Keypair } from '@atproto/crypto';
import { MemoryBlockstore, Repo, blocksToCarFile } from '@atproto/repo';

const fixtures = [];
for (const Keypair of [P256Keypair, Secp256k1Keypair]) {
  const keypair = await Keypair.create();
  const did = 'did:web:reference.example.net';
  for (const size of [0, 1, 300]) {
    const storage = new MemoryBlockstore();
    let repo = await Repo.create(storage, did, keypair, Array.from({ length: size }, (_, i) => ({
      action: 'create', collection: 'com.example.record', rkey: String(i),
      record: { $type: 'com.example.record', n: i },
    })));
    // Exercise trees formed by deletion and insertion as well as fresh trees.
    if (size > 1) {
      repo = await repo.applyWrites([
        { action: 'delete', collection: 'com.example.record', rkey: '0' },
        { action: 'update', collection: 'com.example.record', rkey: '1', record: { updated: true } },
        { action: 'create', collection: 'com.example.other', rkey: 'new', record: { text: 'other collection' } },
      ], keypair);
    }
    const paths = [];
    for await (const { collection, rkey, cid } of repo.walkRecords()) {
      paths.push({ collection, rkey, cid: cid.toString() });
    }
    const car = await blocksToCarFile(repo.cid, storage.blocks);
    fixtures.push({ did, didKey: keypair.did(), head: repo.cid.toString(), paths, car: Buffer.from(car).toString('base64url') });
  }
}
await writeFile(process.argv[2], JSON.stringify(fixtures));
