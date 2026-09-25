const { Events } = require('discord.js');
const { hasModAccess, describeError, ActionError } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

module.exports = {
  name: Events.InteractionCreate,
  async execute(interaction, { commands, guildId }) {
    if (!interaction.inGuild()) return;
    if (guildId && interaction.guildId !== guildId) return;

    const command = commands.get(interaction.commandName);
    if (!command) return;

    if (interaction.isAutocomplete()) {
      if (!command.autocomplete || !hasModAccess(interaction.member, command.permission)) return interaction.respond([]);
      return command.autocomplete(interaction).catch((error) => console.warn(`[autocomplete] ${error.message}`));
    }
    if (!interaction.isChatInputCommand()) return;

    if (command.permission && !hasModAccess(interaction.member, command.permission)) {
      return replyError(interaction, 'Nie masz uprawnień do tej komendy.');
    }

    try {
      await command.execute(interaction);
    } catch (error) {
      if (!(error instanceof ActionError)) console.error(`[/${interaction.commandName}]`, error);
      await replyError(interaction, describeError(error));
    }
  },
};
