import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';
import { fakeDiscord, makeBot } from './support/discord.js';
import * as moderation from '../supabase/functions/hopkostki-bot/lib/moderation.js';
import { DiscordError } from '../supabase/functions/hopkostki-bot/lib/rest.js';

async function setup(options = {}) {
  const { store, db } = await createTestStore();
  await store.updateConfig({ modLogChannelId: '100000000000000001', ...(options.config ?? {}) });
  const discord = fakeDiscord(options);
  const bot = makeBot({ store, discord });
  const users = discord.state.users;
  return { bot, store, db, discord, target: users.get('target'), mod: users.get('mod') };
}

// Atrapa odroczonej interakcji (edycja @original).
function fakeIx(bot, ephemeral = false) {
  return {
    channelId: 'chan',
    ephemeral,
    edit: (payload) => bot.discord.patch('/webhooks/app/tok/messages/@original', payload),
  };
}

const logs = (discord) => discord.state.channels.get('100000000000000001') ?? [];
const field = (embed, name) => embed.fields?.find((f) => f.name === name)?.value;
const fieldNames = (embed) => (embed.fields ?? []).map((f) => f.name).join(' | ');

test('tymczasowy ban: DM przed banem, embed na kanale z oznaczeniem, log i wpis do odbanowania', async () => {
  const s = await setup();
  const { entry, message } = await moderation.banUser(s.bot, {
    interaction: fakeIx(s.bot),
    target: s.target,
    moderator: s.mod,
    reason: 'Wielokrotne łamanie zasad',
    duration: { amount: 14, unit: 'd' },
  });

  const order = s.discord.state.calls.map((c) => `${c.method} ${c.path}`);
  assert.ok(order.indexOf('POST /users/@me/channels') < order.indexOf('PUT /guilds/g1/bans/target'), 'DM przed banem');
  assert.match(s.discord.state.bans.get('target').reason, /dfgbh65: Wielokrotne łamanie zasad \(sprawa #1\)/);

  const payload = s.discord.state.webhook[0].body;
  assert.equal(payload.content, '<@target>');
  assert.deepEqual(payload.allowed_mentions, { users: ['target'] });
  const embed = payload.embeds[0];
  assert.equal(embed.title, '⛔ Pomyślnie zbanowano użytkownika');
  assert.match(embed.description, /Pomyślnie tymczasowo zbanowałeś \*\*hurownik\\_og\*\* z serwera!/);
  assert.equal(field(embed, '⏱️ Czas trwania'), '**14 dni**');
  assert.match(field(embed, '📅 Wygasa'), /^<t:\d+:f>\n<t:\d+:R>$/);
  assert.equal(field(embed, '👤 Użytkownik'), '<@target>\n`hurownik_og`');
  assert.equal(field(embed, '📝 Powód'), '```\nWielokrotne łamanie zasad\n```');
  assert.equal(embed.footer.text, `Sprawa #${entry.id} • Entuzjaści Hopkostki`);

  assert.match(s.discord.state.dms[0].embeds[0].description, /tymczasowo zbanowany na serwerze/);
  assert.deepEqual([...(await s.store.filterModMessages([message.id]))], [message.id]);
  assert.equal((await s.store.listTempBans())[0].caseId, entry.id);
  assert.equal((await s.store.getCase(entry.id)).dmStatus, '✅ dostarczono');
  assert.match(logs(s.discord)[0].embeds[0].author.name, /Tymczasowy ban \| Sprawa #1/);
});

test('ban permanentny usuwa wcześniejszy tymczasowy wpis', async () => {
  const s = await setup();
  await s.store.setTempBan({ guildId: 'g1', userId: 'target', userTag: 'x', expiresAt: Date.now() + 1000, caseId: 1 });
  await moderation.banUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'r' });
  assert.equal((await s.store.listTempBans()).length, 0);
  assert.equal(field(s.discord.state.webhook[0].body.embeds[0], '⏱️ Czas trwania'), '**Permanentny**');
});

test('nieudany ban: sprawa i wysłany DM są wycofywane', async () => {
  const s = await setup({ banError: new DiscordError(403, { code: 50013, message: 'Missing Permissions' }) });
  await assert.rejects(
    moderation.banUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'r' }),
    (error) => error instanceof moderation.ActionError && /Brakuje mi uprawnień/.test(error.message),
  );
  assert.equal((await s.store.listCases()).total, 0);
  assert.equal(s.discord.state.deletedDms.length, 1);
});

