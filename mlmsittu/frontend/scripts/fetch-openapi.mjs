// Pulls the live API description from a running backend into openapi.json.
//
// Architecture 6.2 generates the TypeScript client from this. Keeping the spec checked in means
// the frontend still builds when the backend is not running, and a diff on this file is a visible
// record of every API change.
//
//   npm run api:sync     fetch + regenerate types
//   npm run api:check    fail if the checked-in spec is stale (see check-api-drift.mjs)

import { writeFileSync } from 'node:fs';

const url = process.env.API_DOCS_URL ?? 'http://localhost:8080/v3/api-docs';

try {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }

  const spec = await response.json();
  // Stable key order, so a regeneration produces a diff of real changes rather than noise.
  writeFileSync('openapi.json', JSON.stringify(spec, null, 2) + '\n');

  const paths = Object.keys(spec.paths ?? {}).length;
  const schemas = Object.keys(spec.components?.schemas ?? {}).length;
  console.log(`Wrote openapi.json — ${paths} paths, ${schemas} schemas`);
} catch (error) {
  console.error(`\nCould not read ${url}`);
  console.error(`  ${error.message}\n`);
  console.error('Start the backend first:');
  console.error('  cd ..\\mlmsittu && .\\gradlew bootRun\n');
  process.exit(1);
}
