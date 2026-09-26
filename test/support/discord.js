// Atrapa REST API Discorda: zapisuje wywołania i symuluje serwer z rolami, członkami, banami i kanałami.
import { DiscordError } from '../../supabase/functions/hopkostki-bot/lib/rest.js';

export const GUILD = 'g1';
export const ROLES = [
  { id: GUILD, name: '@everyone', position: 0, permissions: '0', color: 0 },
  { id: 'r-member', name: 'Entuzjasta', position: 1, permissions: '0', color: 0xf1c40f },
  { id: 'r-fan', name: 'Fan', position: 2, permissions: '0', color: 0x2ecc71 },
  { id: 'r-bot', name: 'Bot', position: 8, permissions: String((1n << 1n) | (1n << 2n) | (1n << 40n) | (1n << 13n) | (1n << 4n) | (1n << 27n) | (1n << 28n)), color: 0 },
  { id: 'r-mod', name: 'Moderator', position: 5, permissions: String((1n << 2n) | (1n << 1n) | (1n << 40n)), color: 0x3498db },
  { id: 'r-admin', name: 'Admin', position: 9, permissions: String(1n << 3n), color: 0xe74c3c },
];

let counter = 0n;
export const snowflake = (ms = Date.now()) => String((BigInt(ms - 1420070400000) << 22n) | (counter++ % 4096n));

