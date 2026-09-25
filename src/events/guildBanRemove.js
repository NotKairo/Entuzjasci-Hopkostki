// Gdy ktoś zdejmie bana ręcznie (np. w ustawieniach serwera), usuwamy go z listy tymczasowych banów.

const { Events } = require('discord.js');
const { getStore } = require('../lib/db');

module.exports = {
  name: Events.GuildBanRemove,
  execute(ban) {
    getStore().removeTempBan(ban.guild.id, ban.user.id);
  },
};
