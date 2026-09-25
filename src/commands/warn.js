const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType, EmbedBuilder, MessageFlags } = require('discord.js');
const { getStore } = require('../lib/db');
const { checkTarget, warnUser, sendModLog } = require('../lib/moderation');
const { formatDuration, discordTimestamp } = require('../lib/duration');
const { replyError } = require('../lib/respond');
const { simpleEmbed, successEmbed, severityBar, colorInt } = require('../lib/embeds');

const ESCALATION_NAMES = { alert: 'powiadomienie moderacji', timeout: 'timeout', kick: 'kick', ban: 'ban' };
const CASE_NAMES = { ban: 'bany', kick: 'kicki', timeout: 'timeouty', warn: 'ostrzeżenia (łącznie)' };

function describeRule(rule) {
  const name = ESCALATION_NAMES[rule.action];
  if (rule.action === 'alert' || rule.action === 'kick') return name;
  return rule.amount > 0 ? `${name} na ${formatDuration(rule.amount, rule.unit)}` : `${name} permanentny`;
}

function statusEmbed(user) {
  const store = getStore();
  const { escalation, warns: warnConfig, actions } = store.config;
  const summary = store.warnSummary(user.id);
  const counts = store.caseCounts(user.id);
  const topRule = escalation.rules.at(-1);
  const scale = topRule?.points ?? 10;
  const nextRule = escalation.enabled ? escalation.rules.find((r) => r.points > summary.points) : null;

  const lines = [
    `**Użytkownik:** <@${user.id}> (\`${user.id}\`)`,
    `**Aktywne ostrzeżenia:** ${summary.count}`,
    `**Punkty:** **${summary.points} pkt**`,
    `**Poziom:** ${severityBar(summary.points, scale)} \`${summary.points}/${scale}\``,
  ];
  if (nextRule) lines.push(`**Następny próg:** ${nextRule.points} pkt → ${describeRule(nextRule)}`);
  if (summary.nextExpiry) lines.push(`**Najbliższe wygaśnięcie:** ${discordTimestamp(summary.nextExpiry, 'R')}`);
  const history = Object.entries(CASE_NAMES)
    .filter(([type]) => counts[type])
    .map(([type, name]) => `${name}: **${counts[type]}**`);
  if (history.length) lines.push(`**Historia kar:** ${history.join(' · ')}`);
  if (warnConfig.expiryDays > 0) lines.push(`-# Ostrzeżenia wygasają automatycznie po ${warnConfig.expiryDays} dniach.`);

  const embed = new EmbedBuilder()
    .setColor(colorInt(actions.warn.color))
    .setTitle(`⚠️ Ostrzeżenia — ${user.username}`)
    .setThumbnail(user.displayAvatarURL())
    .setDescription(lines.join('\n'))
    .setFooter({ text: 'Widoczne tylko dla moderacji' });

  for (const warn of summary.warns.slice(0, 10)) {
    const expiry = warn.expiresAt ? `⏳ wygasa ${discordTimestamp(warn.expiresAt, 'R')}` : '♾️ nie wygasa';
    embed.addFields({
      name: `#${warn.id} • ${warn.points} pkt • ${new Date(warn.createdAt).toLocaleDateString('pl-PL')}`,
      value: `${warn.reason.slice(0, 200)}\n-# Moderator: <@${warn.moderatorId}> • ${expiry}`,
    });
  }
  if (summary.warns.length > 10) embed.addFields({ name: '…', value: `Oraz ${summary.warns.length - 10} starszych (pełna lista w panelu).` });
  if (!summary.warns.length) embed.addFields({ name: 'Brak aktywnych ostrzeżeń', value: 'Ten użytkownik jest czysty. ✨' });
  return embed;
}