export function fakeDiscord({ dmFails = false, banError = null, endpoint = null } = {}) {
  const users = new Map([
    ['bot', { id: 'bot', username: 'Hopkostki Bot', avatar: null, bot: true }],
    ['mod', { id: 'mod', username: 'dfgbh65', avatar: null }],
    ['target', { id: 'target', username: 'hurownik_og', avatar: null }],
    ['owner', { id: 'owner', username: 'wlasciciel', avatar: null }],
  ]);
  const members = new Map([
    ['bot', { user: users.get('bot'), roles: ['r-bot'] }],
    ['mod', { user: users.get('mod'), roles: ['r-mod'] }],
    ['target', { user: users.get('target'), roles: ['r-member'] }],
    ['owner', { user: users.get('owner'), roles: [] }],
  ]);
  const state = {
    calls: [],
    users,
    members,
    bans: new Map(),
    kicked: [],
    timeouts: [],
    dms: [],
    deletedDms: [],
    channels: new Map(), // id -> [messages]
    reactions: [],
    commands: null,
    endpoint,
    webhook: [],
    overwrites: new Map(),
    moves: [],
    createdChannels: [],
    deletedChannels: [],
    channelEdits: [],
    editedMessages: [],
    removedOverwrites: [],
    appFlags: 0,
  };

  const channelMessages = (id) => {
    if (!state.channels.has(id)) state.channels.set(id, []);
    return state.channels.get(id);
  };
  const postMessage = (channelId, body) => {
    const message = { id: snowflake(), channel_id: channelId, author: users.get('bot'), ...body };
    channelMessages(channelId).push(message);
    return message;
  };
  const notFound = (code) => {
    throw new DiscordError(404, { code, message: 'Unknown' });
  };

  const routes = [
    ['GET', /^\/applications\/@me$/, () => ({ id: 'app', name: 'Hopkostki', verify_key: state.verifyKey ?? 'aa', bot: users.get('bot'), interactions_endpoint_url: state.endpoint, flags: state.appFlags })],
    ['PATCH', /^\/applications\/@me$/, (m, body) => {
      if ('interactions_endpoint_url' in body) state.endpoint = body.interactions_endpoint_url;
      if ('flags' in body) state.appFlags = body.flags;
      return { id: 'app', interactions_endpoint_url: state.endpoint, flags: state.appFlags };
    }],
    ['GET', /^\/guilds\/g1\/members$/, () => [...members.values()].map((m) => ({ joined_at: new Date().toISOString(), pending: false, ...m }))],
    ['GET', /^\/users\/@me\/guilds$/, () => [{ id: GUILD, name: 'Entuzjaści Hopkostki' }]],
    ['GET', /^\/gateway\/bot$/, () => ({ url: 'wss://gateway.example', shards: 1, session_start_limit: { total: 1000, remaining: state.identifyRemaining ?? 1000 } })],
    ['PUT', /^\/applications\/app\/guilds\/g1\/commands$/, (m, body) => {
      state.commands = body;
      return body;
    }],
    ['GET', /^\/guilds\/g1$/, () => ({ id: GUILD, name: 'Entuzjaści Hopkostki', icon: null, owner_id: 'owner', roles: ROLES, approximate_member_count: 1337 })],
    ['GET', /^\/guilds\/g1\/channels$/, () => [
      { id: 'cat', type: 4, name: 'Moderacja', position: 0 },
      { id: 'chan', type: 0, name: 'ogolny', position: 1, parent_id: null },
      { id: 'logs', type: 0, name: 'mod-logi', position: 2, parent_id: 'cat' },
      { id: 'voice', type: 2, name: 'Głosowy', position: 3 },
    ]],
    ['GET', /^\/guilds\/g1\/members\/(\w+)$/, ([, id]) => members.get(id) ?? notFound(10007)],
    ['PUT', /^\/guilds\/g1\/bans\/(\w+)$/, ([, id], body, opts) => {
      if (banError) throw banError;
      state.bans.set(id, { user: users.get(id) ?? { id, username: id }, body, reason: opts.reason });
      members.delete(id);
      return null;
    }],
    ['DELETE', /^\/guilds\/g1\/bans\/(\w+)$/, ([, id]) => (state.bans.delete(id) ? null : notFound(10026))],
    ['GET', /^\/guilds\/g1\/bans$/, () => [...state.bans.values()].map((b) => ({ user: b.user }))],
    ['DELETE', /^\/guilds\/g1\/members\/(\w+)$/, ([, id], body, opts) => {
      state.kicked.push({ id, reason: opts.reason });
      members.delete(id);
      return null;
    }],
    ['PUT', /^\/guilds\/g1\/members\/(\w+)\/roles\/([\w-]+)$/, ([, id, role]) => {
      members.get(id)?.roles.push(role);
      return null;
    }],
    ['DELETE', /^\/guilds\/g1\/members\/(\w+)\/roles\/([\w-]+)$/, ([, id, role]) => {
      const member = members.get(id);
      if (member) member.roles = member.roles.filter((r) => r !== role);
      return null;
    }],
    ['PATCH', /^\/guilds\/g1\/members\/(\w+)$/, ([, id], body) => {
      if ('channel_id' in body) {
        state.moves.push({ id, channel: body.channel_id });
        return members.get(id);
      }
      state.timeouts.push({ id, until: body.communication_disabled_until });
      return { ...members.get(id), communication_disabled_until: body.communication_disabled_until };
    }],
    ['POST', /^\/guilds\/g1\/channels$/, (m, body) => {
      const channel = { id: snowflake(), guild_id: GUILD, permission_overwrites: [], ...body };
      state.createdChannels.push(channel);
      return channel;
    }],
    ['GET', /^\/users\/(\w+)$/, ([, id]) => users.get(id) ?? notFound(10013)],
    ['POST', /^\/users\/@me\/channels$/, (m, body) => {
      if (dmFails) throw new DiscordError(403, { code: 50007, message: 'Cannot send messages to this user' });
      return { id: `dm-${body.recipient_id}` };
    }],
    ['POST', /^\/channels\/(dm-\w+)\/messages$/, ([, id], body) => {
      const message = { id: snowflake(), channel_id: id, ...body };
      state.dms.push(message);
      return message;
    }],
    ['DELETE', /^\/channels\/(dm-\w+)\/messages\/(\d+)$/, ([, , id]) => {
      state.deletedDms.push(id);
      return null;
    }],
    ['POST', /^\/channels\/([\w-]+)\/messages\/bulk-delete$/, ([, channel], body) => {
      state.channels.set(channel, channelMessages(channel).filter((m) => !body.messages.includes(m.id)));
      return null;
    }],
    ['POST', /^\/channels\/([\w-]+)\/messages$/, ([, channel], body) => postMessage(channel, body)],
    ['DELETE', /^\/channels\/([\w-]+)\/messages\/(\d+)$/, ([, channel, id]) => {
      state.channels.set(channel, channelMessages(channel).filter((m) => m.id !== id));
      return null;
    }],
    ['GET', /^\/channels\/([\w-]+)\/messages$/, ([, channel], body, opts) => {
      const after = opts.query?.after ? BigInt(opts.query.after) : 0n;
      return channelMessages(channel)
        .filter((m) => BigInt(m.id) > after)
        .slice(0, Number(opts.query?.limit ?? 50))
        .reverse();
    }],
    ['PUT', /^\/channels\/([\w-]+)\/messages\/(\d+)\/reactions\/([^/]+)\/@me$/, ([, channel, id, emoji]) => {
      state.reactions.push({ channel, id, emoji: decodeURIComponent(emoji) });
      const message = channelMessages(channel).find((m) => m.id === id);
      if (message) message.reactions = [{ me: true, emoji: { name: decodeURIComponent(emoji), id: null } }];
      return null;
    }],
    ['GET', /^\/channels\/([\w-]+)$/, ([, id]) => (state.deletedChannels.includes(id) ? notFound(10003) : { id, permission_overwrites: state.overwrites.has(id) ? [state.overwrites.get(id)] : [] })],
    ['PUT', /^\/channels\/([\w-]+)\/permissions\/(\w+)$/, ([, id, target], body) => {
      state.overwrites.set(id, { id: target, ...body });
      return null;
    }],
    ['DELETE', /^\/channels\/([\w-]+)\/permissions\/(\w+)$/, ([, id, target]) => {
      state.removedOverwrites.push({ id, target });
      return null;
    }],
    ['PATCH', /^\/channels\/([\w-]+)\/messages\/(\d+)$/, ([, channel, id], body) => {
      state.editedMessages.push({ channel, id, body });
      return { id, channel_id: channel, ...body };
    }],
    ['PATCH', /^\/channels\/([\w-]+)$/, ([, id], body) => {
      state.channelEdits.push({ id, body });
      return { id, ...body };
    }],
    ['DELETE', /^\/channels\/([\w-]+)$/, ([, id]) => {
      state.deletedChannels.push(id);
      return null;
    }],
    ['PATCH', /^\/webhooks\/app\/([\w-]+)\/messages\/@original$/, ([, token], body) => {
      state.webhook.push({ op: 'edit', token, body });
      return postMessage('chan', body);
    }],
    ['DELETE', /^\/webhooks\/app\/([\w-]+)\/messages\/@original$/, ([, token]) => {
      state.webhook.push({ op: 'delete', token });
      return null;
    }],
    ['POST', /^\/webhooks\/app\/([\w-]+)$/, ([, token], body) => {
      state.webhook.push({ op: 'followup', token, body });
      return { id: snowflake(), channel_id: 'chan', ...body };
    }],
  ];

  async function request(method, path, opts = {}) {
    state.calls.push({ method, path, body: opts.body, reason: opts.reason, query: opts.query, files: opts.files });
    for (const [m, pattern, handler] of routes) {
      const match = m === method && pattern.exec(path);
      if (match) return structuredClone(handler(match, opts.body, opts) ?? null);
    }
    throw new Error(`Brak atrapy dla ${method} ${path}`);
  }

  return {
    state,
    request,
    get: (p, o) => request('GET', p, o),
    post: (p, body, o) => request('POST', p, { ...o, body }),
    put: (p, body, o) => request('PUT', p, { ...o, body }),
    patch: (p, body, o) => request('PATCH', p, { ...o, body }),
    delete: (p, o) => request('DELETE', p, o),
  };
}