test('zamknięte DM nie blokują kicka', async () => {
  const s = await setup({ dmFails: true });
  const { entry } = await moderation.kickUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'r' });
  assert.equal(s.discord.state.kicked[0].id, 'target');
  assert.equal((await s.store.getCase(entry.id)).dmStatus, '❌ nie udało się (zamknięte DM)');
});

test('timeout: poprawna data końca i czas w wiadomości', async () => {
  const s = await setup();
  const before = Date.now();
  await moderation.timeoutUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'spam', duration: { amount: 3, unit: 'h' } });
  const until = new Date(s.discord.state.timeouts[0].until).getTime();
  assert.ok(Math.abs(until - (before + 3 * 3_600_000)) < 5_000);
  assert.match(s.discord.state.webhook[0].body.embeds[0].description, /na \*\*3 godziny\*\*/);
});

test('ostrzeżenie: punkty ukryte przed użytkownikiem, widoczne w logu, odliczanie 60 dni', async () => {
  const s = await setup();
  await moderation.warnUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'r', points: 2 });
  const publicEmbed = s.discord.state.webhook[0].body.embeds[0];
  const dmEmbed = s.discord.state.dms[0].embeds[0];
  const logEmbed = logs(s.discord)[0].embeds[0].description;
  assert.doesNotMatch(fieldNames(publicEmbed), /Punkty/);
  assert.doesNotMatch(fieldNames(dmEmbed), /Punkty/);
  assert.match(field(publicEmbed, '⏳ Ostrzeżenie wygasa'), /^<t:\d+:f>/);
  assert.match(field(dmEmbed, '⏳ Ostrzeżenie wygasa'), /^<t:\d+:f>/);
  assert.match(logEmbed, /\*\*Punkty:\*\* \+2/);
  assert.match(logEmbed, /łącznie \*\*2 pkt\*\*/);
  const [warn] = await s.store.getWarns('target');
  assert.ok(warn.expiresAt - Date.now() > 59 * 86_400_000);
});

test('automatyczna kara po przekroczeniu progu punktów', async () => {
  const s = await setup({ config: { escalation: { enabled: true, rules: [{ points: 3, action: 'timeout', amount: 1, unit: 'h' }] } } });
  await moderation.warnUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'a', points: 2 });
  assert.equal(s.discord.state.timeouts.length, 0);
  await moderation.warnUser(s.bot, { interaction: fakeIx(s.bot), target: s.target, moderator: s.mod, reason: 'b', points: 1 });
  assert.equal(s.discord.state.timeouts.length, 1);
  const auto = (await s.store.listCases({ type: 'timeout' })).items[0];
  assert.equal(auto.auto, true);
  assert.equal(auto.moderatorId, 'bot');
  assert.match(auto.reason, /Automatyczna kara: 3 pkt/);
});

test('hierarchia ról i ochrona właściciela', async () => {
  const s = await setup();
  const gctx = await moderation.getGuildContext(s.bot);
  const mod = { id: 'mod', roles: ['r-mod'] };
  const base = { gctx, moderator: mod, target: s.target, targetMember: { roles: ['r-member'] }, action: 'ban' };
  assert.equal(moderation.checkTarget(base), null);
  assert.match(moderation.checkTarget({ ...base, target: s.mod }), /na sobie/);
  assert.match(moderation.checkTarget({ ...base, targetMember: { roles: ['r-admin'] } }), /równą lub wyższą/);
  assert.match(moderation.checkTarget({ ...base, target: { id: 'owner' } }), /właściciela/);
  assert.match(moderation.checkTarget({ ...base, target: { id: 'bot' } }), /samego siebie/);
  assert.match(moderation.checkTarget({ ...base, targetMember: null, action: 'kick' }), /nie ma na serwerze/);
  assert.equal(moderation.checkTarget({ ...base, targetMember: null }), null);
  // Właściciel może ukarać każdego poniżej bota, ale bot nie ukarze kogoś wyżej od siebie.
  assert.match(
    moderation.checkTarget({ ...base, moderator: { id: 'owner', roles: [] }, targetMember: { roles: ['r-admin'] }, action: 'kick' }),
    /moja rola musi być wyżej/,
  );
});

