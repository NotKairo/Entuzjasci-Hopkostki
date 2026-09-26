// Domyślna konfiguracja bota. Wszystko poniżej można zmienić w panelu (https://notkairo.github.io/Entuzjasci-Hopkostki/).
// Zapisana konfiguracja jest scalana z tymi wartościami, więc nowe opcje pojawiają się automatycznie.

export const ACTIONS = ['ban', 'unban', 'kick', 'timeout', 'untimeout', 'warn'];

export const DEFAULT_CONFIG = {
  // Kanał z logami moderacji (pełne szczegóły, punkty ostrzeżeń, status DM). Puste = brak logów.
  modLogChannelId: '',
  // Kanał, na który trafiają ogłoszenia o karach. Puste = odpowiedź na kanale, na którym użyto komendy.
  announceChannelId: '',
  // Role, które mogą używać komend moderacyjnych (oprócz osób z odpowiednimi uprawnieniami Discorda).
  modRoleIds: [],
  // Nadpisania dostępu dla POJEDYNCZYCH komend (panel → Uprawnienia): { nazwaKomendy: ['idRoli', ...] }.
  // Gdy komenda ma tu wpis, liczy się TYLKO ta lista ról (plus administratorzy) — niezależnie od
  // uprawnień Discorda i modRoleIds powyżej. Brak wpisu = zasady domyślne. null = obiekt dynamiczny,
  // scalany/sanitizowany specjalnie (patrz configSchema.js), nie strukturą jak zwykłe pola.
  commandPermissions: null,

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

  // Status bota na liście członków. Teksty rotują co rotateSeconds sekund.
  // type: custom (własny status) | playing (Gra w) | listening (Słucha) | watching (Ogląda) | competing (Rywalizuje w)
  // Zmienne: {czlonkowie} {online} {serwer} {ostrzezenia} {sprawy}
  presence: {
    enabled: true,
    status: 'online',
    rotateSeconds: 30,
    activities: [
      { type: 'custom', text: '🫓 Pilnuję porządku na Hopkostkach' },
      { type: 'watching', text: '{czlonkowie} Entuzjastów Hopkostki' },
      { type: 'custom', text: '🟢 {online} osób online • wpisz /pomoc' },
      { type: 'playing', text: '/pomoc • moderacja 24/7' },
    ],
  },

  // Kanały głosowe na żądanie: wejście na „kanał do dołączenia” (hubId) tworzy osobny kanał tej osoby
  // i przenosi ją tam; pusty kanał znika sam. Zmienne w nazwie: {nick} {numer}.
  // generators: [{ hubId, categoryId ('' = ta sama kategoria co hub), name, limit (0 = bez limitu), private }]
  tempVoice: {
    enabled: false,
    generators: [],
    // Panel z przyciskami wysyłany na czat nowego kanału.
    dashboard: {
      enabled: true,
      title: 'Panel kanału',
      description: 'Zarządzaj swoim kanałem głosowym przyciskami poniżej.',
      color: '#57F287',
    },
  },

  // Tickety: przycisk w wiadomości-panelu tworzy prywatny kanał dla tej osoby i obsługi.
  // Zmienne: {uzytkownik} {nick} {numer}. types: przyciski w panelu (question = pytanie w okienku, puste = bez okienka).
  tickets: {
    enabled: false,
    categoryId: '',
    supportRoleIds: [],
    logChannelId: '',
    maxOpen: 1,
    nameTemplate: 'ticket-{numer}',
    dmTranscript: true,
    welcome: 'Cześć {uzytkownik}! Opisz dokładnie swoją sprawę — ktoś z obsługi zaraz odpowie.',
    panel: {
      title: 'Pomoc i zgłoszenia',
      description: 'Masz problem albo pytanie do administracji? Kliknij przycisk poniżej — utworzymy prywatny kanał, na którym ktoś Ci odpowie.',
      color: '#5865F2',
    },
    types: [{ label: 'Otwórz ticket', style: 'niebieski', question: 'Opisz krótko swoją sprawę' }],
  },

  // Nowe osoby na serwerze. Wymaga intencji „Server Members” — bot włącza ją sam, gdy coś tu jest włączone.
  // Zmienne w wiadomościach: {uzytkownik} {nick} {serwer} {liczba}
  members: {
    autoRole: { enabled: false, roleIds: [], botRoleIds: [] },
    welcome: {
      enabled: false,
      channelId: '',
      title: 'Witaj na serwerze!',
      message: 'Cześć {uzytkownik}! Witamy na **{serwer}** — jesteś naszym **{liczba}.** członkiem.',
      color: '#57F287',
    },
    goodbye: { enabled: false, channelId: '', message: '**{nick}** opuścił(a) serwer. Zostało nas {liczba}.' },
    // Wejścia i wyjścia (z wiekiem konta) w kanale logów moderacji — pomaga wyłapać multikonta.
    logJoins: false,
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
