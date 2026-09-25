import test from 'node:test';
import assert from 'node:assert/strict';
import { createTestStore } from './support/db.js';

const DAY = 86_400_000;
const warnInput = (userId, points = 1, caseId = null) => ({
  guildId: 'g1', userId, userTag: `u${userId}`, moderatorId: 'm', moderatorTag: 'mod', reason: 'test', points, caseId,
});

test('konfiguracja: domyślne 60 dni wygasania i walidacja zapisu', async () => {
  const { store } = await createTestStore();
  assert.equal((await store.getConfig()).warns.expiryDays, 60);
  const config = await store.updateConfig({
    modLogChannelId: 'nie-id',
    announceChannelId: '123456789012345678',
    modRoleIds: ['123456789012345678', 'zle'],
    warns: { expiryDays: 30, defaultPoints: -5 },
    actions: { ban: { color: 'czerwony', title: 'Ban!' } },
    escalation: { rules: [{ points: 10, action: 'ban', amount: 1, unit: 'd' }, { points: 3, action: 'hack', amount: 1, unit: 'd' }] },
    nieznane: true,
  });
  assert.equal(config.modLogChannelId, '');
  assert.equal(config.announceChannelId, '123456789012345678');
  assert.deepEqual(config.modRoleIds, ['123456789012345678']);
  assert.equal(config.warns.expiryDays, 30);
  assert.equal(config.warns.defaultPoints, 1);
  assert.equal(config.actions.ban.color, '#ED4245');
  assert.equal(config.actions.ban.title, 'Ban!');
  assert.deepEqual(config.escalation.rules.map((r) => r.points), [10]);
  assert.equal('nieznane' in config, false);
  assert.deepEqual(await store.getConfig(), config);
});

test('każde ostrzeżenie ma własne odliczanie 60 dni', async () => {
  const { store } = await createTestStore();
  const before = Date.now();
  const warn = await store.addWarn(warnInput('1', 2), 60);
  assert.ok(Math.abs(warn.expiresAt - (before + 60 * DAY)) < 5_000);
  const never = await store.addWarn(warnInput('1', 1), 0);
  assert.equal(never.expiresAt, null);
});

test('wygasłe ostrzeżenia są usuwane, a sprawa dostaje notatkę', async () => {
  const { store, db } = await createTestStore();
  const entry = await store.addCase({ type: 'warn', guildId: 'g1', userId: '1', reason: 'r' });
  const old = await store.addWarn(warnInput('1', 3, entry.id), 60);
  const fresh = await store.addWarn(warnInput('1', 2), 60);
  await db.query(`update bot.warns set expires_at = now() - interval '1 minute' where id = $1`, [old.id]);

  assert.equal((await store.warnSummary('1')).points, 2);
  const expired = await store.expireWarns();
  assert.deepEqual(expired.map((w) => w.id), [old.id]);
  assert.equal(await store.getWarn(old.id), null);
  assert.equal((await store.getWarn(fresh.id)).id, fresh.id);
  assert.equal((await store.getCase(entry.id)).note, 'Ostrzeżenie wygasło');
});

test('ranking, usuwanie i czyszczenie ostrzeżeń', async () => {
  const { store } = await createTestStore();
  await store.addWarn(warnInput('1', 1), 60);
  await store.addWarn(warnInput('2', 5), 60);
  const w = await store.addWarn(warnInput('1', 1), 60);
  assert.deepEqual((await store.warnRanking()).map((r) => [r.userId, r.points]), [['2', 5], ['1', 2]]);
  assert.equal((await store.removeWarn(w.id)).id, w.id);
  assert.equal((await store.clearWarns('2')).length, 1);
  assert.deepEqual((await store.warnRanking()).map((r) => r.userId), ['1']);
});

test('sprawy: lista, filtrowanie, szukanie po nicku, aktualizacja', async () => {
  const { store } = await createTestStore();
  const a = await store.addCase({ type: 'ban', guildId: 'g1', userId: '1', userTag: 'hurownik_og', moderatorTag: 'dfgbh65', reason: 'x', duration: { amount: 14, unit: 'd' }, expiresAt: Date.now() + DAY });
  await store.addCase({ type: 'kick', guildId: 'g1', userId: '2', userTag: 'trollek', reason: 'y' });
  assert.equal((await store.listCases()).total, 2);
  assert.equal((await store.listCases({ type: 'kick' })).items[0].userTag, 'trollek');
  assert.equal((await store.listCases({ search: 'hurow' })).items[0].id, a.id);
  assert.deepEqual(a.duration, { amount: 14, unit: 'd' });
  const updated = await store.updateCase(a.id, { reason: 'nowy', dmStatus: '✅' });
  assert.equal(updated.reason, 'nowy');
  assert.deepEqual(await store.caseCounts('1'), { ban: 1 });
});

test('tymczasowe bany, wiadomości o karach, kursory i blokada crona', async () => {
  const { store } = await createTestStore();
  await store.setTempBan({ guildId: 'g1', userId: '1', userTag: 'a', expiresAt: Date.now() - 1000, caseId: 1 });
  await store.setTempBan({ guildId: 'g1', userId: '2', userTag: 'b', expiresAt: Date.now() + DAY, caseId: 2 });
  assert.deepEqual((await store.dueTempBans()).map((b) => b.userId), ['1']);
  assert.equal(await store.removeTempBan('g1', '1'), true);

  await store.addModMessage('1000', 'chan', 1);
  await store.addModMessage('900', 'chan', 2);
  assert.deepEqual([...(await store.filterModMessages(['1000', '5']))], ['1000']);
  assert.deepEqual(await store.recentModChannels(7), [{ channelId: 'chan', firstMessageId: '900', cursor: null }]);
  await store.setCursor('chan', '1200');
  assert.equal((await store.recentModChannels(7))[0].cursor, '1200');

  assert.equal(await store.acquireCronLock(), true);
  assert.equal(await store.acquireCronLock(), false);
  await store.releaseCronLock();
  assert.equal(await store.acquireCronLock(), true);
  assert.equal(typeof (await store.getState('cron_secret')), 'string');
});
