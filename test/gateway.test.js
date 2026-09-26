import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot, snowflake } from './support/discord.js';
import { runGatewaySession, pickActivity, presencePayload, fillPresenceText, IDENTIFY_CAP } from '../supabase/functions/hopkostki-bot/lib/gateway.js';
import { createHandler } from '../supabase/functions/hopkostki-bot/lib/app.js';

// Atrapa gatewaya Discorda: skrypt `server` reaguje na to, co wysyła bot.
function fakeGateway(server) {
  const sockets = [];
  class FakeSocket {
    constructor(url) {
      this.url = url;
      this.sent = [];
      this.readyState = 1;
      this.seq = 0;
      sockets.push(this);
      setTimeout(() => server.open?.(this), 0);
    }
    send(raw) {
      const packet = JSON.parse(raw);
      this.sent.push(packet);
      if (packet.op === 1) this.receive({ op: 11 });
      setTimeout(() => server.onPacket?.(this, packet), 0);
    }
    close(code) {
      if (this.readyState === 3) return;
      this.readyState = 3;
      this.closedWith = code;
      setTimeout(() => this.onclose?.({ code }), 0);
    }
    receive(packet) {
      if (this.readyState !== 1) return;
      this.onmessage?.({ data: JSON.stringify(packet) });
    }
    dispatch(t, d) {
      this.seq += 1;
      this.receive({ op: 0, t, s: this.seq, d });
    }
    serverClose(code) {
      this.readyState = 3;
      this.onclose?.({ code });
    }
  }
  return { FakeSocket, sockets };
}

const hello = (ws, interval = 40) => ws.receive({ op: 10, d: { heartbeat_interval: interval } });

async function setup() {
  const { store } = await createTestStore();
  const discord = fakeDiscord();
  const bot = makeBot({ store, discord });
  return { store, discord, bot };
}

test('opisy statusu: rotacja z zegara i zmienne', () => {
  const presence = {
    enabled: true,
    status: 'online',
    rotateSeconds: 30,
    activities: [
      { type: 'custom', text: '🫓 Pilnuję porządku' },
      { type: 'watching', text: '{czlonkowie} Entuzjastów' },
    ],
  };
  assert.equal(pickActivity(presence, 0).activity.type, 'custom');
  assert.equal(pickActivity(presence, 30_000).activity.type, 'watching');
  assert.equal(pickActivity(presence, 60_000).nextAt, 90_000);
  assert.deepEqual(presencePayload(presence, presence.activities[0], {}), {
    since: null,
    activities: [{ type: 4, name: 'Custom Status', state: '🫓 Pilnuję porządku' }],
    status: 'online',
    afk: false,
  });
  assert.deepEqual(presencePayload(presence, presence.activities[1], { czlonkowie: 1337 }).activities, [{ type: 3, name: '1337 Entuzjastów' }]);
  assert.equal(fillPresenceText('{nieznana} x', {}), '{nieznana} x');
  assert.deepEqual(presencePayload({ ...presence, enabled: false }, null, {}), { since: null, activities: [], status: 'online', afk: false });
});

test('pierwsza sesja: IDENTIFY ze statusem, heartbeat, 🫓 od razu pod odpowiedzią i zapis sesji do wznowienia', async () => {
  const s = await setup();
  const modMessage = snowflake();
  await s.store.addModMessage(modMessage, 'chan', 1);
  const reply = snowflake();
  const gw = fakeGateway({
    open: (ws) => hello(ws),
    onPacket(ws, packet) {
      if (packet.op !== 2) return;
      ws.dispatch('READY', { session_id: 'sess-1', resume_gateway_url: 'wss://resume.example' });
      ws.dispatch('MESSAGE_CREATE', { id: reply, channel_id: 'chan', guild_id: 'g1', author: { id: 'u1' }, message_reference: { message_id: modMessage } });
      ws.dispatch('MESSAGE_CREATE', { id: snowflake(), channel_id: 'chan', guild_id: 'g1', author: { id: 'b', bot: true }, message_reference: { message_id: modMessage } });
    },
  });

  const status = await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 400 });
  const [ws] = gw.sockets;
  assert.equal(gw.sockets.length, 1);
  assert.equal(ws.url, 'wss://gateway.example/?v=10&encoding=json');
  const identify = ws.sent.find((p) => p.op === 2);
  assert.equal(identify.d.token, 'x');
  assert.equal(identify.d.intents, 1 << 9);
  assert.equal(identify.d.presence.status, 'online');
  assert.equal(identify.d.presence.activities.length, 1);
  assert.ok(ws.sent.some((p) => p.op === 3), 'po READY wysłano status');
  assert.ok(ws.sent.some((p) => p.op === 1), 'heartbeat');
  assert.equal(ws.closedWith, 4000, 'kod inny niż 1000 zostawia sesję do wznowienia');

  assert.deepEqual(s.discord.state.reactions, [{ channel: 'chan', id: reply, emoji: '🫓' }]);
  assert.equal(status.mode, 'identify');
  assert.equal(status.reactions, 1);
  assert.equal(status.error, null);
  const saved = await s.store.getState('gateway_session');
  assert.equal(saved.sessionId, 'sess-1');
  assert.equal(saved.resumeUrl, 'wss://resume.example');
  assert.equal(saved.seq, 3);
  assert.equal((await s.store.getState('gateway_identifies')).length, 1);
  assert.equal((await s.store.getState('gateway_status')).mode, 'identify');
});

