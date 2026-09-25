// Prosta baza danych w pliku JSON (data/db.json). Wystarcza dla jednego serwera,
// nie wymaga instalowania żadnej bazy. Zapis jest atomowy (plik tymczasowy + rename).

const fs = require('node:fs');
const path = require('node:path');
const { DEFAULT_CONFIG } = require('../config/defaults');
const { mergeWithDefaults, sanitizeConfig } = require('./configSchema');
const { DAY } = require('./duration');

const MAX_MOD_MESSAGES = 5000;

function emptyData() {
  return {
    config: structuredClone(DEFAULT_CONFIG),
    counters: { case: 0, warn: 0 },
    cases: [],
    warns: [],
    tempBans: [],
    modMessages: [],
  };
}

class Store {
  constructor(file) {
    this.file = file;
    this.saveTimer = null;
    this.data = this.load();
    this.modMessageSet = new Set(this.data.modMessages);
  }

  load() {
    const base = emptyData();
    if (!this.file || !fs.existsSync(this.file)) return base;
    const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    return {
      ...base,
      ...raw,
      config: mergeWithDefaults(DEFAULT_CONFIG, raw.config),
      counters: { ...base.counters, ...raw.counters },
    };
  }

  save() {
    if (!this.file || this.saveTimer) return;
    this.saveTimer = setTimeout(() => this.flush(), 250);
  }