export function makeBot({ store, discord, env = {} }) {
  return {
    env: { token: 'x', guildId: '', publicKey: '', panelPassword: 'tajne', selfUrl: 'https://example.supabase.co/functions/v1/hopkostki-bot', ...env },
    store,
    discord,
    cache: new Map(),
  };
}

// Interakcja slash tak, jak wysyła ją Discord.
export function commandPayload(name, options = [], { invoker = 'mod', roles = ['r-mod'], permissions = String((1n << 2n) | (1n << 1n) | (1n << 40n)), resolvedIds = ['target'], members } = {}) {
  const users = { bot: { id: 'bot', username: 'Hopkostki Bot', bot: true }, mod: { id: 'mod', username: 'dfgbh65' }, target: { id: 'target', username: 'hurownik_og', avatar: null }, owner: { id: 'owner', username: 'wlasciciel' } };
  const resolvedMembers = members ?? { target: { roles: ['r-member'] }, mod: { roles: ['r-mod'] }, owner: { roles: [] } };
  return {
    type: 2,
    id: 'i1',
    application_id: 'app',
    token: `tok-${name}`,
    guild_id: GUILD,
    channel_id: 'chan',
    member: { user: users[invoker], roles, permissions },
    data: {
      name,
      options,
      resolved: {
        users: Object.fromEntries(resolvedIds.map((id) => [id, users[id]])),
        members: Object.fromEntries(resolvedIds.filter((id) => resolvedMembers[id]).map((id) => [id, resolvedMembers[id]])),
      },
    },
  };
}
