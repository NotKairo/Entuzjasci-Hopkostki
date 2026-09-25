// Dostęp do bazy (schemat "bot" w Postgresie Supabase).
// query(text, params) => Promise<wiersze> — w funkcji Edge to postgres.js, w testach PGlite.

import { DEFAULT_CONFIG } from './defaults.js';
import { mergeWithDefaults, sanitizeConfig } from './configSchema.js';

const ms = (value) => (value === null || value === undefined ? null : new Date(value).getTime());

function mapCase(r) {
  return {
    id: r.id,
    type: r.type,
    guildId: r.guild_id,
    userId: r.user_id,
    userTag: r.user_tag,
    moderatorId: r.moderator_id,
    moderatorTag: r.moderator_tag,
    reason: r.reason,
    duration: r.duration ?? null,
    expiresAt: ms(r.expires_at),
    auto: r.auto,
    note: r.note,
    dmStatus: r.dm_status,
    messageUrl: r.message_url,
    createdAt: ms(r.created_at),
  };
}

function mapWarn(r) {
  return {
    id: r.id,
    guildId: r.guild_id,
    userId: r.user_id,
    userTag: r.user_tag,
    moderatorId: r.moderator_id,
    moderatorTag: r.moderator_tag,
    reason: r.reason,
    points: r.points,
    caseId: r.case_id,
    createdAt: ms(r.created_at),
    expiresAt: ms(r.expires_at),
  };
}

function mapTempBan(r) {
  return { guildId: r.guild_id, userId: r.user_id, userTag: r.user_tag, expiresAt: ms(r.expires_at), caseId: r.case_id };
}

const CASE_COLUMNS = { reason: 'reason', note: 'note', dmStatus: 'dm_status', messageUrl: 'message_url' };
const ACTIVE_WARN = '(expires_at is null or expires_at > now())';

