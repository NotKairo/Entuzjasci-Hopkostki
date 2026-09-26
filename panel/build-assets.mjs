// Generuje supabase/functions/hopkostki-bot/lib/panelAssets.js z plików w panel/public/*,
// żeby funkcja Edge mogła hostować cały panel sama (bez osobnego serwera).
// Uruchom po każdej zmianie w panel/public/: `npm run build:panel`.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(HERE, '..', 'supabase', 'functions', 'hopkostki-bot', 'lib', 'panelAssets.js');

const html = fs.readFileSync(path.join(HERE, 'public', 'index.html'), 'utf8');
const js = fs.readFileSync(path.join(HERE, 'public', 'app.js'), 'utf8');
const css = fs.readFileSync(path.join(HERE, 'public', 'style.css'), 'utf8');

const out =
  `// WYGENEROWANE z panel/public/* przez \`npm run build:panel\` (panel/build-assets.mjs) — nie edytuj ręcznie.\n` +
  `// Pozwala funkcji Edge hostować cały panel pod adresem …/hopkostki-bot/panel/, bez osobnego serwera.\n\n` +
  `export const PANEL_INDEX_HTML = ${JSON.stringify(html)};\n\n` +
  `export const PANEL_APP_JS = ${JSON.stringify(js)};\n\n` +
  `export const PANEL_STYLE_CSS = ${JSON.stringify(css)};\n`;

fs.writeFileSync(OUT, out);
console.log(`✅ Zapisano ${path.relative(process.cwd(), OUT)} (${(out.length / 1024).toFixed(1)} KB)`);
