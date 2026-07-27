// Fetches third-party browser bundles too large/volatile to commit into the repo — runs before
// dev/build (see package.json) and is a no-op once the file already exists locally.
import { mkdir, writeFile, access } from 'node:fs/promises';
import { dirname } from 'node:path';

const FILES = [
  {
    url: 'https://aladin.cds.unistra.fr/AladinLite/api/v3/latest/aladin.js',
    dest: new URL('../public/vendor/aladin/aladin.js', import.meta.url),
  },
];

for (const { url, dest } of FILES) {
  const path = dest.pathname;
  try {
    await access(path);
    console.log(`[fetch-vendor] ${path} already present, skipping`);
    continue;
  } catch {
    // not present — fetch it below
  }

  console.log(`[fetch-vendor] fetching ${url}`);
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`[fetch-vendor] failed to fetch ${url}: ${res.status} ${res.statusText}`);
  }
  const body = Buffer.from(await res.arrayBuffer());

  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, body);
  console.log(`[fetch-vendor] wrote ${path} (${body.length} bytes)`);
}
