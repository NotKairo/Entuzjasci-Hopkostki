import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot, GUILD } from './support/discord.js';
import { handleInteraction } from '../supabase/functions/hopkostki-bot/lib/interactions.js';
import { createHandler } from '../supabase/functions/hopkostki-bot/lib/app.js';
import { P } from '../supabase/functions/hopkostki-bot/lib/permissions.js';

const SUPPORT = '100000000000000080';
const CATEGORY = '100000000000000081';
const LOGS = '100000000000000082';
const PANEL_CHANNEL = '100000000000000083';

async function setup(tickets = {}) {
  const { store } = await createTestStore();
  const discord = fakeDiscord();
  const bot = makeBot({ store, discord });
  await store.updateConfig({ tickets: { enabled: true, categoryId: CATEGORY, supportRoleIds: [SUPPORT], logChannelId: LOGS, ...tickets } });
  return { store, discord, bot };
}

function click(customId, { user = 'target', roles = [], permissions = '0', type = 3, values, components, message } = {}) {
  return {
    type,
    id: 'i4',
    application_id: 'app',
    token: `tok-${customId.replace(/\W/g, '-')}`,
    guild_id: GUILD,
    channel_id: 'chan',
    member: { user: { id: user, username: user }, roles, permissions },
    message,
    data: { custom_id: customId, ...(values ? { values } : {}), ...(components ? { components } : {}) },
  };
}

async function run(bot, body) {
  const result = await handleInteraction(body, bot);
  await result.task?.();
  return result;
}

const form = (value) => [{ type: 1, components: [{ type: 4, custom_id: 'value', value }] }];

test('panel ticketów: wysyłka na kanał, ponowna wysyłka aktualizuje tę samą wiadomość', async () => {
  const s = await setup({ types: [{ label: 'Pomoc', style: 'zielony', question: '' }, { label: 'Odwołanie', style: 'czerwony', question: 'Od czego się odwołujesz?' }] });
  const handle = createHandler(s.bot);
  const call = (body) =>
    handle(new Request('https://x.supabase.co/functions/v1/hopkostki-bot/panel/tickets/panel', { method: 'POST', headers: { 'x-panel-password': 'tajne' }, body: JSON.stringify(body) }));

  assert.equal((await call({ channelId: PANEL_CHANNEL })).status, 200);
  const panel = s.discord.state.channels.get(PANEL_CHANNEL)[0];
  assert.deepEqual(panel.components[0].components.map((b) => [b.label, b.style, b.custom_id]), [
    ['Pomoc', 3, 'tk|open|0'],
    ['Odwołanie', 4, 'tk|open|1'],
  ]);
  assert.equal((await call({ channelId: PANEL_CHANNEL })).status, 200);
  assert.equal(s.discord.state.channels.get(PANEL_CHANNEL).length, 1, 'bez duplikatu');
  assert.equal(s.discord.state.editedMessages.at(-1).id, panel.id);
});

test('otwarcie ticketu: okienko z pytaniem, prywatny kanał, powitanie z przyciskami, limit otwartych', async () => {
  const s = await setup();
  const modal = await run(s.bot, click('tk|open|0'));
  assert.equal(modal.response.type, 9);
  assert.equal(modal.response.data.components[0].components[0].label, 'Opisz krótko swoją sprawę');

  await run(s.bot, click('tkm|open|0', { type: 5, components: form('Nie działa mi rola') }));
  const [channel] = s.discord.state.createdChannels;
  assert.equal(channel.name, 'ticket-0001');
  assert.equal(channel.type, 0);
  assert.equal(channel.parent_id, CATEGORY);
  const byId = Object.fromEntries(channel.permission_overwrites.map((o) => [o.id, o]));
  assert.equal(BigInt(byId[GUILD].deny) & P.VIEW_CHANNEL, P.VIEW_CHANNEL, 'inni nie widzą ticketu');
  assert.equal(BigInt(byId.target.allow) & P.SEND_MESSAGES, P.SEND_MESSAGES);
  assert.equal(BigInt(byId[SUPPORT].allow) & P.VIEW_CHANNEL, P.VIEW_CHANNEL);

  const welcome = s.discord.state.channels.get(channel.id)[0];
  assert.match(welcome.content, new RegExp(`<@target> <@&${SUPPORT}>`));
  assert.equal(welcome.embeds[0].fields[0].value, 'Nie działa mi rola');
  assert.deepEqual(welcome.components[0].components.map((b) => b.label), ['Zamknij', 'Przejmij', 'Dodaj osobę']);
  assert.match(s.discord.state.webhook.at(-1).body.embeds[0].description, new RegExp(`<#${channel.id}>`));
  assert.equal(s.discord.state.channels.get(LOGS).length, 1, 'log otwarcia');

  await run(s.bot, click('tkm|open|0', { type: 5, components: form('drugi') }));
  assert.match(s.discord.state.webhook.at(-1).body.embeds[0].description, /Masz już otwarty ticket/);
  assert.equal(s.discord.state.createdChannels.length, 1);
});

