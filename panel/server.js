// Lokalny panel konfiguracyjny: http://localhost:3000
// Serwuje frontend z ./public i przekazuje /api/* do bota działającego na Supabase
// (dopisując hasło PANEL_PASSWORD z pliku .env). Nie wymaga instalowania zależności.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(HERE, 'public');
export const DEFAULT_BOT_URL = 'https://ucjmbdogtzztrkorqzjq.supabase.co/functions/v1/hopkostki-bot';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]', '::1']);
const MAX_BODY = 256 * 1024;

// Prosty parser .env (bez zależności). Nie nadpisuje zmiennych ustawionych w systemie.
export function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/.exec(line);
    if (!match || line.trim().startsWith('#')) continue;
    const value = match[2].replace(/^(['"])(.*)\1$/, '$2');
    if (process.env[match[1]] === undefined) process.env[match[1]] = value;
  }
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new Error('Za duże żądanie'));
        req.destroy();
      } else chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function serveStatic(res, pathname) {
  const relative = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  const file = path.resolve(PUBLIC_DIR, relative);
  if (!file.startsWith(PUBLIC_DIR + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('Nie znaleziono');
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] ?? 'application/octet-stream' });
  return fs.createReadStream(file).pipe(res);
}

export function createPanelServer({ botUrl = DEFAULT_BOT_URL, password = '', host = '127.0.0.1', fetchImpl = fetch } = {}) {
  async function proxy(req, res, url) {
    if (!password) return sendJson(res, 500, { error: 'Ustaw PANEL_PASSWORD w pliku .env (to samo hasło co w sekretach Supabase).' });
    // Zapisy tylko z naszego frontendu (nagłówek wymusza preflight CORS, którego obce strony nie przejdą).
    if (req.method !== 'GET' && req.headers['x-panel'] !== '1') return sendJson(res, 403, { error: 'Brak nagłówka panelu' });

    const target = `${botUrl.replace(/\/+$/, '')}/panel/${url.pathname.slice('/api/'.length)}${url.search}`;
    try {
      const body = ['GET', 'HEAD'].includes(req.method) ? undefined : await readBody(req);
      const upstream = await fetchImpl(target, {
        method: req.method,
        headers: { 'Content-Type': 'application/json', 'x-panel-password': password },
        body,
      });
      const text = await upstream.text();
      res.writeHead(upstream.status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      return res.end(text || '{}');
    } catch (error) {
      return sendJson(res, 502, { error: `Nie można połączyć się z botem na Supabase: ${error.message}` });
    }
  }

  return http.createServer((req, res) => {
    // Ochrona przed DNS rebinding: przy nasłuchu lokalnym akceptujemy tylko lokalne nagłówki Host.
    const hostname = String(req.headers.host ?? '').replace(/:\d+$/, '').toLowerCase();
    if (LOCAL_HOSTS.has(host) && !LOCAL_HOSTS.has(hostname)) {
      res.writeHead(403);
      return res.end('Niedozwolony host');
    }
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) return proxy(req, res, url);
    if (req.method !== 'GET') {
      res.writeHead(405);
      return res.end();
    }
    return serveStatic(res, url.pathname);
  });
}

// Uruchomienie: npm run panel
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  loadEnv(path.join(HERE, '..', '.env'));
  const port = Number(process.env.PANEL_PORT) || 3000;
  const host = process.env.PANEL_HOST || '127.0.0.1';
  const botUrl = process.env.BOT_URL || DEFAULT_BOT_URL;
  const password = process.env.PANEL_PASSWORD || '';

  if (!password) console.warn('⚠️  Brak PANEL_PASSWORD w pliku .env — panel nie połączy się z botem.');
  if (!LOCAL_HOSTS.has(host)) console.warn('⚠️  Panel nasłuchuje poza localhost — każdy w sieci zobaczy panel z Twoim hasłem!');

  const server = createPanelServer({ botUrl, password, host });
  server.listen(port, host, () => {
    console.log(`🖥️  Panel: http://localhost:${port}`);
    console.log(`🔗 Bot na Supabase: ${botUrl}`);
  });
  server.on('error', (error) => console.error(`❌ Panel nie wystartował (port ${port}): ${error.message}`));
}
