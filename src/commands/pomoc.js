const { SlashCommandBuilder, InteractionContextType, EmbedBuilder, MessageFlags } = require('discord.js');
const { getStore } = require('../lib/db');
const { COLORS } = require('../lib/embeds');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('pomoc')
    .setDescription('Lista komend bota moderacyjnego')
    .setContexts(InteractionContextType.Guild),

  async execute(interaction) {
    const { warns } = getStore().config;
    const embed = new EmbedBuilder()
      .setColor(COLORS.info)
      .setTitle('🫓 Bot moderacyjny — Entuzjaści Hopkostki')
      .setDescription('Jednostki czasu: **minuty, godziny, dni, tygodnie, miesiące** — wybierasz je z listy przy komendzie.')
      .addFields(
        {
          name: '🔨 Kary',
          value: [
            '`/ban` — ban na czas lub permanentny (+ opcjonalne usuwanie wiadomości)',
            '`/unban` — zdjęcie bana (lista zbanowanych w podpowiedziach)',
            '`/timeout` — wyciszenie na czas (maks. 28 dni)',
            '`/untimeout` — zdjęcie wyciszenia',
            '`/kick` — wyrzucenie z serwera',
          ].join('\n'),
        },
        {
          name: '⚠️ Ostrzeżenia (punkty widzi tylko moderacja)',
          value: [
            '`/warn dodaj` — ostrzeżenie z punktami',
            '`/warn status` — ile ostrzeżeń i punktów ma osoba',
            '`/warn usun` · `/warn wyczysc` — usuwanie ostrzeżeń',
            '`/warn ranking` — kto najbardziej przegina',
            warns.expiryDays > 0 ? `-# Każde ostrzeżenie wygasa samo po **${warns.expiryDays} dniach**.` : '-# Ostrzeżenia nie wygasają automatycznie.',
          ].join('\n'),
        },
        {
          name: '🗂️ Sprawy i narzędzia',
          value: [
            '`/historia` — wszystkie kary użytkownika',
            '`/sprawa pokaz` · `/sprawa powod` — podgląd / edycja sprawy',
            '`/clear` — usuwanie wiadomości',
            '`/slowmode` — tryb powolny',
            '`/lock` · `/unlock` — blokada kanału',
          ].join('\n'),
        },
      )
      .setFooter({ text: 'Odpowiedz na wiadomość o karze, a bot doda reakcję 🫓' });

    await interaction.reply({ embeds: [embed], flags: MessageFlags.Ephemeral });
  },
};
