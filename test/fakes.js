// Minimalne atrapy obiektów discord.js — wystarczające do przetestowania logiki kar bez łączenia z Discordem.

let messageCounter = 0;

function fakeUser(id, username, { dmFails = false, bot = false } = {}) {
  return {
    id,
    username,
    bot,
    dms: [],
    displayAvatarURL: () => `https://cdn.discordapp.com/embed/avatars/0.png`,
    async send(payload) {
      if (dmFails) throw new Error('Cannot send messages to this user');
      const message = { payload, deleted: false, async delete() { message.deleted = true; } };
      this.dms.push(message);
      return message;
    },
  };
}

function fakeChannel(id) {
  return {
    id,
    sent: [],
    isTextBased: () => true,
    toString: () => `<#${id}>`,
    async send(payload) {
      messageCounter += 1;
      const message = { id: `msg${messageCounter}`, url: `https://discord.com/channels/g/${id}/msg${messageCounter}`, payload };
      this.sent.push(message);
      return message;
    },
  };
}

function fakeMember(user, { position = 1, admin = false, events } = {}) {
  return {
    id: user.id,
    user,
    bannable: true,
    kickable: true,
    moderatable: !admin,
    roles: { highest: { position, comparePositionTo: (other) => position - other.position }, cache: new Map() },
    permissions: { has: () => admin },
    timeoutCalls: [],
    async timeout(ms, reason) {
      events?.push(`timeout:${ms}`);
      this.timeoutCalls.push({ ms, reason });
    },
    async kick(reason) {
      events?.push('kick');
      this.kicked = reason;
    },
    isCommunicationDisabled: () => false,
  };
}

function fakeGuild({ bot, members = [], channels = [], events = [], banError = null } = {}) {
  const guild = {
    id: 'g1',
    name: 'Entuzjaści Hopkostki',
    ownerId: 'owner',
    iconURL: () => null,
    client: { user: bot },
    channels: { cache: new Map(channels.map((c) => [c.id, c])) },
    banned: [],
    members: {
      async ban(id, options) {
        events.push('ban');
        if (banError) throw banError;
        guild.banned.push({ id, options });
      },
      async fetch(id) {
        const member = members.find((m) => m.id === id);
        if (!member) throw new Error('Unknown Member');
        return member;
      },
    },
    bans: {
      async remove(id) {
        events.push('unban');
        guild.banned = guild.banned.filter((b) => b.id !== id);
      },
    },
  };
  return guild;
}

function fakeInteraction(channel, user) {
  return {
    channelId: channel.id,
    channel,
    user,
    deferred: false,
    replied: false,
    ephemeral: false,
    replies: [],
    async deferReply(options = {}) {
      this.deferred = true;
      this.ephemeral = Boolean(options.flags);
    },
    async editReply(payload) {
      this.replies.push(payload);
      return channel.send(payload);
    },
  };
}

module.exports = { fakeUser, fakeChannel, fakeMember, fakeGuild, fakeInteraction };
