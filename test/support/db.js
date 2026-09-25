// Prawdziwy Postgres w pamięci (PGlite) z migracją schematu bota — testy sprawdzają faktyczne SQL.
// Jedna instancja na plik testowy; przed każdym testem tabele są czyszczone.
import fs from 'node:fs';
import { PGlite } from '@electric-sql/pglite';
import { createStore } from '../../supabase/functions/hopkostki-bot/lib/store.js';

const SCHEMA = new URL('../../supabase/migrations/20260925160000_bot_schema.sql', import.meta.url);
let shared = null;

async function database() {
  if (!shared) {
    shared = (async () => {
      const db = new PGlite();
      await db.exec(fs.readFileSync(SCHEMA, 'utf8').replace(/revoke .*? from anon, authenticated;/g, ''));
      return db;
    })();
  }
  return shared;
}

export async function createTestStore() {
  const db = await database();
  await db.exec(`
    truncate bot.warns, bot.cases, bot.temp_bans, bot.mod_messages, bot.channel_cursors restart identity cascade;
    update bot.config set data = '{}'::jsonb;
    delete from bot.state where key not in ('cron_secret', 'cron_lock');
    update bot.state set value = to_jsonb('1970-01-01T00:00:00Z'::text) where key = 'cron_lock';
  `);
  const store = createStore(async (text, params) => (await db.query(text, params ?? [])).rows);
  return { db, store };
}
