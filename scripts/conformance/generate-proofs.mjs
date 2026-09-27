import { writeFile } from 'node:fs/promises';
import { P256Keypair, Secp256k1Keypair } from '@atproto/crypto';
import { MemoryBlockstore, Repo, getRecords } from '@atproto/repo';

const fixtures = [];
for (const Keypair of [P256Keypair, Secp256k1Keypair]) {
  const keypair = await Keypair.create();
  const did = 'did:web:reference.example.net';
  const storage = new MemoryBlockstore();
  const repo = await Repo.create(storage, did, keypair, Array.from({ length: 300 }, (_, i) => ({
    action: 'create', collection: 'com.example.record', rkey: String(i),
    record: { $type: 'com.example.record', n: i },
  })));
  for (const rkey of ['0', '1', '33', '299', 'absent']) {
    const chunks = [];
    for await (const chunk of getRecords(storage, repo.cid, [{ collection: 'com.example.record', rkey }])) chunks.push(chunk);
    fixtures.push({ did, didKey: keypair.did(), head: repo.cid.toString(), rkey,
      record: await repo.getRecord('com.example.record', rkey), car: Buffer.concat(chunks).toString('base64url') });
  }
}
await writeFile(process.argv[2], JSON.stringify(fixtures));