test('wygasły tymczasowy ban jest zdejmowany automatycznie', async () => {
  const s = await setup();
  s.discord.state.bans.set('target', { user: s.target });
  await s.store.setTempBan({ guildId: 'g1', userId: 'target', userTag: 'hurownik_og', expiresAt: Date.now() - 1, caseId: 7 });
  await moderation.expireTempBan(s.bot, (await s.store.dueTempBans())[0]);
  assert.equal(s.discord.state.bans.size, 0);
  assert.equal((await s.store.listTempBans()).length, 0);
  const unban = (await s.store.listCases({ type: 'unban' })).items[0];
  assert.equal(unban.auto, true);
  assert.match(unban.reason, /sprawa #7/);
});

test('hasModAccess: nadpisanie komendy liczy się zamiast uprawnień Discorda i modRoleIds', async () => {
  // ID ról jak prawdziwe snowflaki Discorda — sanitizeConfig odrzuca krótkie napisy typu "r-vip".
  const SUPPORT = '100000000000000010';
  const VIP = '100000000000000020';
  const s = await setup({ config: { modRoleIds: [SUPPORT], commandPermissions: { kick: [VIP] } } });
  const config = await s.store.getConfig();

  // Zwykły moderator (uprawnienie Discorda KICK_MEMBERS) — bez nadpisania miałby dostęp, z nim już nie.
  const modMember = { id: 'mod', roles: ['r-mod'], permissions: String((1n << 1n) | (1n << 2n) | (1n << 40n)) };
  assert.equal(moderation.hasModAccess(modMember, 1n << 1n, config, 'kick'), false);
  // Rola z listy nadpisania — dostęp, mimo braku jakichkolwiek uprawnień Discorda.
  const vipMember = { id: 'vip', roles: [VIP], permissions: '0' };
  assert.equal(moderation.hasModAccess(vipMember, 1n << 1n, config, 'kick'), true);
  // Rola z ogólnej listy moderatorów (modRoleIds) NIE omija nadpisania konkretnej komendy.
  const supportMember = { id: 'support', roles: [SUPPORT], permissions: '0' };
  assert.equal(moderation.hasModAccess(supportMember, 1n << 1n, config, 'kick'), false);
  // Administrator zawsze przechodzi, nadpisanie go nie blokuje.
  const adminMember = { id: 'admin', roles: [], permissions: String(1n << 3n) };
  assert.equal(moderation.hasModAccess(adminMember, 1n << 1n, config, 'kick'), true);
  // Inna komenda bez nadpisania nadal działa na starych zasadach (modRoleIds wystarcza).
  assert.equal(moderation.hasModAccess(supportMember, 1n << 40n, config, 'timeout'), true);
  // Komenda bez wymaganych uprawnień jest otwarta dla wszystkich, gdy nie ma nadpisania.
  assert.equal(moderation.hasModAccess({ id: 'x', roles: [], permissions: '0' }, null, config, 'serwer'), true);
  // Brak członka (np. spoza serwera) nigdy nie dostaje dostępu.
  assert.equal(moderation.hasModAccess(null, null, config, 'serwer'), false);
});

test('roleHasAccess: ta sama logika co hasModAccess, ale liczona dla roli (panel)', async () => {
  const SUPPORT = '100000000000000010';
  const VIP = '100000000000000020';
  const s = await setup({ config: { modRoleIds: [SUPPORT], commandPermissions: { kick: [VIP] } } });
  const config = await s.store.getConfig();
  const role = (id, permissions = '0') => ({ id, permissions });

  assert.equal(moderation.roleHasAccess(role('r-mod', String(1n << 1n)), 1n << 1n, config, 'kick'), false);
  assert.equal(moderation.roleHasAccess(role(VIP), 1n << 1n, config, 'kick'), true);
  assert.equal(moderation.roleHasAccess(role(SUPPORT), 1n << 1n, config, 'kick'), false);
  assert.equal(moderation.roleHasAccess(role('r-admin', String(1n << 3n)), 1n << 1n, config, 'kick'), true);
  assert.equal(moderation.roleHasAccess(role(SUPPORT), 1n << 40n, config, 'timeout'), true);
  assert.equal(moderation.roleHasAccess(role('everyone'), null, config, 'serwer'), true);
});
