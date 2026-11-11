import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { catalog, languageNames } from '../src/i18n';

// The Compose desktop app (Windows, Linux) reads the same catalog as this one.
describe('Compose desktop strings', () => {
  it('match the Tauri catalog; run npm run strings:compose after editing i18n.ts', () => {
    const exported = JSON.parse(readFileSync(resolve(__dirname, '../../desktop-compose/src/main/resources/strings.json'), 'utf8'));
    expect(exported).toEqual({ languages: Object.keys(languageNames), names: languageNames, messages: catalog });
  });
});
