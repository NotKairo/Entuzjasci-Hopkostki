import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot, GUILD, ROLES } from './support/discord.js';
import { createHandler } from '../supabase/functions/hopkostki-bot/lib/app.js';
import { handleInteraction } from '../supabase/functions/hopkostki-bot/lib/interactions.js';

// Role z ID jak prawdziwe (krótkie ID z atrapy odpadają w walidacji). Bot ma rolę na pozycji 8.
const GRACZ = '200000000000000001';
const WIDZ = '200000000000000002';
const SZEF = '200000000000000003';
ROLES.push(
  { id: GRACZ, name: 'Gracz', position: 3, permissions: '0', color: 0 },
  { id: WIDZ, name: 'Widz', position: 4, permissions: '0', color: 0 },
  { id: SZEF, name: 'Szef', position: 10, permissions: '0', color: 0 },
);
const CHANNEL = '100000000000000050';

async function setup() {
  const { store } = await createTestStore();
  const discord = fakeDiscord();
  const bot = makeBot({ store, discord });
  const handle = createHandler(bot);
  const call = (path, { method = 'GET', body } = {}) =>
    handle(new Request(`https://x.supabase.co/functions/v1/hopkostki-bot/panel${path}`, {
      method,
      headers: { 'x-panel-password': 'tajne', 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    }));
  return { store, discord, bot, call };
}

const click = (customId, { roles = [], values, message } = {}) => ({
  type: 3,
  id: 'i3',
  application_id: 'app',
  token: `tok-${customId.replace(/\W/g, "-")}`,
  guild_id: GUILD,
  channel_id: CHANNEL,
  member: { user: { id: 'target', username: 'hurownik_og' }, roles, permissions: '0' },
  message,
  data: { custom_id: customId, ...(values ? { values } : {}) },
});

test('wiadomość z przyciskami ról: wysyłka z panelu, kliknięcie dodaje i zdejmuje rolę', async () => {
  const s = await setup();
  const res = await s.call('/messages', {
    method: 'POST',
    body: {
      channelId: CHANNEL,
      content: 'Wybierz, co lubisz',
      embed: { enabled: true, title: 'Role', description: 'Kliknij przycisk', color: '#ff0000' },
      roles: { mode: 'buttons', items: [{ roleId: GRACZ, label: 'Gram', style: 'zielony' }, { roleId: WIDZ, label: '' }] },
    },
  });
  assert.equal(res.status, 200);
  const sent = s.discord.state.channels.get(CHANNEL)[0];
  assert.equal(sent.content, 'Wybierz, co lubisz');
  assert.equal(sent.embeds[0].title, 'Role');
  assert.equal(sent.embeds[0].color, 0xff0000);
  assert.deepEqual(sent.components[0].components.map((b) => [b.label, b.style, b.custom_id]), [
    ['Gram', 3, `rr|b|${GRACZ}`],
    ['Widz', 1, `rr|b|${WIDZ}`],
  ]);
  assert.deepEqual(sent.allowed_mentions, { parse: [] });

  const add = await handleInteraction(click(`rr|b|${GRACZ}`), s.bot);
  assert.deepEqual(add.response, { type: 5, data: { flags: 64 } });
  await add.task();
  assert.ok(s.discord.state.calls.some((c) => c.method === 'PUT' && c.path === `/guilds/g1/members/target/roles/${GRACZ}`));
  assert.match(s.discord.state.webhook.at(-1).body.embeds[0].description, /Dodano/);

  const remove = await handleInteraction(click(`rr|b|${GRACZ}`, { roles: [GRACZ] }), s.bot);
  await remove.task();
  assert.ok(s.discord.state.calls.some((c) => c.method === 'DELETE' && c.path === `/guilds/g1/members/target/roles/${GRACZ}`));

  const { messages } = await (await s.call('/messages')).json();
  assert.equal(messages.length, 1);
  assert.equal(messages[0].data.roles.items[0].roleId, GRACZ);
});

test('lista wyboru ról: dodaje wybrane, zdejmuje odznaczone, nie rusza innych ról', async () => {
  const s = await setup();
  const res = await s.call('/messages', {
    method: 'POST',
    body: { channelId: CHANNEL, content: 'Role', roles: { mode: 'select', placeholder: 'Wybierz', items: [{ roleId: GRACZ }, { roleId: WIDZ, description: 'Oglądam' }] } },
  });
  assert.equal(res.status, 200);
  const message = s.discord.state.channels.get(CHANNEL)[0];
  const select = message.components[0].components[0];
  assert.equal(select.type, 3);
  assert.equal(select.max_values, 2);
  assert.equal(select.min_values, 0);
  assert.deepEqual(select.options.map((o) => o.label), ['Gracz', 'Widz']);

  const result = await handleInteraction(click('rr|s', { roles: [WIDZ, 'r-member'], values: [GRACZ], message }), s.bot);
  await result.task();
  const roleCalls = s.discord.state.calls.filter((c) => c.path.startsWith('/guilds/g1/members/target/roles/')).map((c) => `${c.method} ${c.path.split('/').pop()}`);
  assert.deepEqual(roleCalls, [`PUT ${GRACZ}`, `DELETE ${WIDZ}`]);
});

test('walidacja: rola nad botem, pusta wiadomość, brak kanału; edycja i usuwanie wysłanej wiadomości', async () => {
  const s = await setup();
  const tooHigh = await s.call('/messages', { method: 'POST', body: { channelId: CHANNEL, content: 'x', roles: { mode: 'buttons', items: [{ roleId: SZEF }] } } });
  assert.equal(tooHigh.status, 400);
  assert.match((await tooHigh.json()).error, /wyżej niż rola bota/);

  const empty = await s.call('/messages', { method: 'POST', body: { channelId: CHANNEL } });
  assert.match((await empty.json()).error, /treść albo embed/);
  const noChannel = await s.call('/messages', { method: 'POST', body: { content: 'x' } });
  assert.match((await noChannel.json()).error, /Wybierz kanał/);

  const { message } = await (await s.call('/messages', { method: 'POST', body: { channelId: CHANNEL, content: 'pierwsza' } })).json();
  const edited = await s.call(`/messages/${message.id}`, { method: 'PUT', body: { channelId: 'inny', content: 'poprawiona' } });
  assert.equal(edited.status, 200);
  assert.equal(s.discord.state.editedMessages.at(-1).body.content, 'poprawiona');
  assert.equal(s.discord.state.editedMessages.at(-1).channel, CHANNEL, 'kanału nie da się zmienić przy edycji');

  assert.equal((await s.call(`/messages/${message.id}`, { method: 'DELETE' })).status, 200);
  assert.equal(s.discord.state.channels.get(CHANNEL).length, 0);
  assert.equal((await s.call(`/messages/${message.id}`, { method: 'DELETE' })).status, 404);
});

test('API panelu: kanały głosowe, kategorie, role do rozdania i brakujące uprawnienia bota', async () => {
  const s = await setup();
  const guild = await (await s.call('/guild')).json();
  assert.deepEqual(guild.voiceChannels.map((c) => c.name), ['Głosowy']);
  assert.deepEqual(guild.categories.map((c) => c.name), ['Moderacja']);
  assert.equal(guild.roles.find((r) => r.id === GRACZ).assignable, true);
  assert.equal(guild.roles.find((r) => r.id === SZEF).assignable, false);
  assert.ok(guild.bot.missingVoice.includes('Przenoszenie członków'));

  await s.store.addTempVoice({ channelId: '300000000000000001', guildId: GUILD, ownerId: 'target' });
  await s.store.setVoiceState('target', '300000000000000001');
  const { channels } = await (await s.call('/voice')).json();
  assert.equal(channels[0].members, 1);
  assert.equal((await s.call('/voice/300000000000000001', { method: 'DELETE' })).status, 200);
  assert.deepEqual(s.discord.state.deletedChannels, ['300000000000000001']);
});