export function createStore(query) {
  const one = async (text, params) => (await query(text, params))[0] ?? null;

  const store = {
    query,

    // ---------- Konfiguracja ----------
    async getConfig() {
      const row = await one('select data from bot.config where id = 1');
      return mergeWithDefaults(DEFAULT_CONFIG, row?.data);
    },

    async updateConfig(input) {
      const next = sanitizeConfig(input, await store.getConfig());
      await query(
        `insert into bot.config (id, data, updated_at) values (1, $1::jsonb, now())
         on conflict (id) do update set data = excluded.data, updated_at = now()`,
        [JSON.stringify(next)],
      );
      return next;
    },

    // ---------- Sprawy ----------
    async addCase(c) {
      const row = await one(
        `insert into bot.cases (type, guild_id, user_id, user_tag, moderator_id, moderator_tag, reason, duration, expires_at, auto)
         values ($1::text, $2::text, $3::text, $4::text, $5::text, $6::text, $7::text, $8::jsonb,
                 case when $9::float8 is null then null else to_timestamp($9::float8 / 1000) end, $10::boolean)
         returning *`,
        [
          c.type,
          c.guildId,
          c.userId,
          c.userTag ?? null,
          c.moderatorId ?? null,
          c.moderatorTag ?? null,
          c.reason,
          c.duration ? JSON.stringify(c.duration) : null,
          c.expiresAt ?? null,
          Boolean(c.auto),
        ],
      );
      return mapCase(row);
    },

    async getCase(id) {
      const row = await one('select * from bot.cases where id = $1::int', [id]);
      return row ? mapCase(row) : null;
    },

    async updateCase(id, patch) {
      const entries = Object.entries(patch).filter(([key]) => CASE_COLUMNS[key]);
      if (!entries.length) return store.getCase(id);
      const sets = entries.map(([key], i) => `${CASE_COLUMNS[key]} = $${i + 2}::text`).join(', ');
      const row = await one(`update bot.cases set ${sets} where id = $1::int returning *`, [id, ...entries.map(([, v]) => v)]);
      return row ? mapCase(row) : null;
    },

    async deleteCase(id) {
      await query('delete from bot.cases where id = $1::int', [id]);
    },

    async listCases({ userId, type, search, limit = 50, offset = 0 } = {}) {
      const where = [];
      const params = [];
      if (userId) {
        params.push(userId);
        where.push(`user_id = $${params.length}::text`);
      }
      if (type) {
        params.push(type);
        where.push(`type = $${params.length}::text`);
      }
      if (search) {
        params.push(`%${search}%`);
        where.push(`(user_tag ilike $${params.length}::text or moderator_tag ilike $${params.length}::text)`);
      }
      const clause = where.length ? `where ${where.join(' and ')}` : '';
      const total = await one(`select count(*)::int as n from bot.cases ${clause}`, params);
      const rows = await query(
        `select * from bot.cases ${clause} order by id desc limit $${params.length + 1}::int offset $${params.length + 2}::int`,
        [...params, limit, offset],
      );
      return { total: total.n, items: rows.map(mapCase) };
    },

    async caseCounts(userId) {
      const rows = await query('select type, count(*)::int as n from bot.cases where user_id = $1::text group by type', [userId]);
      return Object.fromEntries(rows.map((r) => [r.type, r.n]));
    },

    // ---------- Ostrzeżenia ----------
    async addWarn(w, expiryDays) {
      const row = await one(
        `insert into bot.warns (guild_id, user_id, user_tag, moderator_id, moderator_tag, reason, points, case_id, expires_at)
         values ($1::text, $2::text, $3::text, $4::text, $5::text, $6::text, $7::int, $8::int,
                 case when $9::int > 0 then now() + make_interval(days => $9::int) end)
         returning *`,
        [w.guildId, w.userId, w.userTag, w.moderatorId, w.moderatorTag, w.reason, w.points, w.caseId, expiryDays],
      );
      return mapWarn(row);
    },

    async getWarn(id) {
      const row = await one(`select * from bot.warns where id = $1::int and ${ACTIVE_WARN}`, [id]);
      return row ? mapWarn(row) : null;
    },

    async getWarns(userId) {
      const rows = await query(`select * from bot.warns where user_id = $1::text and ${ACTIVE_WARN} order by created_at desc, id desc`, [userId]);
      return rows.map(mapWarn);
    },

    async warnSummary(userId) {
      const warns = await store.getWarns(userId);
      const points = warns.reduce((sum, w) => sum + w.points, 0);
      const nextExpiry = warns.map((w) => w.expiresAt).filter(Boolean).sort((a, b) => a - b)[0] ?? null;
      return { count: warns.length, points, warns, nextExpiry };
    },

    async removeWarn(id) {
      const row = await one('delete from bot.warns where id = $1::int returning *', [id]);
      return row ? mapWarn(row) : null;
    },

    async clearWarns(userId) {
      const rows = await query('delete from bot.warns where user_id = $1::text returning *', [userId]);
      return rows.map(mapWarn);
    },

    async updateWarnByCase(caseId, { reason }) {
      await query('update bot.warns set reason = $2::text where case_id = $1::int', [caseId, reason]);
    },

    // Usuwa ostrzeżenia, którym skończyło się odliczanie, i dopisuje notatkę w sprawie.
    async expireWarns() {
      const rows = await query('delete from bot.warns where expires_at is not null and expires_at <= now() returning *');
      const ids = rows.map((r) => r.case_id).filter(Boolean);
      if (ids.length) {
        await query(
          `update bot.cases set note = 'Ostrzeżenie wygasło' where id in (select (jsonb_array_elements_text($1::jsonb))::int)`,
          [JSON.stringify(ids)],
        );
      }
      return rows.map(mapWarn);
    },

    async warnRanking() {
      const rows = await query(`select * from bot.warns where ${ACTIVE_WARN} order by created_at desc, id desc`);
      const byUser = new Map();
      for (const w of rows.map(mapWarn)) {
        const row = byUser.get(w.userId) ?? { userId: w.userId, userTag: w.userTag, count: 0, points: 0, warns: [] };
        row.count += 1;
        row.points += w.points;
        row.warns.push(w);
        byUser.set(w.userId, row);
      }
      return [...byUser.values()].sort((a, b) => b.points - a.points || b.count - a.count);
    },

    // ---------- Tymczasowe bany ----------
    async setTempBan({ guildId, userId, userTag, expiresAt, caseId }) {
      await query(
        `insert into bot.temp_bans (guild_id, user_id, user_tag, expires_at, case_id)
         values ($1::text, $2::text, $3::text, to_timestamp($4::float8 / 1000), $5::int)
         on conflict (guild_id, user_id) do update
           set user_tag = excluded.user_tag, expires_at = excluded.expires_at, case_id = excluded.case_id`,
        [guildId, userId, userTag, expiresAt, caseId],
      );
    },

    async removeTempBan(guildId, userId) {
      const rows = await query('delete from bot.temp_bans where guild_id = $1::text and user_id = $2::text returning user_id', [guildId, userId]);
      return rows.length > 0;
    },

    async listTempBans() {
      return (await query('select * from bot.temp_bans order by expires_at')).map(mapTempBan);
    },

    async dueTempBans() {
      return (await query('select * from bot.temp_bans where expires_at <= now() order by expires_at')).map(mapTempBan);
    },

    // ---------- Wiadomości o karach (reakcje na odpowiedzi) ----------
    async addModMessage(messageId, channelId, caseId) {
      await query(
        `insert into bot.mod_messages (message_id, channel_id, case_id) values ($1::text, $2::text, $3::int)
         on conflict (message_id) do nothing`,
        [messageId, channelId, caseId ?? null],
      );
    },

    async filterModMessages(ids) {
      if (!ids.length) return new Set();
      const rows = await query(
        'select message_id from bot.mod_messages where message_id in (select jsonb_array_elements_text($1::jsonb))',
        [JSON.stringify(ids)],
      );
      return new Set(rows.map((r) => r.message_id));
    },

    // Kanały z wiadomościami o karach z ostatnich dni + najstarsza z nich (punkt startowy przeglądania).
    async recentModChannels(days = 7) {
      const rows = await query(
        `select m.channel_id, min(m.message_id::numeric)::text as first_message_id, c.last_message_id
         from bot.mod_messages m left join bot.channel_cursors c on c.channel_id = m.channel_id
         where m.created_at > now() - make_interval(days => $1::int)
         group by m.channel_id, c.last_message_id`,
        [days],
      );
      return rows.map((r) => ({ channelId: r.channel_id, firstMessageId: r.first_message_id, cursor: r.last_message_id }));
    },

    async setCursor(channelId, messageId) {
      await query(
        `insert into bot.channel_cursors (channel_id, last_message_id, updated_at) values ($1::text, $2::text, now())
         on conflict (channel_id) do update set last_message_id = excluded.last_message_id, updated_at = now()`,
        [channelId, messageId],
      );
    },

    async pruneModMessages(days = 30) {
      await query('delete from bot.mod_messages where created_at < now() - make_interval(days => $1::int)', [days]);
    },

    // ---------- Stan techniczny ----------
    async getState(key) {
      const row = await one('select value from bot.state where key = $1::text', [key]);
      return row?.value ?? null;
    },

    async setState(key, value) {
      await query(
        `insert into bot.state (key, value, updated_at) values ($1::text, $2::jsonb, now())
         on conflict (key) do update set value = excluded.value, updated_at = now()`,
        [key, JSON.stringify(value)],
      );
    },

    // Blokada, żeby dwa przebiegi crona nie działały naraz (wygasa po 60 s na wypadek awarii).
    async acquireCronLock() {
      const rows = await query(
        `update bot.state set value = to_jsonb(now()::text), updated_at = now()
         where key = 'cron_lock' and (value #>> '{}')::timestamptz < now() - interval '60 seconds'
         returning key`,
      );
      return rows.length > 0;
    },

    async releaseCronLock() {
      await query(`update bot.state set value = to_jsonb('1970-01-01T00:00:00Z'::text) where key = 'cron_lock'`);
    },

    async stats() {
      const row = await one(
        `select (select count(*)::int from bot.cases) as cases,
                (select count(*)::int from bot.warns where ${ACTIVE_WARN}) as active_warns,
                (select count(*)::int from bot.temp_bans) as temp_bans`,
      );
      const byType = await query('select type, count(*)::int as n from bot.cases group by type');
      return {
        cases: row.cases,
        activeWarns: row.active_warns,
        tempBans: row.temp_bans,
        byType: Object.fromEntries(byType.map((r) => [r.type, r.n])),
      };
    },
  };
  return store;
}
