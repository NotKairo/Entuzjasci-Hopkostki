// Punkt startowy: logowanie bota, rejestracja komend, harmonogram wygasania i panel konfiguracyjny.

require('dotenv').config({ quiet: true });

const fs = require('node:fs');
const path = require('node:path');
const { Client, GatewayIntentBits, Events, ActivityType } = require('discord.js');
const { loadCommands } = require('./commands');
const { getStore } = require('./lib/db');
const { startScheduler } = require('./lib/scheduler');
const { startPanel } = require('./panel/server');

const token = process.env.DISCORD_TOKEN;
if (!token) {
  console.error('❌ Brak DISCORD_TOKEN. Skopiuj plik .env.example do .env i wklej token bota.');
  process.exit(1);
}
const guildId = process.env.GUILD_ID?.trim() || null;

// Tylko nieuprzywilejowane intenty — nie trzeba nic włączać w Developer Portalu.
const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.GuildModeration],
});

const store = getStore();
const commands = loadCommands();
const context = { commands, guildId };

const eventsDir = path.join(__dirname, 'events');
for (const file of fs.readdirSync(eventsDir).filter((f) => f.endsWith('.js'))) {
  const event = require(path.join(eventsDir, file));
  client.on(event.name, (...args) =>
    Promise.resolve(event.execute(...args, context)).catch((error) => console.error(`[event ${event.name}]`, error)),
  );
}

async function registerCommands(guild) {
  const body = [...commands.values()].map((command) => command.data.toJSON());
  await guild.commands.set(body);
  console.log(`✅ Zarejestrowano ${body.length} komend na serwerze "${guild.name}"`);
}

client.once(Events.ClientReady, async (ready) => {
  console.log(`🤖 Zalogowano jako ${ready.user.tag}`);
  ready.user.setPresence({ activities: [{ name: 'Entuzjaści Hopkostki 🫓', type: ActivityType.Watching }] });

  const guilds = guildId ? [ready.guilds.cache.get(guildId)].filter(Boolean) : [...ready.guilds.cache.values()];
  if (guildId && !guilds.length) {
    console.warn(`⚠️ Bot nie jest na serwerze o ID ${guildId}. Zaproś go linkiem z README.`);
  }
  for (const guild of guilds) {
    await registerCommands(guild).catch((error) => console.error(`❌ Rejestracja komend (${guild.name}):`, error.message));
  }
  startScheduler(ready);
});

client.on(Events.GuildCreate, (guild) => {
  if (!guildId || guild.id === guildId) registerCommands(guild).catch((error) => console.error(error.message));
});

startPanel(client, {
  port: Number(process.env.PANEL_PORT) || 3000,
  host: process.env.PANEL_HOST || '127.0.0.1',
  password: process.env.PANEL_PASSWORD || '',
  guildId,
});

function shutdown() {
  console.log('👋 Zamykanie bota...');
  store.flush();
  client.destroy().finally(() => process.exit(0));
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('unhandledRejection', (error) => console.error('[unhandledRejection]', error));

client.login(token).catch((error) => {
  console.error('❌ Nie udało się zalogować — sprawdź DISCORD_TOKEN w .env:', error.message);
  process.exit(1);
});
