// Panel konfiguracyjny na http://localhost:3000 (Express + statyczny frontend w ./public).
// Domyślnie nasłuchuje tylko na 127.0.0.1, więc jest dostępny wyłącznie z Twojego komputera.

const path = require('node:path');
const crypto = require('node:crypto');
const express = require('express');
const { ChannelType } = require('discord.js');
const { DEFAULT_CONFIG } = require('../config/defaults');
const { getStore } = require('../lib/db');
const { unbanUser, sendModLog, describeError } = require('../lib/moderation');
const { simpleEmbed } = require('../lib/embeds');

const COOKIE = 'hopkostki_panel';
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]']);

function parseCookies(header = '') {
  return Object.fromEntries(
    header
      .split(';')
      .map((part) => part.trim().split('='))
      .filter(([key]) => key)
      .map(([key, ...value]) => [key, decodeURIComponent(value.join('='))]),
  );
}

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function createApp(client, { password = '', guildId = null, host = '127.0.0.1' } = {}) {
  const app = express();
  const store = getStore();
  const sessions = new Set();

  const getGuild = () => (guildId ? client.guilds.cache.get(guildId) : client.guilds.cache.first()) ?? null;

  app.disable('x-powered-by');
  app.use(express.json({ limit: '200kb' }));

  // Ochrona przed DNS rebinding: przy nasłuchu lokalnym akceptujemy tylko lokalne nagłówki Host.
  app.use((req, res, next) => {
    if (!LOCAL_HOSTS.has(host)) return next();
    const hostname = (req.hostname || '').toLowerCase();
    if (LOCAL_HOSTS.has(hostname) || hostname === '::1') return next();
    return res.status(403).send('Niedozwolony host');
  });

  app.use(express.static(path.join(__dirname, 'public')));

  const isLoggedIn = (req) => !password || sessions.has(parseCookies(req.headers.cookie)[COOKIE]);

  app.get('/api/auth', (req, res) => res.json({ required: Boolean(password), loggedIn: isLoggedIn(req) }));

  app.post('/api/login', (req, res) => {
    if (!password) return res.json({ ok: true });
    if (!safeEqual(req.body?.password ?? '', password)) return res.status(401).json({ error: 'Złe hasło' });
    const token = crypto.randomBytes(32).toString('hex');
    sessions.add(token);
    res.setHeader('Set-Cookie', `${COOKIE}=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800`);
    return res.json({ ok: true });
  });

  app.post('/api/logout', (req, res) => {
    sessions.delete(parseCookies(req.headers.cookie)[COOKIE]);
    res.setHeader('Set-Cookie', `${COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`);
    res.json({ ok: true });
  });

  // Wszystko poniżej wymaga zalogowania; zapisy wymagają nagłówka panelu (blokuje żądania z obcych stron).
  app.use('/api', (req, res, next) => {
    if (!isLoggedIn(req)) return res.status(401).json({ error: 'Zaloguj się' });
    if (req.method !== 'GET' && req.get('x-panel') !== '1') return res.status(403).json({ error: 'Brak nagłówka panelu' });
    return next();
  });

  app.get('/api/status', (req, res) => {
    const guild = getGuild();
    res.json({
      ready: client.isReady(),
      bot: client.user ? { tag: client.user.tag, id: client.user.id, avatar: client.user.displayAvatarURL({ size: 128 }) } : null,
      ping: client.ws.ping,
      uptime: client.uptime,
      guild: guild
        ? { id: guild.id, name: guild.name, icon: guild.iconURL({ size: 128 }), memberCount: guild.memberCount }
        : null,
      stats: store.stats(),
      recent: store.listCases({ limit: 8 }).items,
    });
  });

  app.get('/api/guild', (req, res) => {
    const guild = getGuild();
    if (!guild) return res.json({ channels: [], roles: [] });
    const channels = guild.channels.cache
      .filter((c) => [ChannelType.GuildText, ChannelType.GuildAnnouncement].includes(c.type))
      .sort((a, b) => a.rawPosition - b.rawPosition)
      .map((c) => ({ id: c.id, name: c.name, category: c.parent?.name ?? null }));
    const roles = guild.roles.cache
      .filter((r) => r.id !== guild.id && !r.managed)
      .sort((a, b) => b.position - a.position)
      .map((r) => ({ id: r.id, name: r.name, color: r.hexColor }));
    return res.json({ channels, roles });
  });

  app.get('/api/config', (req, res) => res.json({ config: store.config, defaults: DEFAULT_CONFIG }));

  app.put('/api/config', (req, res) => res.json({ config: store.updateConfig(req.body ?? {}) }));

  app.get('/api/cases', (req, res) => {
    const limit = Math.min(Number(req.query.limit) || 25, 100);
    const page = Math.max(Number(req.query.page) || 1, 1);
    const user = String(req.query.user ?? '').trim().toLowerCase();
    const type = String(req.query.type ?? '') || undefined;

    let result;
    if (/^\d{15,25}$/.test(user) || !user) {
      result = store.listCases({ userId: user || undefined, type, limit, offset: (page - 1) * limit });
    } else {
      // Szukanie po nicku.
      const all = store.listCases({ type, limit: Number.MAX_SAFE_INTEGER }).items.filter(
        (c) => c.userTag?.toLowerCase().includes(user) || c.moderatorTag?.toLowerCase().includes(user),
      );
      result = { total: all.length, items: all.slice((page - 1) * limit, page * limit) };
    }
    res.json({ ...result, page, limit });
  });

  app.get('/api/warns', (req, res) => res.json({ users: store.warnRanking(), now: Date.now() }));

  app.delete('/api/warns/:id', async (req, res) => {
    const warn = store.removeWarn(Number(req.params.id));
    if (!warn) return res.status(404).json({ error: 'Nie ma takiego ostrzeżenia' });
    store.updateCase(warn.caseId, { note: 'Ostrzeżenie usunięte w panelu' });
    const guild = client.guilds.cache.get(warn.guildId);
    if (guild) {
      await sendModLog(guild, {
        embeds: [
          simpleEmbed(
            'success',
            `🗑️ Usunięto ostrzeżenie #${warn.id} (panel)`,
            `**Użytkownik:** <@${warn.userId}>\n**Treść:** ${warn.reason} (${warn.points} pkt)`,
          ),
        ],
      });
    }
    return res.json({ ok: true });
  });

  app.get('/api/tempbans', (req, res) => res.json({ bans: store.listTempBans(), now: Date.now() }));

  app.post('/api/tempbans/:userId/unban', async (req, res) => {
    const ban = store.listTempBans().find((b) => b.userId === req.params.userId);
    if (!ban) return res.status(404).json({ error: 'Nie ma takiego bana' });
    const guild = client.guilds.cache.get(ban.guildId);
    const target = await client.users.fetch(ban.userId).catch(() => null);
    if (!guild || !target || !client.user) return res.status(409).json({ error: 'Bot nie jest połączony z serwerem' });
    try {
      await unbanUser({ guild, target, moderator: client.user, reason: 'Ban zdjęty w panelu', announce: false });
      return res.json({ ok: true });
    } catch (error) {
      store.removeTempBan(ban.guildId, ban.userId);
      return res.status(400).json({ error: describeError(error) });
    }
  });

  app.use('/api', (req, res) => res.status(404).json({ error: 'Nie znaleziono' }));
  return app;
}

function startPanel(client, { port = 3000, host = '127.0.0.1', password = '', guildId = null } = {}) {
  if (!LOCAL_HOSTS.has(host) && !password) {
    console.warn('⚠️ Panel nasłuchuje poza localhost BEZ hasła! Ustaw PANEL_PASSWORD w .env.');
  }
  const app = createApp(client, { password, guildId, host });
  const server = app.listen(port, host, () => {
    const shown = host === '0.0.0.0' ? 'localhost' : host === '127.0.0.1' ? 'localhost' : host;
    console.log(`🖥️  Panel konfiguracyjny: http://${shown}:${port}`);
  });
  server.on('error', (error) => console.error(`❌ Panel nie wystartował (port ${port}): ${error.message}`));
  return server;
}

module.exports = { createApp, startPanel };
