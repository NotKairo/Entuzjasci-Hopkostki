// Router funkcji Edge "hopkostki-bot" (Web Request -> Response, działa w Deno i w Node):
//   POST /            interakcje Discorda (podpis Ed25519)
//   POST /cron        zadania okresowe (sekret z bazy, wywołuje pg_cron)
//   *    /panel/...   API panelu (nagłówek x-panel-password)
//   GET  /health      szybka diagnostyka bez sekretów

import { handleInteraction } from './interactions.js';
import { runCron } from './cron.js';
import { handlePanel } from './panel.js';
import { getApp } from './moderation.js';
import { verifyDiscordRequest, ed25519Supported, safeEqual } from './verify.js';

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json; charset=utf-8' } });

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

  async function cron(request, url) {
    const secret = await bot.store.getState('cron_secret');
    if (!secret || !(await safeEqual(request.headers.get('x-cron-secret'), secret))) return json({ error: 'Brak dostępu' }, 401);
    if (!bot.discord) return json({ skipped: 'Brak sekretu DISCORD_TOKEN' });
    return json(await runCron(bot, { force: url.searchParams.get('force') === '1' }));
  }

  async function panel(request, url, path) {
    if (!bot.env.panelPassword) return json({ error: 'Ustaw sekret PANEL_PASSWORD w Supabase (Edge Functions → Secrets).' }, 503);
    if (!(await safeEqual(request.headers.get('x-panel-password'), bot.env.panelPassword))) {
      return json({ error: 'Złe hasło panelu (PANEL_PASSWORD).' }, 401);
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
          panelPasswordConfigured: Boolean(bot.env.panelPassword),
          ed25519: await ed25519Supported(),
        });
      }
      if (path === '/cron' && request.method === 'POST') return await cron(request, url);
      if (path === '/panel' || path.startsWith('/panel/')) return await panel(request, url, path);
      if (request.method === 'POST' && (path === '/' || path === '/interactions')) return await discord(request);
      return json({ error: 'Nie znaleziono' }, 404);
    } catch (error) {
      console.error('[handler]', error);
      return json({ error: 'Błąd serwera' }, 500);
    }
  };
}