test('kolejna sesja wznawia poprzednią (RESUME) bez nowego logowania', async () => {
  const s = await setup();
  await s.store.setState('gateway_session', { sessionId: 'sess-1', resumeUrl: 'wss://resume.example', seq: 7, savedAt: Date.now() });
  const gw = fakeGateway({
    open(ws) {
      ws.seq = 7;
      hello(ws);
    },
    onPacket(ws, packet) {
      if (packet.op === 6) ws.dispatch('RESUMED', {});
    },
  });
  const status = await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 300 });
  const [ws] = gw.sockets;
  assert.equal(ws.url, 'wss://resume.example/?v=10&encoding=json');
  assert.deepEqual(ws.sent.find((p) => p.op === 6).d, { token: 'x', session_id: 'sess-1', seq: 7 });
  assert.ok(!ws.sent.some((p) => p.op === 2));
  assert.ok(ws.sent.some((p) => p.op === 3));
  assert.equal(status.mode, 'resume');
  assert.ok(!s.discord.state.calls.some((c) => c.path === '/gateway/bot'));
  assert.equal((await s.store.getState('gateway_session')).seq, 8);
});

test('nieważna sesja (op 9) = nowe logowanie w tej samej sesji', async () => {
  const s = await setup();
  await s.store.setState('gateway_session', { sessionId: 'stara', resumeUrl: 'wss://resume.example', seq: 7, savedAt: Date.now() });
  const gw = fakeGateway({
    open: (ws) => hello(ws),
    onPacket(ws, packet) {
      if (packet.op === 6) ws.receive({ op: 9, d: false });
      if (packet.op === 2) ws.dispatch('READY', { session_id: 'nowa', resume_gateway_url: 'wss://resume2.example' });
    },
  });
  const status = await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 600, retryDelay: () => 0 });
  assert.equal(gw.sockets.length, 2);
  assert.equal(gw.sockets[1].url, 'wss://gateway.example/?v=10&encoding=json');
  assert.equal(status.mode, 'identify');
  assert.equal((await s.store.getState('gateway_session')).sessionId, 'nowa');
});

test('zły token (4004) zatrzymuje próby i zapisuje błąd', async () => {
  const s = await setup();
  const gw = fakeGateway({
    open: (ws) => hello(ws),
    onPacket(ws, packet) {
      if (packet.op === 2) ws.serverClose(4004);
    },
  });
  const status = await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 600, retryDelay: () => 0 });
  assert.equal(gw.sockets.length, 1);
  assert.match(status.error, /DISCORD_TOKEN/);
  assert.equal(await s.store.getState('gateway_session'), null);
  assert.match((await s.store.getState('gateway_status')).error, /4004/);
});

test('limit logowań na dobę chroni token przed resetem', async () => {
  const s = await setup();
  await s.store.setState('gateway_identifies', Array.from({ length: IDENTIFY_CAP }, () => Date.now()));
  const gw = fakeGateway({});
  const status = await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 300 });
  assert.equal(gw.sockets.length, 0);
  assert.match(status.error, /Wstrzymano logowanie/);

  await s.store.setState('gateway_identifies', []);
  s.discord.state.identifyRemaining = 20;
  const second = await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 300 });
  assert.equal(gw.sockets.length, 0);
  assert.match(second.error, /tylko na 20 logowań/);
});

test('równoległe uruchomienie jest pomijane, a /gateway wymaga sekretu crona', async () => {
  const s = await setup();
  const gw = fakeGateway({ open: (ws) => hello(ws), onPacket: (ws, p) => p.op === 2 && ws.dispatch('READY', { session_id: 'a', resume_gateway_url: 'wss://r' }) });
  const first = runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 300 });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.deepEqual(await runGatewaySession(s.bot, { WebSocketImpl: gw.FakeSocket, durationMs: 300 }), { skipped: 'poprzednia sesja jeszcze trwa' });
  await first;

  const handle = createHandler(s.bot);
  const denied = await handle(new Request('https://x.supabase.co/functions/v1/hopkostki-bot/gateway', { method: 'POST' }));
  assert.equal(denied.status, 401);
});