module.exports = {
  permission: PermissionFlagsBits.ModerateMembers,
  data: new SlashCommandBuilder()
    .setName('warn')
    .setDescription('System ostrzeżeń')
    .setDefaultMemberPermissions(PermissionFlagsBits.ModerateMembers)
    .setContexts(InteractionContextType.Guild)
    .addSubcommand((s) =>
      s
        .setName('dodaj')
        .setDescription('Daje użytkownikowi ostrzeżenie')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Kogo ostrzec').setRequired(true))
        .addStringOption((o) => o.setName('powod').setDescription('Powód ostrzeżenia').setRequired(true).setMaxLength(500))
        .addIntegerOption((o) =>
          o.setName('punkty').setDescription('Ile punktów (domyślnie z konfiguracji)').setMinValue(1).setMaxValue(100),
        ),
    )
    .addSubcommand((s) =>
      s
        .setName('status')
        .setDescription('Pokazuje ostrzeżenia i punkty użytkownika (tylko dla moderacji)')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Czyje ostrzeżenia').setRequired(true)),
    )
    .addSubcommand((s) =>
      s
        .setName('usun')
        .setDescription('Usuwa jedno ostrzeżenie po numerze')
        .addIntegerOption((o) => o.setName('numer').setDescription('Numer ostrzeżenia (#)').setRequired(true).setMinValue(1))
        .addStringOption((o) => o.setName('powod').setDescription('Dlaczego usuwasz').setMaxLength(500)),
    )
    .addSubcommand((s) =>
      s
        .setName('wyczysc')
        .setDescription('Usuwa wszystkie ostrzeżenia użytkownika')
        .addUserOption((o) => o.setName('uzytkownik').setDescription('Czyje ostrzeżenia wyczyścić').setRequired(true)),
    )
    .addSubcommand((s) => s.setName('ranking').setDescription('Użytkownicy z największą liczbą punktów')),

  async execute(interaction) {
    const store = getStore();
    const sub = interaction.options.getSubcommand();

    if (sub === 'dodaj') {
      const target = interaction.options.getUser('uzytkownik', true);
      const targetMember = interaction.options.getMember('uzytkownik');
      const problem = checkTarget({
        guild: interaction.guild,
        moderatorMember: interaction.member,
        target,
        targetMember,
        action: 'warn',
      });
      if (problem) return replyError(interaction, problem);
      if (target.bot) return replyError(interaction, 'Botów nie można ostrzegać.');

      return warnUser({
        interaction,
        guild: interaction.guild,
        target,
        moderator: interaction.user,
        reason: interaction.options.getString('powod', true),
        points: interaction.options.getInteger('punkty') ?? store.config.warns.defaultPoints,
      });
    }

    if (sub === 'status') {
      const user = interaction.options.getUser('uzytkownik', true);
      return interaction.reply({ embeds: [statusEmbed(user)], flags: MessageFlags.Ephemeral });
    }

    if (sub === 'usun') {
      const id = interaction.options.getInteger('numer', true);
      const warn = store.removeWarn(id);
      if (!warn) return replyError(interaction, `Nie ma aktywnego ostrzeżenia **#${id}**.`);
      const reason = interaction.options.getString('powod') ?? 'Nie podano powodu';
      store.updateCase(warn.caseId, { note: `Ostrzeżenie usunięte przez ${interaction.user.username}: ${reason}` });
      const left = store.warnSummary(warn.userId);
      await sendModLog(interaction.guild, {
        embeds: [
          simpleEmbed(
            'success',
            `🗑️ Usunięto ostrzeżenie #${warn.id}`,
            [
              `**Użytkownik:** <@${warn.userId}>`,
              `**Usunął:** <@${interaction.user.id}>`,
              `**Powód usunięcia:** ${reason}`,
              `**Treść ostrzeżenia:** ${warn.reason} (${warn.points} pkt)`,
              `**Pozostało:** ${left.count} ostrzeżeń (${left.points} pkt)`,
            ].join('\n'),
          ),
        ],
      });
      return interaction.reply({
        embeds: [successEmbed(`Usunięto ostrzeżenie **#${warn.id}** użytkownika <@${warn.userId}> (-${warn.points} pkt).\nPozostało: **${left.points} pkt**.`)],
        flags: MessageFlags.Ephemeral,
      });
    }

    if (sub === 'wyczysc') {
      const user = interaction.options.getUser('uzytkownik', true);
      const removed = store.clearWarns(user.id);
      if (!removed.length) return replyError(interaction, `<@${user.id}> nie ma żadnych ostrzeżeń.`);
      const points = removed.reduce((sum, w) => sum + w.points, 0);
      for (const warn of removed) store.updateCase(warn.caseId, { note: `Wyczyszczone przez ${interaction.user.username}` });
      await sendModLog(interaction.guild, {
        embeds: [
          simpleEmbed(
            'success',
            '🧹 Wyczyszczono ostrzeżenia',
            `**Użytkownik:** <@${user.id}>\n**Usunął:** <@${interaction.user.id}>\n**Usunięto:** ${removed.length} ostrzeżeń (${points} pkt)`,
          ),
        ],
      });
      return interaction.reply({
        embeds: [successEmbed(`Usunięto **${removed.length}** ostrzeżeń (${points} pkt) użytkownika <@${user.id}>.`)],
        flags: MessageFlags.Ephemeral,
      });
    }

    if (sub === 'ranking') {
      const rows = store.warnRanking().slice(0, 15);
      const description = rows.length
        ? rows
            .map((row, i) => {
              const next = row.warns.map((w) => w.expiresAt).filter(Boolean).sort((a, b) => a - b)[0];
              const expiry = next ? ` • najbliższe wygasa ${discordTimestamp(next, 'R')}` : '';
              return `**${i + 1}.** <@${row.userId}> — **${row.points} pkt** (${row.count} ostrz.)${expiry}`;
            })
            .join('\n')
        : 'Nikt nie ma aktywnych ostrzeżeń. 🎉';
      return interaction.reply({
        embeds: [simpleEmbed('warning', '📊 Ranking ostrzeżeń', description).setFooter({ text: 'Widoczne tylko dla moderacji' })],
        flags: MessageFlags.Ephemeral,
      });
    }
  },
};
