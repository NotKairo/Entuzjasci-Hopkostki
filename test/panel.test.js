import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot } from './support/discord.js';
import { createHandler } from '../supabase/functions/hopkostki-bot/lib/app.js';
import { createPanelServer } from '../panel/server.js';

const BASE = 'https://x.supabase.co/functions/v1/hopkostki-bot/panel';

async function setup() {
  const { store } = await createTestStore();
  const discord = fakeDiscord();
  const bot = makeBot({ store, discord });
  const handle = createHandler(bot);
  const call = (path, { method = 'GET', body, password = 'tajne' } = {}) =>
    handle(new Request(`${BASE}${path}`, { method, headers: { 'x-panel-password': password, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }));
  return { store, discord, bot, handle, call };
}

test('API panelu wymaga hasła', async () => {
  const s = await setup();
  assert.equal((await s.call('/status', { password: 'zle' })).status, 401);
  assert.equal((await s.call('/status', { password: '' })).status, 401);
});

test('API panelu: status, kanały, role i zapis konfiguracji', async () => {
  const s = await setup();
  const status = await (await s.call('/status')).json();
  assert.equal(status.ready, true);
  assert.equal(status.guild.name, 'Entuzjaści Hopkostki');
  assert.equal(status.guild.memberCount, 1337);
  assert.equal(status.setup.tokenConfigured, true);

  const guild = await (await s.call('/guild')).json();
  assert.deepEqual(guild.channels.map((c) => [c.name, c.category]), [['ogolny', null], ['mod-logi', 'Moderacja']]);
  assert.deepEqual(guild.roles.map((r) => r.name), ['Admin', 'Bot', 'Moderator', 'Entuzjasta']);

  const { config } = await (await s.call('/config', { method: 'PUT', body: { replyReaction: { emoji: '🍞' } } })).json();
  assert.equal(config.replyReaction.emoji, '🍞');
  assert.equal((await s.store.getConfig()).replyReaction.emoji, '🍞');
});

test('API panelu: usuwanie ostrzeżenia i ręczne odbanowanie', async () => {
  const s = await setup();
  const warn = await s.store.addWarn({ guildId: 'g1', userId: 'target', userTag: 'a', moderatorId: 'm', moderatorTag: 'mod', reason: 'r', points: 1, caseId: null }, 60);
  const list = await (await s.call('/warns')).json();
  assert.equal(list.users[0].warns[0].id, warn.id);
  assert.equal((await s.call(`/warns/${warn.id}`, { method: 'DELETE' })).status, 200);
  assert.equal(await s.store.getWarn(warn.id), null);

  s.discord.state.bans.set('target', { user: s.discord.state.users.get('target') });
  await s.store.setTempBan({ guildId: 'g1', userId: 'target', userTag: 'hurownik_og', expiresAt: Date.now() + 1e6, caseId: 1 });
  assert.equal((await s.call('/tempbans/target/unban', { method: 'POST' })).status, 404, 'tylko liczbowe ID');
});

test('lokalny panel przekazuje /api do bota z hasłem i serwuje frontend', async () => {
  const seen = [];
  const upstream = http.createServer((req, res) => {
    seen.push({ method: req.method, url: req.url, password: req.headers['x-panel-password'] });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
  });
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve));
  const botUrl = `http://127.0.0.1:${upstream.address().port}/functions/v1/hopkostki-bot`;
  const panel = createPanelServer({ botUrl, password: 'tajne' });
  await new Promise((resolve) => panel.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${panel.address().port}`;
  try {
    assert.deepEqual(await (await fetch(`${base}/api/cases?page=2`)).json(), { ok: true });
    assert.deepEqual(seen[0], { method: 'GET', url: '/functions/v1/hopkostki-bot/panel/cases?page=2', password: 'tajne' });

    const blocked = await fetch(`${base}/api/config`, { method: 'PUT', body: '{}' });
    assert.equal(blocked.status, 403, 'zapis bez nagłówka panelu');

    const page = await fetch(`${base}/`);
    assert.match(await page.text(), /Panel — Entuzjaści Hopkostki/);
    assert.equal((await fetch(`${base}/..%2F..%2Fpackage.json`)).status, 404);

    const evil = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port: panel.address().port, path: '/', headers: { Host: 'evil.example.com' } }, (res) => resolve(res.statusCode));
    });
    assert.equal(evil, 403);
  } finally {
    panel.close();
    upstream.close();
  }
});
