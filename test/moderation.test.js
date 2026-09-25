require('./helpers');
const test = require('node:test');
const assert = require('node:assert/strict');
const { getStore } = require('../src/lib/db');
const moderation = require('../src/lib/moderation');
const messageCreate = require('../src/events/messageCreate');
const { fakeUser, fakeChannel, fakeMember, fakeGuild, fakeInteraction } = require('./fakes');

const store = getStore();
const LOG_ID = '100000000000000001';

function setup(options = {}) {
  const events = [];
  const bot = fakeUser('bot', 'Hopkostki Bot');
  const mod = fakeUser('mod', 'dfgbh65');
  const target = fakeUser('target', 'hurownik_og', options);
  const targetMember = fakeMember(target, { position: 1, events });
  const modMember = fakeMember(mod, { position: 5 });
  const channel = fakeChannel('chan');
  const log = fakeChannel(LOG_ID);
  const guild = fakeGuild({ bot, members: [targetMember], channels: [channel, log], events, banError: options.banError });
  const interaction = fakeInteraction(channel, mod);
  const origSend = target.send.bind(target);
  target.send = async (payload) => {
    events.push('dm');
    return origSend(payload);
  };
  return { events, bot, mod, target, targetMember, modMember, channel, log, guild, interaction };
}

test.beforeEach(() => {
  store.updateConfig({ modLogChannelId: LOG_ID, escalation: { enabled: false } });
});

