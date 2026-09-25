const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType } = require('discord.js');
const { UNIT_CHOICES, MAX_TIMEOUT_MS, toMs } = require('../lib/duration');
const { checkTarget, timeoutUser } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

module.exports = {
  permission: PermissionFlagsBits.ModerateMembers,
  data: new SlashCommandBuilder()
    .setName('timeout')
    .setDescription('Wycisza użytkownika na określony czas (maks. 28 dni)')
    .setDefaultMemberPermissions(PermissionFlagsBits.ModerateMembers)
    .setContexts(InteractionContextType.Guild)
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Kogo wyciszyć').setRequired(true))
    .addIntegerOption((o) => o.setName('czas').setDescription('Na ile').setRequired(true).setMinValue(1).setMaxValue(40320))
    .addStringOption((o) =>
      o.setName('jednostka').setDescription('Jednostka czasu').setRequired(true).addChoices(...UNIT_CHOICES),
    )
    .addStringOption((o) => o.setName('powod').setDescription('Powód wyciszenia').setRequired(true).setMaxLength(500)),

  async execute(interaction) {
    const target = interaction.options.getUser('uzytkownik', true);
    const targetMember = interaction.options.getMember('uzytkownik');
    const duration = {
      amount: interaction.options.getInteger('czas', true),
      unit: interaction.options.getString('jednostka', true),
    };

    if (toMs(duration.amount, duration.unit) > MAX_TIMEOUT_MS) {
      return replyError(interaction, 'Discord pozwala na timeout maksymalnie **28 dni** (4 tygodnie). Na dłużej użyj `/ban`.');
    }

    const problem = checkTarget({
      guild: interaction.guild,
      moderatorMember: interaction.member,
      target,
      targetMember,
      action: 'timeout',
    });
    if (problem) return replyError(interaction, problem);

    await timeoutUser({
      interaction,
      guild: interaction.guild,
      target,
      targetMember,
      moderator: interaction.user,
      reason: interaction.options.getString('powod', true),
      duration,
    });
  },
};
