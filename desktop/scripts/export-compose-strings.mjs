// Writes the Compose desktop app's strings from the Tauri catalog (src/i18n.ts),
// so both desktop apps show the same text. tests/compose-strings.test.ts fails
// when the two drift apart; run `npm run strings:compose` after editing i18n.ts.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(fileURLToPath(new URL('../src/i18n.ts', import.meta.url)), 'utf8');
const module = await import('data:text/javascript,' + encodeURIComponent(stripTypeScriptTypes(source)));
export const composeStrings = { languages: Object.keys(module.languageNames), names: module.languageNames, messages: module.catalog };
writeFileSync(fileURLToPath(new URL('../../desktop-compose/src/main/resources/strings.json', import.meta.url)), JSON.stringify(composeStrings, null, 1) + '\n');
