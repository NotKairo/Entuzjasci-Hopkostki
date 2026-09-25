require('./helpers');
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Store } = require('../src/lib/db');

const DAY = 86_400_000;
const newStore = () => new Store(path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'hk-')), 'db.json'));
const warnInput = (userId, points = 1) => ({
  guildId: 'g', userId, userTag: `u${userId}`, moderatorId: 'm', moderatorTag: 'mod', reason: 'test', points, caseId: 1,
});

test('ostrzeżenia domyślnie wygasają po 60 dniach, każde z własnym terminem', () => {
  const store = newStore();
  assert.equal(store.config.warns.expiryDays, 60);
  const before = Date.now();
  const warn = store.addWarn(warnInput('1', 2));
  assert.ok(warn.expiresAt >= before + 60 * DAY && warn.expiresAt <= Date.now() + 60 * DAY);
});

test('wygasłe ostrzeżenia są usuwane i nie liczą się do punktów', () => {
  const store = newStore();
  const old = store.addWarn(warnInput('1', 3));
  const fresh = store.addWarn(warnInput('1', 2));
  old.expiresAt = Date.now() - 1000;

  assert.equal(store.warnSummary('1').points, 2);
  const expired = store.expireWarns();
  assert.deepEqual(expired.map((w) => w.id), [old.id]);
  assert.equal(store.getWarn(old.id), null);
  assert.equal(store.getWarn(fresh.id).id, fresh.id);
});

test('expiryDays = 0 oznacza brak wygasania', () => {
  const store = newStore();
  store.updateConfig({ warns: { expiryDays: 0 } });
  const warn = store.addWarn(warnInput('1'));
  assert.equal(warn.expiresAt, null);
  assert.equal(store.expireWarns(Date.now() + 10_000 * DAY).length, 0);
});

test('ranking, czyszczenie i usuwanie ostrzeżeń', () => {
  const store = newStore();
  store.addWarn(warnInput('1', 1));
  store.addWarn(warnInput('2', 5));
  const w = store.addWarn(warnInput('1', 1));
  assert.deepEqual(store.warnRanking().map((r) => [r.userId, r.points]), [['2', 5], ['1', 2]]);
  assert.equal(store.removeWarn(w.id).id, w.id);
  assert.equal(store.clearWarns('2').length, 1);
  assert.deepEqual(store.warnRanking().map((r) => r.userId), ['1']);
});

test('zapis na dysk i ponowne wczytanie', () => {
  const store = newStore();
  store.addCase({ type: 'ban', userId: '1', reason: 'x' });
  store.setTempBan({ guildId: 'g', userId: '1', userTag: 'a', expiresAt: 5, caseId: 1 });
  store.addModMessage('999');
  store.flush();
  const again = new Store(store.file);
  assert.equal(again.getCase(1).type, 'ban');
  assert.equal(again.dueTempBans(10).length, 1);
  assert.ok(again.isModMessage('999'));
});

test('walidacja konfiguracji z panelu', () => {
  const store = newStore();
  const config = store.updateConfig({
    modLogChannelId: 'nie-id',
    announceChannelId: '123456789012345678',
    modRoleIds: ['123456789012345678', 'zle', '123456789012345678'],
    dmUsers: 'tak',
    warns: { expiryDays: 30, defaultPoints: -5 },
    actions: { ban: { color: 'czerwony', title: 'Ban!' }, kick: { color: '#abcdef' } },
    escalation: { rules: [{ points: 10, action: 'ban', amount: 1, unit: 'd' }, { points: 3, action: 'hack', amount: 1, unit: 'd' }, { points: 2, action: 'alert', amount: 0, unit: 'm' }] },
    nieznane: true,
  });
  assert.equal(config.modLogChannelId, '');
  assert.equal(config.announceChannelId, '123456789012345678');
  assert.deepEqual(config.modRoleIds, ['123456789012345678']);
  assert.equal(config.dmUsers, true);
  assert.equal(config.warns.expiryDays, 30);
  assert.equal(config.warns.defaultPoints, 1);
  assert.equal(config.actions.ban.color, '#ED4245');
  assert.equal(config.actions.ban.title, 'Ban!');
  assert.equal(config.actions.kick.color, '#ABCDEF');
  assert.deepEqual(config.escalation.rules.map((r) => r.points), [2, 10]);
  assert.equal('nieznane' in config, false);
});
