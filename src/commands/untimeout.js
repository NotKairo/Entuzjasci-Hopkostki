const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType } = require('discord.js');
const { checkTarget, untimeoutUser } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

module.exports = {
  permission: PermissionFlagsBits.ModerateMembers,
  data: new SlashCommandBuilder()
    .setName('untimeout')
    .setDescription('Zdejmuje timeout z użytkownika')
    .setDefaultMemberPermissions(PermissionFlagsBits.ModerateMembers)
    .setContexts(InteractionContextType.Guild)
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Komu zdjąć timeout').setRequired(true))
    .addStringOption((o) => o.setName('powod').setDescription('Powód').setMaxLength(500)),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik', true);
    const targetMember = interaction.options.getMember('uzytkownik');

    const problem = checkTarget({
      guild: interaction.guild,
      moderatorMember: interaction.member,
      target,
      targetMember,
      action: 'untimeout',
    });
    if (problem) return replyError(interaction, problem);
    if (!targetMember.isCommunicationDisabled()) return replyError(interaction, 'Ten użytkownik nie ma timeoutu.');

    await untimeoutUser({
      interaction,
      guild: interaction.guild,
      target,
      targetMember,
      moderator: interaction.user,
      reason: interaction.options.getString('powod') ?? 'Nie podano powodu',
    });
  },
};
