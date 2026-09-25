import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot, commandPayload } from './support/discord.js';
import { createHandler } from '../supabase/functions/hopkostki-bot/lib/app.js';

const hex = (buf) => Buffer.from(buf).toString('hex');

// Klucze jak u Discorda: prywatnym "Discord" podpisuje, publiczny zna bot.
async function keys() {
  const pair = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  return { pair, publicHex: hex(await crypto.subtle.exportKey('raw', pair.publicKey)) };
}

async function setup(env = {}) {
  const { store } = await createTestStore();
  const discord = fakeDiscord();
  const { pair, publicHex } = await keys();
  discord.state.verifyKey = publicHex;
  const bot = makeBot({ store, discord, env });
  const tasks = [];
  const handle = createHandler(bot, { waitUntil: (p) => tasks.push(p) });

  async function send(body, { badSignature = false } = {}) {
    const raw = JSON.stringify(body);
    const timestamp = String(Math.floor(Date.now() / 1000));
    const signature = hex(await crypto.subtle.sign({ name: 'Ed25519' }, pair.privateKey, new TextEncoder().encode(timestamp + raw)));
    const response = await handle(
      new Request('https://x.supabase.co/functions/v1/hopkostki-bot', {
        method: 'POST',
        headers: { 'x-signature-ed25519': badSignature ? signature.replace(/^./, (c) => (c === 'a' ? 'b' : 'a')) : signature, 'x-signature-timestamp': timestamp },
        body: raw,
      }),
    );
    await Promise.all(tasks.splice(0));
    return response;
  }
  return { bot, store, discord, send, handle };
}

const opt = (name, value, type = 3) => ({ name, type, value });

test('PING z poprawnym podpisem dostaje PONG, zły podpis = 401', async () => {
  const s = await setup();
  const ok = await s.send({ type: 1 });
  assert.equal(ok.status, 200);
  assert.deepEqual(await ok.json(), { type: 1 });
  const bad = await s.send({ type: 1 }, { badSignature: true });
  assert.equal(bad.status, 401);
});

test('/ban: odroczona odpowiedź, a w tle ban + embed z oznaczeniem', async () => {
  const s = await setup();
  const response = await s.send(
    commandPayload('ban', [opt('uzytkownik', 'target', 6), opt('powod', 'Wielokrotne łamanie zasad'), opt('czas', 14, 4), opt('jednostka', 'd')]),
  );
  assert.deepEqual(await response.json(), { type: 5, data: {} });
  assert.ok(s.discord.state.bans.has('target'));
  const edit = s.discord.state.webhook.find((w) => w.op === 'edit');
  assert.equal(edit.body.content, '<@target>');
  assert.match(edit.body.embeds[0].description, /\*\*Czas:\*\* 14 dni/);
  assert.equal((await s.store.listTempBans()).length, 1);
});

test('brak uprawnień = prywatny błąd bez odraczania', async () => {
  const s = await setup();
  const response = await s.send(commandPayload('ban', [opt('uzytkownik', 'target', 6), opt('powod', 'x')], { roles: [], permissions: '0' }));
  const body = await response.json();
  assert.equal(body.type, 4);
  assert.equal(body.data.flags, 64);
  assert.match(body.data.embeds[0].description, /Nie masz uprawnień/);
  assert.equal(s.discord.state.bans.size, 0);
});

test('rola moderatora z panelu daje dostęp mimo braku uprawnień Discorda', async () => {
  const s = await setup();
  await s.store.updateConfig({ modRoleIds: ['123456789012345678'] });
  const response = await s.send(
    commandPayload('warn', [{ name: 'status', type: 1, options: [opt('uzytkownik', 'target', 6)] }], { roles: ['123456789012345678'], permissions: '0' }),
  );
  assert.deepEqual(await response.json(), { type: 5, data: { flags: 64 } });
  const edit = s.discord.state.webhook.find((w) => w.op === 'edit');
  assert.match(edit.body.embeds[0].title, /Ostrzeżenia — hurownik_og/);
});

test('błąd hierarchii: publiczne "myśli..." znika, błąd widzi tylko moderator', async () => {
  const s = await setup();
  const payload = commandPayload('kick', [opt('uzytkownik', 'target', 6), opt('powod', 'x')], {
    members: { target: { roles: ['r-admin'] } },
  });
  const response = await s.send(payload);
  assert.equal((await response.json()).type, 5);
  assert.equal(s.discord.state.kicked.length, 0);
  assert.deepEqual(s.discord.state.webhook.map((w) => w.op), ['delete', 'followup']);
  const followup = s.discord.state.webhook[1].body;
  assert.equal(followup.flags, 64);
  assert.match(followup.embeds[0].description, /równą lub wyższą/);
});

test('/timeout powyżej 28 dni jest odrzucany', async () => {
  const s = await setup();
  await s.send(commandPayload('timeout', [opt('uzytkownik', 'target', 6), opt('czas', 1, 4), opt('jednostka', 'mo'), opt('powod', 'x')]));
  assert.equal(s.discord.state.timeouts.length, 0);
  assert.match(s.discord.state.webhook.at(-1).body.embeds[0].description, /maksymalnie \*\*28 dni\*\*/);
});

test('autouzupełnianie /unban podpowiada zbanowanych', async () => {
  const s = await setup();
  s.discord.state.bans.set('target', { user: { id: '123456789012345678', username: 'hurownik_og' } });
  const response = await s.send({
    ...commandPayload('unban'),
    type: 4,
    data: { name: 'unban', options: [{ name: 'uzytkownik', type: 3, value: 'hur', focused: true }] },
  });
  assert.deepEqual(await response.json(), { type: 8, data: { choices: [{ name: 'hurownik_og (123456789012345678)', value: '123456789012345678' }] } });
});

test('/lock blokuje pisanie dla @everyone', async () => {
  const s = await setup();
  await s.send(commandPayload('lock', [], { roles: ['r-admin'], permissions: String(1n << 3n) }));
  const overwrite = s.discord.state.overwrites.get('chan');
  assert.equal(overwrite.id, 'g1');
  assert.equal(BigInt(overwrite.deny) & (1n << 11n), 1n << 11n);
  await s.send(commandPayload('unlock', [], { roles: ['r-admin'], permissions: String(1n << 3n) }));
  assert.equal(BigInt(s.discord.state.overwrites.get('chan').deny) & (1n << 11n), 0n);
});

test('/health działa bez sekretów i nie ujawnia ich', async () => {
  const s = await setup();
  const response = await s.handle(new Request('https://x.supabase.co/functions/v1/hopkostki-bot/health'));
  assert.deepEqual(await response.json(), { ok: true, tokenConfigured: true, panelPasswordConfigured: true, ed25519: true });
});