  flush() {
    clearTimeout(this.saveTimer);
    this.saveTimer = null;
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2));
    fs.renameSync(tmp, this.file);
  }

  nextId(counter) {
    this.data.counters[counter] += 1;
    return this.data.counters[counter];
  }

  // ---------- Konfiguracja ----------

  get config() {
    return this.data.config;
  }

  updateConfig(input) {
    this.data.config = sanitizeConfig(input, this.data.config);
    this.save();
    return this.data.config;
  }

  // ---------- Sprawy (historia wszystkich akcji) ----------

  addCase(data) {
    const entry = {
      id: this.nextId('case'),
      createdAt: Date.now(),
      duration: null,
      expiresAt: null,
      auto: false,
      note: null,
      ...data,
    };
    this.data.cases.push(entry);
    this.save();
    return entry;
  }

  getCase(id) {
    return this.data.cases.find((c) => c.id === id) ?? null;
  }

  updateCase(id, patch) {
    const entry = this.getCase(id);
    if (!entry) return null;
    Object.assign(entry, patch);
    this.save();
    return entry;
  }

  // Usuwa sprawę, która nie doszła do skutku (np. Discord odrzucił bana). Numer i tak zostaje zużyty.
  deleteCase(id) {
    this.data.cases = this.data.cases.filter((c) => c.id !== id);
    this.save();
  }

  listCases({ userId, type, limit = 50, offset = 0 } = {}) {
    let list = this.data.cases;
    if (userId) list = list.filter((c) => c.userId === userId);
    if (type) list = list.filter((c) => c.type === type);
    const sorted = [...list].sort((a, b) => b.id - a.id);
    return { total: sorted.length, items: sorted.slice(offset, offset + limit) };
  }

  caseCounts(userId) {
    const counts = {};
    for (const c of this.data.cases) {
      if (c.userId === userId) counts[c.type] = (counts[c.type] ?? 0) + 1;
    }
    return counts;
  }

  // ---------- Ostrzeżenia ----------

  addWarn({ guildId, userId, userTag, moderatorId, moderatorTag, reason, points, caseId }) {
    const createdAt = Date.now();
    const days = this.config.warns.expiryDays;
    const warn = {
      id: this.nextId('warn'),
      guildId,
      userId,
      userTag,
      moderatorId,
      moderatorTag,
      reason,
      points,
      caseId,
      createdAt,
      expiresAt: days > 0 ? createdAt + days * DAY : null,
    };
    this.data.warns.push(warn);
    this.save();
    return warn;
  }

  getWarn(id) {
    return this.data.warns.find((w) => w.id === id) ?? null;
  }

  // Aktywne ostrzeżenia użytkownika (najnowsze pierwsze).
  getWarns(userId, now = Date.now()) {
    return this.data.warns
      .filter((w) => w.userId === userId && (w.expiresAt === null || w.expiresAt > now))
      .sort((a, b) => b.createdAt - a.createdAt);
  }

  warnSummary(userId, now = Date.now()) {
    const warns = this.getWarns(userId, now);
    const points = warns.reduce((sum, w) => sum + w.points, 0);
    const nextExpiry = warns
      .map((w) => w.expiresAt)
      .filter(Boolean)
      .sort((a, b) => a - b)[0] ?? null;
    return { count: warns.length, points, warns, nextExpiry };
  }

  updateWarnByCase(caseId, patch) {
    const warn = this.data.warns.find((w) => w.caseId === caseId);
    if (!warn) return null;
    Object.assign(warn, patch);
    this.save();
    return warn;
  }

  removeWarn(id) {
    const warn = this.getWarn(id);
    if (!warn) return null;
    this.data.warns = this.data.warns.filter((w) => w.id !== id);
    this.save();
    return warn;
  }

  clearWarns(userId) {
    const removed = this.data.warns.filter((w) => w.userId === userId);
    this.data.warns = this.data.warns.filter((w) => w.userId !== userId);
    this.save();
    return removed;
  }

  // Usuwa ostrzeżenia, którym skończyło się odliczanie. Zwraca usunięte.
  expireWarns(now = Date.now()) {
    const expired = this.data.warns.filter((w) => w.expiresAt !== null && w.expiresAt <= now);
    if (!expired.length) return [];
    this.data.warns = this.data.warns.filter((w) => !expired.includes(w));
    for (const warn of expired) {
      const entry = this.getCase(warn.caseId);
      if (entry) entry.note = 'Ostrzeżenie wygasło';
    }
    this.save();
    return expired;
  }

  // Wszyscy użytkownicy z aktywnymi ostrzeżeniami, posortowani po punktach.
  warnRanking(now = Date.now()) {
    const byUser = new Map();
    for (const w of this.data.warns) {
      if (w.expiresAt !== null && w.expiresAt <= now) continue;
      const row = byUser.get(w.userId) ?? { userId: w.userId, userTag: w.userTag, count: 0, points: 0, warns: [] };
      row.count += 1;
      row.points += w.points;
      row.userTag = w.userTag;
      row.warns.push(w);
      byUser.set(w.userId, row);
    }
    return [...byUser.values()]
      .map((row) => ({ ...row, warns: row.warns.sort((a, b) => b.createdAt - a.createdAt) }))
      .sort((a, b) => b.points - a.points || b.count - a.count);
  }

  // ---------- Tymczasowe bany ----------

  setTempBan({ guildId, userId, userTag, expiresAt, caseId }) {
    this.data.tempBans = this.data.tempBans.filter((b) => !(b.guildId === guildId && b.userId === userId));
    this.data.tempBans.push({ guildId, userId, userTag, expiresAt, caseId });
    this.save();
  }

  removeTempBan(guildId, userId) {
    const before = this.data.tempBans.length;
    this.data.tempBans = this.data.tempBans.filter((b) => !(b.guildId === guildId && b.userId === userId));
    if (this.data.tempBans.length !== before) this.save();
    return before !== this.data.tempBans.length;
  }

  listTempBans() {
    return [...this.data.tempBans].sort((a, b) => a.expiresAt - b.expiresAt);
  }

  dueTempBans(now = Date.now()) {
    return this.data.tempBans.filter((b) => b.expiresAt <= now);
  }

  // ---------- Wiadomości o karach (do reakcji na odpowiedzi) ----------

  addModMessage(messageId) {
    if (!messageId || this.modMessageSet.has(messageId)) return;
    this.modMessageSet.add(messageId);
    this.data.modMessages.push(messageId);
    if (this.data.modMessages.length > MAX_MOD_MESSAGES) {
      const dropped = this.data.modMessages.splice(0, this.data.modMessages.length - MAX_MOD_MESSAGES);
      for (const id of dropped) this.modMessageSet.delete(id);
    }
    this.save();
  }

  isModMessage(messageId) {
    return this.modMessageSet.has(messageId);
  }

  stats(now = Date.now()) {
    return {
      cases: this.data.cases.length,
      activeWarns: this.data.warns.filter((w) => w.expiresAt === null || w.expiresAt > now).length,
      tempBans: this.data.tempBans.length,
      byType: this.data.cases.reduce((acc, c) => ({ ...acc, [c.type]: (acc[c.type] ?? 0) + 1 }), {}),
    };
  }
}

const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', '..', 'data');
let instance = null;

// Wspólna instancja dla całego bota. W testach można podać własny plik (lub null = tylko pamięć).
function getStore(file = path.join(DATA_DIR, 'db.json')) {
  if (!instance) instance = new Store(file);
  return instance;
}

module.exports = { Store, getStore };
