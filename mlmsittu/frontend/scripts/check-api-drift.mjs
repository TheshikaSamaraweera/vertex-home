// Fails when the checked-in openapi.json no longer matches the running backend.
//
// This is the drift check architecture 6.2 asks CI to enforce. Without it, a Java DTO can gain or
// lose a field and the TypeScript types keep describing the old shape — the compiler stays happy
// and the mistake surfaces at runtime, in a browser, in front of someone.
//
//   npm run api:check
//
// Wire it into CI in Phase 8 (P8-05).

import { readFileSync } from 'node:fs';

const url = process.env.API_DOCS_URL ?? 'http://localhost:8080/v3/api-docs';

const response = await fetch(url).catch((error) => {
  console.error(`Could not read ${url}: ${error.message}`);
  process.exit(1);
});

if (!response.ok) {
  console.error(`Could not read ${url}: ${response.status}`);
  process.exit(1);
}

const live = await response.json();
const checkedIn = JSON.parse(readFileSync('openapi.json', 'utf8'));

const livePaths = new Set(Object.keys(live.paths ?? {}));
const checkedInPaths = new Set(Object.keys(checkedIn.paths ?? {}));

const added = [...livePaths].filter((path) => !checkedInPaths.has(path));
const removed = [...checkedInPaths].filter((path) => !livePaths.has(path));

// Compare the whole document, not only the path list: a field added to a request body changes
// nothing about which paths exist but breaks the generated types just as thoroughly.
const identical = JSON.stringify(live) === JSON.stringify(checkedIn);

if (identical) {
  console.log(`API in sync — ${livePaths.size} paths`);
  process.exit(0);
}

console.error('\nAPI DRIFT: the backend no longer matches the checked-in openapi.json.\n');
added.forEach((path) => console.error(`  + ${path}`));
removed.forEach((path) => console.error(`  - ${path}`));
if (added.length === 0 && removed.length === 0) {
  console.error('  (same paths, but a request or response shape changed)');
}
console.error('\nRegenerate and commit:\n  npm run api:sync\n');
process.exit(1);
