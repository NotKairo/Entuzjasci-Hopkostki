// Prawdziwy Postgres w pamięci (PGlite) z migracją schematu bota — testy sprawdzają faktyczne SQL.
// Jedna instancja na plik testowy; przed każdym testem tabele są czyszczone.
import fs from 'node:fs';
import { PGlite } from '@electric-sql/pglite';
import { createStore } from '../../supabase/functions/hopkostki-bot/lib/store.js';

const SCHEMA = new URL('../../supabase/migrations/20260925160000_bot_schema.sql', import.meta.url);
// Z migracji notatek bierzemy tylko DDL tabel (harmonogramy pg_cron nie istnieją w PGlite).
const NOTES = new URL('../../supabase/migrations/20260925213000_bot_notes_gateway.sql', import.meta.url);
const withoutCron = (sql) => sql.split(/^select cron\.schedule/m)[0];
const withoutGrants = (sql) => sql.replace(/revoke .*? from anon, authenticated;/g, '');
let shared = null;

async function database() {
  if (!shared) {
    shared = (async () => {
      const db = new PGlite();
      await db.exec(withoutGrants(fs.readFileSync(SCHEMA, 'utf8')));
      await db.exec(withoutGrants(withoutCron(fs.readFileSync(NOTES, 'utf8'))));
      return db;
    })();
  }
  return shared;
}

export async function createTestStore() {
  const db = await database();
  await db.exec(`
    truncate bot.warns, bot.cases, bot.temp_bans, bot.mod_messages, bot.channel_cursors, bot.notes restart identity cascade;
    update bot.config set data = '{}'::jsonb;
    delete from bot.state where key not in ('cron_secret', 'cron_lock');
    update bot.state set value = to_jsonb('1970-01-01T00:00:00Z'::text) where key = 'cron_lock';
  `);
  const store = createStore(async (text, params) => (await db.query(text, emulatePostgresJs(text, params ?? []))).rows);
  return { db, store };
}

// postgres.js (sterownik w funkcji Edge) serializuje parametr rzutowany wprost na jsonb przez JSON.stringify,
// więc przekazany napis JSON zostałby zapisany jako napis, a nie obiekt. PGlite tak nie robi —
// odtwarzamy to zachowanie, żeby testy wyłapywały takie błędy.
function emulatePostgresJs(text, params) {
  const jsonbParams = new Set([...text.matchAll(/\$(\d+)::jsonb/g)].map((m) => Number(m[1]) - 1));
  return params.map((value, i) => (jsonbParams.has(i) && value !== null ? JSON.stringify(value) : value));
}
