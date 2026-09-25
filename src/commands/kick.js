const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType } = require('discord.js');
const { checkTarget, kickUser } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

module.exports = {
  permission: PermissionFlagsBits.KickMembers,
  data: new SlashCommandBuilder()
    .setName('kick')
    .setDescription('Wyrzuca użytkownika z serwera')
    .setDefaultMemberPermissions(PermissionFlagsBits.KickMembers)
    .setContexts(InteractionContextType.Guild)
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Kogo wyrzucić').setRequired(true))
    .addStringOption((o) => o.setName('powod').setDescription('Powód wyrzucenia').setRequired(true).setMaxLength(500)),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik', true);
    const targetMember = interaction.options.getMember('uzytkownik');

    const problem = checkTarget({
      guild: interaction.guild,
      moderatorMember: interaction.member,
      target,
      targetMember,
      action: 'kick',
    });
    if (problem) return replyError(interaction, problem);

    await kickUser({
      interaction,
      guild: interaction.guild,
      target,
      targetMember,
      moderator: interaction.user,
      reason: interaction.options.getString('powod', true),
    });
  },
};
