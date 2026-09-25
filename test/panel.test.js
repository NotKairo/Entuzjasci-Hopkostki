require('./helpers');
const test = require('node:test');
const assert = require('node:assert/strict');
const { Collection, ChannelType } = require('discord.js');
const { createApp } = require('../src/panel/server');
const { getStore } = require('../src/lib/db');

function fakeClient() {
  const channels = new Collection([
    ['200000000000000001', { id: '200000000000000001', name: 'logi', type: ChannelType.GuildText, rawPosition: 1, parent: { name: 'Moderacja' } }],
    ['200000000000000002', { id: '200000000000000002', name: 'glosowy', type: ChannelType.GuildVoice, rawPosition: 2 }],
  ]);
  const roles = new Collection([
    ['g1', { id: 'g1', name: '@everyone', position: 0, managed: false, hexColor: '#000000' }],
    ['300000000000000001', { id: '300000000000000001', name: 'Moderator', position: 5, managed: false, hexColor: '#ff0000' }],
  ]);
  const guild = { id: 'g1', name: 'Entuzjaści Hopkostki', memberCount: 123, iconURL: () => null, channels: { cache: channels }, roles: { cache: roles } };
  return {
    isReady: () => true,
    user: { tag: 'Hopkostki#0001', id: 'bot', displayAvatarURL: () => 'x' },
    ws: { ping: 42 },
    uptime: 1000,
    guilds: { cache: new Collection([['g1', guild]]) },
    users: { fetch: async () => null },
  };
}

async function withServer(options, fn) {
  const server = createApp(fakeClient(), options).listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await fn(base);
  } finally {
    server.close();
  }
}

const json = { 'Content-Type': 'application/json', 'X-Panel': '1' };

test('panel bez hasła: status, kanały, role i zapis konfiguracji', async () => {
  await withServer({}, async (base) => {
    const status = await (await fetch(`${base}/api/status`)).json();
    assert.equal(status.guild.name, 'Entuzjaści Hopkostki');
    assert.equal(status.ping, 42);

    const guild = await (await fetch(`${base}/api/guild`)).json();
    assert.deepEqual(guild.channels.map((c) => c.name), ['logi']);
    assert.deepEqual(guild.roles.map((r) => r.name), ['Moderator']);

    const res = await fetch(`${base}/api/config`, {
      method: 'PUT',
      headers: json,
      body: JSON.stringify({ modLogChannelId: '200000000000000001', replyReaction: { emoji: '🍞' } }),
    });
    const { config } = await res.json();
    assert.equal(config.modLogChannelId, '200000000000000001');
    assert.equal(getStore().config.replyReaction.emoji, '🍞');

    const page = await fetch(`${base}/`);
    assert.match(await page.text(), /Panel — Entuzjaści Hopkostki/);
  });
});

test('zapis bez nagłówka panelu jest odrzucany (ochrona przed obcymi stronami)', async () => {
  await withServer({}, async (base) => {
    const res = await fetch(`${base}/api/config`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    assert.equal(res.status, 403);
  });
});

test('obcy nagłówek Host jest blokowany przy nasłuchu lokalnym', async () => {
  const http = require('node:http');
  await withServer({}, async (base) => {
    const { port } = new URL(base);
    const status = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port, path: '/api/status', headers: { Host: 'evil.example.com' } }, (res) => resolve(res.statusCode));
    });
    assert.equal(status, 403);
  });
});

test('panel z hasłem wymaga logowania', async () => {
  await withServer({ password: 'tajne' }, async (base) => {
    assert.equal((await fetch(`${base}/api/status`)).status, 401);
    assert.deepEqual(await (await fetch(`${base}/api/auth`)).json(), { required: true, loggedIn: false });

    const bad = await fetch(`${base}/api/login`, { method: 'POST', headers: json, body: JSON.stringify({ password: 'zle' }) });
    assert.equal(bad.status, 401);

    const good = await fetch(`${base}/api/login`, { method: 'POST', headers: json, body: JSON.stringify({ password: 'tajne' }) });
    const cookie = good.headers.get('set-cookie').split(';')[0];
    const status = await fetch(`${base}/api/status`, { headers: { cookie } });
    assert.equal(status.status, 200);
  });
});

test('usuwanie ostrzeżenia z panelu', async () => {
  const store = getStore();
  const warn = store.addWarn({ guildId: 'g1', userId: '1', userTag: 'a', moderatorId: 'm', moderatorTag: 'mod', reason: 'r', points: 1, caseId: 1 });
  await withServer({}, async (base) => {
    const list = await (await fetch(`${base}/api/warns`)).json();
    assert.equal(list.users[0].warns[0].id, warn.id);
    const del = await fetch(`${base}/api/warns/${warn.id}`, { method: 'DELETE', headers: json });
    assert.equal(del.status, 200);
    assert.equal(store.getWarn(warn.id), null);
  });
});