test('ticket: przejęcie tylko przez obsługę, dodanie osoby, zamknięcie z zapisem do logów i DM', async () => {
  const s = await setup({ types: [{ label: 'Pomoc', style: 'niebieski', question: '' }] });
  await run(s.bot, click('tk|open|0'));
  const channel = s.discord.state.createdChannels[0];
  const ticket = (await s.store.listTickets())[0];
  s.discord.state.channels.get(channel.id).push({ id: '1300000000000000000', content: 'Pomocy!', author: { username: 'target' }, timestamp: new Date().toISOString() });

  const denied = await run(s.bot, click(`tk|claim|${ticket.id}`));
  assert.match(denied.response.data.embeds[0].description, /tylko obsługa/);
  await run(s.bot, click(`tk|claim|${ticket.id}`, { user: 'mod', roles: [SUPPORT], message: { id: s.discord.state.channels.get(channel.id)[0].id } }));
  assert.equal((await s.store.getTicket(ticket.id)).claimedBy, 'mod');

  await run(s.bot, click(`tks|add|${ticket.id}`, { values: ['owner'] }));
  const added = s.discord.state.calls.find((c) => c.method === 'PUT' && c.path === `/channels/${channel.id}/permissions/owner`);
  assert.equal(BigInt(added.body.allow) & P.VIEW_CHANNEL, P.VIEW_CHANNEL);

  const confirm = await run(s.bot, click(`tk|close|${ticket.id}`));
  assert.equal(confirm.response.data.components[0].components[0].custom_id, `tk|confirm|${ticket.id}`);
  await run(s.bot, click(`tk|confirm|${ticket.id}`));

  assert.deepEqual(s.discord.state.deletedChannels, [channel.id]);
  const closed = await s.store.getTicket(ticket.id);
  assert.equal(closed.status, 'closed');
  assert.ok(closed.closedAt);
  const logCall = s.discord.state.calls.findLast((c) => c.method === 'POST' && c.path === `/channels/${LOGS}/messages`);
  assert.equal(logCall.files[0].name, 'ticket-0001.txt');
  assert.match(logCall.files[0].content, /target: Pomocy!/);
  assert.equal(s.discord.state.dms.at(-1).embeds[0].title, 'Twój ticket #0001 został zamknięty');

  const again = await run(s.bot, click(`tk|close|${ticket.id}`));
  assert.match(again.response.data.embeds[0].description, /już zamknięty/);
});

test('ticket, którego kanał usunięto ręcznie, nie blokuje nowego; wyłączone tickety nic nie tworzą', async () => {
  const s = await setup({ types: [{ label: 'Pomoc', style: 'niebieski', question: '' }] });
  await run(s.bot, click('tk|open|0'));
  s.discord.state.deletedChannels.push(s.discord.state.createdChannels[0].id);
  await run(s.bot, click('tk|open|0'));
  assert.equal(s.discord.state.createdChannels.length, 2);

  await s.store.updateConfig({ tickets: { enabled: false } });
  const off = await run(s.bot, click('tk|open|0', { user: 'owner' }));
  assert.match(off.response.data.embeds[0].description, /wyłączone/);
});
