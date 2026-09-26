import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot, GUILD, snowflake } from './support/discord.js';
import {
  onMessageCreateLog,
  onMessageUpdate,
  onMessageDelete,
  onMessageDeleteBulk,
  onAuditLogEntry,
  permissionDiff,
} from '../supabase/functions/hopkostki-bot/lib/logs.js';
import { onVoiceStateUpdate } from '../supabase/functions/hopkostki-bot/lib/voice.js';
import { onMemberJoin } from '../supabase/functions/hopkostki-bot/lib/members.js';
import { sessionIntents } from '../supabase/functions/hopkostki-bot/lib/gateway.js';
import { handleInteraction } from '../supabase/functions/hopkostki-bot/lib/interactions.js';
import { commandPayload } from './support/discord.js';

const MAIN = '100000000000000050';
const MSG_LOGS = '100000000000000051';
const TALK = '100000000000000052';
const QUIET = '100000000000000053';

async function setup(logs = {}, extra = {}) {
  const { store } = await createTestStore();
  const discord = fakeDiscord();
  const bot = makeBot({ store, discord });
  await store.updateConfig({ logs: { enabled: true, channelId: MAIN, messagesChannelId: MSG_LOGS, ignoredChannelIds: [QUIET], ...logs }, ...extra });
  return { store, discord, bot };
}

const logged = (s, channel = MAIN) => s.discord.state.channels.get(channel) ?? [];
const message = (content, extra = {}) => ({
  id: snowflake(),
  type: 0,
  guild_id: GUILD,
  channel_id: TALK,
  author: { id: 'target', username: 'hurownik_og' },
  content,
  attachments: [],
  timestamp: new Date().toISOString(),
  ...extra,
});

