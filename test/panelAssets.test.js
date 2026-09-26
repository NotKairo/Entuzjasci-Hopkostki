// Sprawdza, że supabase/functions/hopkostki-bot/lib/panelAssets.js (wersja hostowana przez funkcję Edge)
// jest wygenerowany z aktualnych plików panel/public/* — czyli że ktoś uruchomił `npm run build:panel`
// po ostatniej zmianie panelu. Jeśli ten test nie przechodzi: `npm run build:panel` i zacommituj wynik.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PANEL_INDEX_HTML, PANEL_APP_JS, PANEL_STYLE_CSS } from '../supabase/functions/hopkostki-bot/lib/panelAssets.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(ROOT, '..', 'panel', 'public');

test('panelAssets.js jest aktualny względem panel/public/*', () => {
  assert.equal(PANEL_INDEX_HTML, fs.readFileSync(path.join(PUBLIC_DIR, 'index.html'), 'utf8'));
  assert.equal(PANEL_APP_JS, fs.readFileSync(path.join(PUBLIC_DIR, 'app.js'), 'utf8'));
  assert.equal(PANEL_STYLE_CSS, fs.readFileSync(path.join(PUBLIC_DIR, 'style.css'), 'utf8'));
});
