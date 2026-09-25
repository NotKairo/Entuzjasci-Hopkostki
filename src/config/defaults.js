// Domyślna konfiguracja bota. Wszystko poniżej można zmienić w panelu (http://localhost:3000).
// Zapisana konfiguracja jest scalana z tymi wartościami, więc nowe opcje pojawiają się automatycznie.

const ACTIONS = ['ban', 'unban', 'kick', 'timeout', 'untimeout', 'warn'];

const DEFAULT_CONFIG = {
  // Kanał z logami moderacji (pełne szczegóły, punkty ostrzeżeń, status DM). Puste = brak logów.
  modLogChannelId: '',
  // Kanał, na który trafiają ogłoszenia o karach. Puste = odpowiedź na kanale, na którym użyto komendy.
  announceChannelId: '',
  // Role, które mogą używać komend moderacyjnych (oprócz osób z odpowiednimi uprawnieniami Discorda).
  modRoleIds: [],

  // Oznaczanie ukaranego użytkownika w wiadomości na kanale.
  mentionTarget: true,
  // Wysyłanie prywatnej wiadomości (DM) do ukaranego użytkownika.
  dmUsers: true,
  // Czy w DM pokazywać, który moderator nałożył karę.
  dmShowModerator: true,
  // Tekst dopisywany do DM (np. informacja o odwołaniach). Puste = brak.
  appealText: 'Jeśli uważasz, że kara była niesłuszna, skontaktuj się z administracją serwera.',

  // Reakcja na odpowiedzi do wiadomości o karach (domyślnie :flatbread:).
  replyReaction: {
    enabled: true,
    emoji: '🫓',
  },

  warns: {
    // Domyślna liczba punktów za ostrzeżenie.
    defaultPoints: 1,
    // Po ilu dniach ostrzeżenie jest automatycznie usuwane (0 = nigdy nie wygasa).
    // Każde ostrzeżenie ma własne odliczanie liczone od chwili nadania.
    expiryDays: 60,
    // Czy pokazywać punkty użytkownikowi (kanał + DM). Domyślnie punkty widzi tylko moderacja.
    showPointsToUser: false,
  },

  // Automatyczne kary po przekroczeniu progu aktywnych punktów ostrzeżeń.
  // action: alert (tylko powiadomienie w logach) | timeout | kick | ban
  escalation: {
    enabled: false,
    rules: [
      { points: 5, action: 'alert', amount: 0, unit: 'm' },
      { points: 10, action: 'timeout', amount: 1, unit: 'd' },
      { points: 20, action: 'ban', amount: 7, unit: 'd' },
    ],
  },

  // Wygląd embedów dla każdej akcji. Dostępne zmienne w tekstach:
  // {uzytkownik} {nick} {moderator} {moderatorNick} {powod} {czas} {serwer} {sprawa} {typ}
  actions: {
    ban: {
      color: '#ED4245',
      emoji: '⛔',
      title: 'Pomyślnie zbanowano użytkownika',
      description: 'Pomyślnie {typ} zbanowałeś **{nick}** z serwera!',
      dmTitle: 'Zostałeś zbanowany',
      dmDescription: 'Zostałeś {typ} zbanowany na serwerze **{serwer}**.',
    },
    unban: {
      color: '#57F287',
      emoji: '✅',
      title: 'Pomyślnie odbanowano użytkownika',
      description: 'Użytkownik **{nick}** został odbanowany i może wrócić na serwer.',
      dmTitle: 'Zostałeś odbanowany',
      dmDescription: 'Twój ban na serwerze **{serwer}** został zdjęty. Możesz wrócić!',
    },
    kick: {
      color: '#F0883E',
      emoji: '👢',
      title: 'Pomyślnie wyrzucono użytkownika',
      description: 'Pomyślnie wyrzuciłeś **{nick}** z serwera!',
      dmTitle: 'Zostałeś wyrzucony',
      dmDescription: 'Zostałeś wyrzucony z serwera **{serwer}**. Możesz dołączyć ponownie z nowym zaproszeniem.',
    },
    timeout: {
      color: '#9B59B6',
      emoji: '🔇',
      title: 'Pomyślnie wyciszono użytkownika',
      description: 'Pomyślnie nałożyłeś timeout na **{nick}** na **{czas}**!',
      dmTitle: 'Otrzymałeś timeout',
      dmDescription: 'Zostałeś wyciszony na serwerze **{serwer}** na **{czas}**.',
    },
    untimeout: {
      color: '#57F287',
      emoji: '🔊',
      title: 'Pomyślnie zdjęto timeout',
      description: 'Timeout użytkownika **{nick}** został zdjęty.',
      dmTitle: 'Twój timeout został zdjęty',
      dmDescription: 'Możesz znowu pisać na serwerze **{serwer}**.',
    },
    warn: {
      color: '#FEE75C',
      emoji: '⚠️',
      title: 'Użytkownik otrzymał ostrzeżenie',
      description: '**{nick}** otrzymał ostrzeżenie od moderacji.',
      dmTitle: 'Otrzymałeś ostrzeżenie',
      dmDescription: 'Otrzymałeś ostrzeżenie na serwerze **{serwer}**. Kolejne mogą skutkować karą.',
    },
  },
};

module.exports = { DEFAULT_CONFIG, ACTIONS };
