const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType } = require('discord.js');
const { UNIT_CHOICES } = require('../lib/duration');
const { checkTarget, banUser } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

module.exports = {
  permission: PermissionFlagsBits.BanMembers,
  data: new SlashCommandBuilder()
    .setName('ban')
    .setDescription('Banuje użytkownika na określony czas lub na zawsze')
    .setDefaultMemberPermissions(PermissionFlagsBits.BanMembers)
    .setContexts(InteractionContextType.Guild)
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Kogo zbanować').setRequired(true))
    .addStringOption((o) => o.setName('powod').setDescription('Powód bana').setRequired(true).setMaxLength(500))
    .addIntegerOption((o) =>
      o.setName('czas').setDescription('Na ile (puste = ban permanentny)').setMinValue(1).setMaxValue(1000),
    )
    .addStringOption((o) =>
      o.setName('jednostka').setDescription('Jednostka czasu (domyślnie dni)').addChoices(...UNIT_CHOICES),
    )
    .addIntegerOption((o) =>
      o
        .setName('usun_wiadomosci')
        .setDescription('Usunąć ostatnie wiadomości użytkownika?')
        .addChoices(
          { name: 'Nie usuwaj', value: 0 },
          { name: 'Z ostatniej godziny', value: 3600 },
          { name: 'Z ostatnich 24 godzin', value: 86400 },
          { name: 'Z ostatnich 7 dni', value: 604800 },
        ),
    ),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik', true);
    const targetMember = interaction.options.getMember('uzytkownik');
    const amount = interaction.options.getInteger('czas');
    const unit = interaction.options.getString('jednostka') ?? 'd';

    const problem = checkTarget({
      guild: interaction.guild,
      moderatorMember: interaction.member,
      target,
      targetMember,
      action: 'ban',
    });
    if (problem) return replyError(interaction, problem);

    await banUser({
      interaction,
      guild: interaction.guild,
      target,
      moderator: interaction.user,
      reason: interaction.options.getString('powod', true),
      duration: amount ? { amount, unit } : null,
      deleteMessageSeconds: interaction.options.getInteger('usun_wiadomosci') ?? 0,
    });
  },
};