test('logi wiadomości: edycja z treścią przed/po, usunięcie z treścią, zbiorcze z plikiem; boty i wyciszone kanały pomijane', async () => {
  const s = await setup();
  const first = message('Cześć wszystkim');
  const second = message('druga', { attachments: [{ filename: 'kot.png', url: 'https://cdn/kot.png' }] });
  const third = message('trzecia');
  for (const m of [first, second, third]) await onMessageCreateLog(s.bot, m);
  await onMessageCreateLog(s.bot, message('bot', { author: { id: 'bot', bot: true } }));
  await onMessageCreateLog(s.bot, message('cicho', { channel_id: QUIET }));

  await onMessageUpdate(s.bot, { ...first, content: 'Cześć wszystkim!', edited_timestamp: new Date().toISOString() });
  await onMessageUpdate(s.bot, { ...first, content: 'Cześć wszystkim!', edited_timestamp: new Date().toISOString() });
  const [edit] = logged(s, MSG_LOGS);
  assert.match(edit.embeds[0].description, /edytowana na <#\d+>\*\* \[Przejdź do wiadomości\]/);
  assert.deepEqual(edit.embeds[0].fields.map((f) => f.value), ['Cześć wszystkim', 'Cześć wszystkim!']);
  assert.equal(logged(s, MSG_LOGS).length, 1, 'bez zmiany treści (np. podgląd linku) nie ma logu');

  await onMessageDelete(s.bot, { id: second.id, channel_id: TALK, guild_id: GUILD });
  const del = logged(s, MSG_LOGS)[1].embeds[0];
  assert.match(del.description, /Wiadomość od <@target> usunięta na <#\d+>\*\*\ndruga\n\n\*\*Załączniki:\*\*\n\[kot\.png\]/);
  assert.equal(del.author.name, 'hurownik_og');

  await onMessageDelete(s.bot, { id: '999999999999999999', channel_id: TALK, guild_id: GUILD });
  assert.equal(logged(s, MSG_LOGS).length, 2, 'nieznanej wiadomości nie logujemy');

  await onMessageDeleteBulk(s.bot, { ids: [first.id, third.id, '1'], channel_id: TALK, guild_id: GUILD });
  const bulkCall = s.discord.state.calls.findLast((c) => c.method === 'POST' && c.path === `/channels/${MSG_LOGS}/messages`);
  assert.match(bulkCall.body.embeds[0].description, /Usunięto zbiorczo 3 wiadomości/);
  assert.match(bulkCall.files[0].content, /hurownik_og \(target\): Cześć wszystkim!\n.*trzecia/);

  // /snipe pokazuje ostatnio usuniętą wiadomość z kanału.
  const snipe = await handleInteraction({ ...commandPayload('snipe', [], { permissions: String(1n << 13n) }), channel_id: TALK }, s.bot);
  await snipe.task();
  assert.match(s.discord.state.webhook.at(-1).body.embeds[0].description, /Cześć wszystkim!|trzecia/);
});

test('logi z dziennika zdarzeń: role z autorem zmiany, pseudonim, ban; kary bota pomijane, gdy są logi moderacji', async () => {
  const s = await setup({}, { modLogChannelId: '100000000000000054' });
  await onAuditLogEntry(s.bot, {
    guild_id: GUILD,
    action_type: 25,
    user_id: 'mod',
    target_id: 'target',
    changes: [{ key: '$add', new_value: [{ id: 'r-fan', name: 'Fan' }] }, { key: '$remove', new_value: [{ id: 'r-member', name: 'Entuzjasta' }] }],
  });
  const roles = logged(s)[0].embeds[0];
  assert.match(roles.description, /Zmiana ról <@target>\*\*\n\*\*Dodano rolę:\*\* <@&r-fan>\n\*\*Zabrano rolę:\*\* <@&r-member>\n\*\*Przez:\*\* <@mod>/);
  assert.equal(roles.author.name, 'hurownik_og');

  await onAuditLogEntry(s.bot, { guild_id: GUILD, action_type: 24, user_id: 'target', target_id: 'target', changes: [{ key: 'nick', old_value: 'stary', new_value: 'nowy' }] });
  const nick = logged(s)[1].embeds[0];
  assert.deepEqual(nick.fields.map((f) => f.value), ['stary', 'nowy']);
  assert.doesNotMatch(nick.description, /Przez/, 'sam sobie zmienił');

  await onAuditLogEntry(s.bot, { guild_id: GUILD, action_type: 22, user_id: 'mod', target_id: 'target', reason: 'spam' });
  assert.match(logged(s)[2].embeds[0].description, /<@target> został\(a\) zbanowany\/a\*\*\n\*\*Przez:\*\* <@mod>\n\*\*Powód:\*\* spam/);
  await onAuditLogEntry(s.bot, { guild_id: GUILD, action_type: 22, user_id: 'bot', target_id: 'target', reason: 'x' });
  await onAuditLogEntry(s.bot, { guild_id: GUILD, action_type: 10, user_id: 'bot', target_id: '5', changes: [{ key: 'name', new_value: 'Kanał kogoś' }] });
  assert.equal(logged(s).length, 3, 'ban komendą bota i kanał na żądanie bota nie trafiają do logów serwera');

  await onAuditLogEntry(s.bot, {
    guild_id: GUILD,
    action_type: 31,
    user_id: 'mod',
    target_id: 'r-fan',
    changes: [
      { key: 'name', old_value: 'Fan', new_value: 'Superfan' },
      { key: 'permissions', old_value: '0', new_value: String((1n << 13n) | (1n << 3n)) },
      { key: 'position', old_value: 2, new_value: 3 },
    ],
  });
  const role = logged(s)[3].embeds[0].description;
  assert.match(role, /Zmieniono rolę <@&r-fan>\*\*\n\*\*Nazwa:\*\* `Fan` → `Superfan`\n\*\*Dodane uprawnienia:\*\* Administrator, Zarządzanie wiadomościami/);
  assert.doesNotMatch(role, /3/, 'pozycja to szum');
});

test('permissionDiff: nazwy dodanych i zabranych uprawnień', () => {
  assert.deepEqual(permissionDiff(String(1n << 11n), String((1n << 2n) | (1n << 40n))), {
    added: ['Banowanie członków', 'Wyciszanie (timeout)'],
    removed: ['Wysyłanie wiadomości'],
  });
});

test('logi głosowe: wejście, przejście i wyjście', async () => {
  const s = await setup({ voiceChannelId: '100000000000000055' });
  const member = { user: { id: 'target', username: 'hurownik_og' } };
  await onVoiceStateUpdate(s.bot, { guild_id: GUILD, user_id: 'target', channel_id: 'v1', member });
  await onVoiceStateUpdate(s.bot, { guild_id: GUILD, user_id: 'target', channel_id: 'v1', member, self_mute: true });
  await onVoiceStateUpdate(s.bot, { guild_id: GUILD, user_id: 'target', channel_id: 'v2', member });
  await onVoiceStateUpdate(s.bot, { guild_id: GUILD, user_id: 'target', channel_id: null, member });
  assert.deepEqual(
    logged(s, '100000000000000055').map((m) => m.embeds[0].description),
    [
      '**<@target> dołączył(a) do kanału głosowego <#v1>**',
      '**<@target> przeszedł/przeszła z <#v1> na <#v2>**',
      '**<@target> opuścił(a) kanał głosowy <#v2>**',
    ],
  );
});

test('intencje sesji: dziennik zdarzeń przy logach, treść wiadomości i członkowie tylko z flagami aplikacji', async () => {
  const s = await setup();
  const base = (1 << 0) | (1 << 7) | (1 << 9);
  assert.equal(await sessionIntents(s.bot), base | (1 << 2), 'bez flag aplikacji — bez uprzywilejowanych');
  s.discord.state.appFlags = (1 << 15) | (1 << 19);
  s.bot.cache.delete('app');
  assert.equal(await sessionIntents(s.bot), base | (1 << 2) | (1 << 1) | (1 << 15));
  await s.store.updateConfig({ logs: { enabled: false } });
  assert.equal(await sessionIntents(s.bot), base);
});

test('nowe konto: ping @here poniżej progu, wybrane role zamiast @here, starsze konto bez pingu', async () => {
  const s = await setup({ membersChannelId: '100000000000000056', newAccount: { ping: true, days: 7, mention: 'here' } });
  const idAged = (days) => String(BigInt(Date.now() - days * 86_400_000 - 1420070400000) << 22n);
  const join = (id, extra = {}) => onMemberJoin(s.bot, { guild_id: GUILD, user: { id, username: `u${id.slice(-4)}`, ...extra }, roles: [], joined_at: new Date().toISOString() });
  const log = () => logged(s, '100000000000000056').at(-1);

  await join(idAged(3));
  assert.equal(log().content, '@here');
  assert.match(log().embeds[0].description, /ma mniej niż 7 dni/);
  assert.equal(log().embeds[0].color, 0xfee75c);

  await join(idAged(30));
  assert.equal(log().content, undefined, 'stare konto bez pingu');
  assert.doesNotMatch(log().embeds[0].description, /nowe konto/);

  await join(idAged(2), { bot: true });
  assert.equal(log().content, undefined, 'boty bez pingu');

  await s.store.updateConfig({ logs: { newAccount: { mention: 'roles', roleIds: ['100000000000000057'], days: 1 } } });
  await join(idAged(0.5));
  assert.equal(log().content, '<@&100000000000000057>');
  assert.deepEqual(log().allowed_mentions, { roles: ['100000000000000057'] });
  await join(idAged(3));
  assert.equal(log().content, undefined, 'próg 1 dzień');

  const cfg = await s.store.updateConfig({ logs: { newAccount: { days: 999, mention: 'wszyscy' } } });
  assert.equal(cfg.logs.newAccount.days, 60);
  assert.equal(cfg.logs.newAccount.mention, 'roles');
});