test('tymczasowy ban: DM przed banem, ogłoszenie z oznaczeniem, log i wpis do odbanowania', async () => {
  const s = setup();
  const { entry, message } = await moderation.banUser({
    interaction: s.interaction,
    guild: s.guild,
    target: s.target,
    moderator: s.mod,
    reason: 'Wielokrotne łamanie zasad',
    duration: { amount: 14, unit: 'd' },
  });

  assert.deepEqual(s.events, ['dm', 'ban']);
  assert.equal(s.guild.banned[0].id, 'target');
  const payload = s.interaction.replies[0];
  assert.equal(payload.content, '<@target>');
  const embed = payload.embeds[0].toJSON();
  assert.equal(embed.title, '⛔ Pomyślnie zbanowano użytkownika');
  assert.match(embed.description, /Pomyślnie tymczasowo zbanowałeś \*\*hurownik\\_og\*\* z serwera!/);
  assert.match(embed.description, /\*\*Czas:\*\* 14 dni/);
  assert.match(embed.description, /\*\*Wygasa:\*\* <t:\d+:f> \(<t:\d+:R>\)/);
  assert.equal(embed.footer.text, `Sprawa #${entry.id} • Entuzjaści Hopkostki`);

  const dm = s.target.dms[0].payload.embeds[0].toJSON();
  assert.match(dm.title, /Zostałeś zbanowany/);
  assert.match(dm.description, /tymczasowo zbanowany na serwerze/);

  assert.ok(store.isModMessage(message.id));
  assert.equal(store.listTempBans().find((b) => b.userId === 'target').caseId, entry.id);
  assert.match(s.log.sent[0].payload.embeds[0].toJSON().author.name, /Tymczasowy ban \| Sprawa #/);
});

test('ban permanentny usuwa wcześniejszy tymczasowy wpis', async () => {
  const s = setup();
  store.setTempBan({ guildId: 'g1', userId: 'target', userTag: 'x', expiresAt: Date.now() + 1000, caseId: 1 });
  await moderation.banUser({ interaction: s.interaction, guild: s.guild, target: s.target, moderator: s.mod, reason: 'r' });
  assert.equal(store.listTempBans().some((b) => b.userId === 'target'), false);
  assert.match(s.interaction.replies[0].embeds[0].toJSON().description, /\*\*Czas:\*\* Permanentny/);
});

test('nieudany ban: sprawa i DM są wycofywane', async () => {
  const s = setup({ banError: new Error('Missing Permissions') });
  const casesBefore = store.listCases().total;
  await assert.rejects(
    moderation.banUser({ interaction: s.interaction, guild: s.guild, target: s.target, moderator: s.mod, reason: 'r' }),
    moderation.ActionError,
  );
  assert.equal(store.listCases().total, casesBefore);
  assert.equal(s.target.dms[0].deleted, true);
});

test('zamknięte DM nie blokują kary', async () => {
  const s = setup({ dmFails: true });
  const { entry } = await moderation.kickUser({
    interaction: s.interaction, guild: s.guild, target: s.target, targetMember: s.targetMember, moderator: s.mod, reason: 'r',
  });
  assert.ok(s.targetMember.kicked);
  assert.equal(store.getCase(entry.id).dmStatus, '❌ nie udało się (zamknięte DM)');
});

test('timeout: czas w wiadomości i poprawne ms', async () => {
  const s = setup();
  await moderation.timeoutUser({
    interaction: s.interaction, guild: s.guild, target: s.target, targetMember: s.targetMember, moderator: s.mod,
    reason: 'spam', duration: { amount: 3, unit: 'h' },
  });
  assert.equal(s.targetMember.timeoutCalls[0].ms, 3 * 3_600_000);
  assert.match(s.interaction.replies[0].embeds[0].toJSON().description, /na \*\*3 godziny\*\*/);
});

test('ostrzeżenie: punkty ukryte przed użytkownikiem, widoczne w logu, z odliczaniem 60 dni', async () => {
  const s = setup();
  await moderation.warnUser({ interaction: s.interaction, guild: s.guild, target: s.target, moderator: s.mod, reason: 'r', points: 2 });
  const publicEmbed = s.interaction.replies[0].embeds[0].toJSON().description;
  const dmEmbed = s.target.dms[0].payload.embeds[0].toJSON().description;
  const logEmbed = s.log.sent[0].payload.embeds[0].toJSON().description;
  assert.doesNotMatch(publicEmbed, /Punkty/);
  assert.doesNotMatch(dmEmbed, /Punkty/);
  assert.match(publicEmbed, /Ostrzeżenie wygasa:\*\* <t:\d+:f>/);
  assert.match(logEmbed, /\*\*Punkty:\*\* \+2/);
  assert.match(logEmbed, /łącznie \*\*\d+ pkt\*\*/);
});

test('automatyczna kara po przekroczeniu progu punktów', async () => {
  store.clearWarns('target');
  store.updateConfig({ escalation: { enabled: true, rules: [{ points: 3, action: 'timeout', amount: 1, unit: 'h' }] } });
  const s = setup();
  await moderation.warnUser({ interaction: s.interaction, guild: s.guild, target: s.target, moderator: s.mod, reason: 'a', points: 2 });
  assert.equal(s.targetMember.timeoutCalls.length, 0);
  await moderation.warnUser({ interaction: s.interaction, guild: s.guild, target: s.target, moderator: s.mod, reason: 'b', points: 1 });
  assert.equal(s.targetMember.timeoutCalls[0].ms, 3_600_000);
  const autoCase = store.listCases({ userId: 'target', type: 'timeout' }).items[0];
  assert.equal(autoCase.auto, true);
  assert.match(autoCase.reason, /Automatyczna kara: 3 pkt/);
});

test('crossedRule wybiera najwyższy przekroczony próg', () => {
  const rules = [{ points: 5 }, { points: 10 }, { points: 20 }];
  assert.equal(moderation.crossedRule(rules, 4, 12).points, 10);
  assert.equal(moderation.crossedRule(rules, 10, 12), null);
  assert.equal(moderation.crossedRule(rules, 0, 25).points, 20);
});

test('hierarchia ról i ochrona właściciela', () => {
  const s = setup();
  const base = { guild: s.guild, moderatorMember: s.modMember, target: s.target, targetMember: s.targetMember, action: 'ban' };
  assert.equal(moderation.checkTarget(base), null);
  assert.match(moderation.checkTarget({ ...base, target: s.mod, targetMember: s.modMember }), /na sobie/);
  const boss = fakeMember(s.target, { position: 9 });
  assert.match(moderation.checkTarget({ ...base, targetMember: boss }), /równą lub wyższą/);
  assert.match(moderation.checkTarget({ ...base, target: { id: 'owner' } }), /właściciela/);
  assert.match(moderation.checkTarget({ ...base, targetMember: null, action: 'kick' }), /nie ma na serwerze/);
  assert.equal(moderation.checkTarget({ ...base, targetMember: null, action: 'ban' }), null);
});

test('odpowiedź na wiadomość o karze dostaje reakcję 🫓', async () => {
  store.addModMessage('kara123');
  const reacted = [];
  const makeMessage = (ref) => ({
    inGuild: () => true,
    author: { bot: false },
    reference: ref ? { messageId: ref } : null,
    async react(emoji) { reacted.push([ref, emoji]); },
  });
  await messageCreate.execute(makeMessage('kara123'));
  await messageCreate.execute(makeMessage('inna'));
  await messageCreate.execute(makeMessage(null));
  assert.deepEqual(reacted, [['kara123', '🫓']]);
});

test('wygasły tymczasowy ban jest zdejmowany automatycznie', async () => {
  const s = setup();
  s.guild.banned.push({ id: 'target' });
  const client = { user: s.bot, guilds: { cache: new Map([['g1', s.guild]]) }, users: { fetch: async () => s.target } };
  store.setTempBan({ guildId: 'g1', userId: 'target', userTag: 'hurownik_og', expiresAt: Date.now() - 1, caseId: 7 });
  await moderation.expireTempBan(client, store.dueTempBans()[0]);
  assert.equal(s.guild.banned.length, 0);
  assert.equal(store.listTempBans().length, 0);
  const unban = store.listCases({ type: 'unban' }).items[0];
  assert.equal(unban.auto, true);
  assert.match(unban.reason, /sprawa #7/);
});
