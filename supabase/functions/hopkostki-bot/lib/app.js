// Router funkcji Edge "hopkostki-bot" (Web Request -> Response, działa w Deno i w Node):
//   POST /            interakcje Discorda (podpis Ed25519)
//   POST /cron        zadania okresowe (sekret z bazy, wywołuje pg_cron)
//   POST /gateway     sesja gateway w tle (status online + opisy), też z pg_cron
//   GET  /panel/      strona konfiguracyjna (hasło wpisuje się w przeglądarce)
//   *    /panel/...   API panelu (nagłówek x-panel-password)
//   GET  /health      szybka diagnostyka bez sekretów

import { handleInteraction } from './interactions.js';
import { runCron } from './cron.js';
import { runGatewaySession } from './gateway.js';
import { handlePanel } from './panel.js';
import { getApp } from './moderation.js';
import { verifyDiscordRequest, ed25519Supported, safeEqual } from './verify.js';
import { PANEL_INDEX_HTML, PANEL_APP_JS, PANEL_STYLE_CSS } from './panelAssets.js';

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json; charset=utf-8' } });
const staticFile = (body, contentType) => new Response(body, { status: 200, headers: { 'Content-Type': contentType, 'Cache-Control': 'no-store' } });

function randomPassword() {
  const bytes = new Uint8Array(18);
  crypto.getRandomValues(bytes);
  let text = '';
  for (const b of bytes) text += String.fromCharCode(b);
  return btoa(text).replace(/[+/=]/g, (c) => ({ '+': '-', '/': '_', '=': '' })[c]);
}

// Hasło panelu: albo na stałe z sekretu PANEL_PASSWORD, albo (gdy nie ustawiono) wygenerowane samo przy
// pierwszym użyciu i zapamiętane w bazie — dzięki temu panel działa "od ręki", bez ustawiania sekretów.
async function resolvePanelPassword(bot) {
  if (bot.env.panelPassword) return { password: bot.env.panelPassword, fixed: true };
  let stored = await bot.store.getState('panel_password');
  if (!stored) {
    stored = randomPassword();
    await bot.store.setState('panel_password', stored);
  }
  return { password: stored, fixed: false };
}

async function publicKey(bot) {
  if (bot.env.publicKey) return bot.env.publicKey;
  const hit = bot.cache.get('verifyKey');
  if (hit) return hit;
  let key = (await bot.store.getState('app'))?.verifyKey;
  if (!key) {
    const app = await getApp(bot);
    key = app.verify_key;
    await bot.store.setState('app', { id: app.id, verifyKey: key, name: app.name });
  }
  bot.cache.set('verifyKey', key);
  return key;
}

export function createHandler(bot, { waitUntil = (promise) => promise } = {}) {
  async function discord(request) {
    if (!bot.discord) return json({ error: 'Brak sekretu DISCORD_TOKEN' }, 503);
    const raw = await request.text();
    const valid = await verifyDiscordRequest(
      await publicKey(bot),
      request.headers.get('x-signature-ed25519'),
      request.headers.get('x-signature-timestamp'),
      raw,
    );
    if (!valid) return new Response('invalid request signature', { status: 401 });

    const { response, task } = await handleInteraction(JSON.parse(raw), bot);
    if (task) waitUntil(task());
    return json(response);
  }

  async function cronAllowed(request) {
    const secret = await bot.store.getState('cron_secret');
    return Boolean(secret) && (await safeEqual(request.headers.get('x-cron-secret'), secret));
  }

  async function cron(request, url) {
    if (!(await cronAllowed(request))) return json({ error: 'Brak dostępu' }, 401);
    if (!bot.discord) return json({ skipped: 'Brak sekretu DISCORD_TOKEN' });
    return json(await runCron(bot, { force: url.searchParams.get('force') === '1' }));
  }

  // Odpowiadamy od razu, a połączenie z gateway trwa w tle (~55 s).
  async function gateway(request) {
    if (!(await cronAllowed(request))) return json({ error: 'Brak dostępu' }, 401);
    if (!bot.discord) return json({ skipped: 'Brak sekretu DISCORD_TOKEN' });
    waitUntil(runGatewaySession(bot).catch((error) => console.error('[gateway]', error)));
    return json({ started: true }, 202);
  }

  async function panel(request, url, path) {
    const { password } = await resolvePanelPassword(bot);
    if (!(await safeEqual(request.headers.get('x-panel-password'), password))) {
      return json({ error: 'Złe hasło panelu.' }, 401);
    }
    let body = {};
    if (!['GET', 'HEAD'].includes(request.method)) body = await request.json().catch(() => ({}));
    const result = await handlePanel(bot, {
      method: request.method,
      path: path.slice('/panel'.length) || '/',
      query: Object.fromEntries(url.searchParams),
      body,
    });
    return json(result.body, result.status);
  }

  return async function handle(request) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^.*?\/hopkostki-bot(?=\/|$)/, '') || '/';
    try {
      if (path === '/health') {
        return json({
          ok: true,
          tokenConfigured: Boolean(bot.env.token),
          panelPasswordConfigured: true, // zawsze prawda: sekret albo hasło wygenerowane samo w bazie
          ed25519: await ed25519Supported(),
        });
      }
      if (path === '/cron' && request.method === 'POST') return await cron(request, url);
      if (path === '/gateway' && request.method === 'POST') return await gateway(request);
      // Strona panelu jest publiczna (logowanie hasłem dzieje się w przeglądarce) — bez ukośnika na końcu
      // przekierowujemy, żeby względne ścieżki (app.js, style.css, wywołania API) rozwiązywały się poprawnie
      // niezależnie od tego, pod jakim prefiksem funkcja jest zamontowana.
      if (path === '/panel' && request.method === 'GET') {
        const dest = new URL(request.url);
        dest.pathname = `${url.pathname}/`; // dopisujemy "/" do PRAWDZIWEJ ścieżki, nie tej po ucięciu prefiksu
        return Response.redirect(dest.toString(), 302);
      }
      if (path === '/panel/' && request.method === 'GET') return staticFile(PANEL_INDEX_HTML, 'text/html; charset=utf-8');
      if (path === '/panel/app.js' && request.method === 'GET') return staticFile(PANEL_APP_JS, 'text/javascript; charset=utf-8');
      if (path === '/panel/style.css' && request.method === 'GET') return staticFile(PANEL_STYLE_CSS, 'text/css; charset=utf-8');
      if (path.startsWith('/panel/')) return await panel(request, url, path);
      if (request.method === 'POST' && (path === '/' || path === '/interactions')) return await discord(request);
      return json({ error: 'Nie znaleziono' }, 404);
    } catch (error) {
      console.error('[handler]', error);
      return json({ error: 'Błąd serwera' }, 500);
    }
  };
}
