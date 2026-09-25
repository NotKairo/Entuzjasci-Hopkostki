# 🫓 Entuzjaści Hopkostki — bot moderacyjny

Bot moderacyjny na Discorda dla serwera **Entuzjaści Hopkostki**. Działa 24/7 na **Supabase**, więc nie
musisz trzymać włączonego komputera. Masz do niego lokalny panel konfiguracyjny pod `http://localhost:3000`.

```
Discord ──(komendy /ban, /warn…)──▶ Supabase Edge Function "hopkostki-bot" ──▶ Postgres (sprawy, ostrzeżenia, ustawienia)
                                           ▲                     ▲
                        pg_cron co 30 s ───┘                     └─── lokalny panel http://localhost:3000
            (wygasanie banów i ostrzeżeń, reakcje 🫓)
```

## Szybki start (3 kroki)

Projekt Supabase **„Entuzjaści Hopkostki”** (`ucjmbdogtzztrkorqzjq`) jest już utworzony. Baza, funkcja bota
i harmonogram są wdrożone. Brakuje tylko Twoich sekretów:

1. **Zresetuj token bota:** https://discord.com/developers/applications → Twoja aplikacja → **Bot** → **Reset Token**.
   Token, który był w czacie, uznaj za spalony.
2. **Dodaj sekrety w Supabase:** https://supabase.com/dashboard/project/ucjmbdogtzztrkorqzjq/functions/secrets
   | Nazwa | Wartość |
   | --- | --- |
   | `DISCORD_TOKEN` | nowy token bota |
   | `PANEL_PASSWORD` | dowolne mocne hasło do panelu |
   | `GUILD_ID` *(opcjonalnie)* | ID serwera; bez niego bot bierze pierwszy serwer, na którym jest |
3. **Poczekaj około minuty.** Harmonogram sam:
   - zarejestruje komendy slash na serwerze,
   - ustawi w Discordzie **Interactions Endpoint URL** na `https://ucjmbdogtzztrkorqzjq.supabase.co/functions/v1/hopkostki-bot`.

   Jeśli ustawienie adresu się nie uda (widać to w panelu), wklej go ręcznie w Developer Portal →
   **General Information** → **Interactions Endpoint URL** → **Save**.

Jeśli bota nie ma jeszcze na serwerze, zaproś go tym linkiem (podmień `TWOJE_CLIENT_ID` na *Application ID*):
```
https://discord.com/oauth2/authorize?client_id=TWOJE_CLIENT_ID&scope=bot+applications.commands&permissions=1374658325590
```
Potem przesuń rolę bota **wyżej** niż role osób, które ma karać (Ustawienia serwera → Role).

## Panel konfiguracyjny (localhost)

Panel działa u Ciebie na komputerze i łączy się z botem na Supabase. Potrzebujesz Node.js 18.17+ (https://nodejs.org).

```bash
cp .env.example .env       # Windows: copy .env.example .env
# wpisz w .env PANEL_PASSWORD (to samo co w Supabase)
npm run panel
```

Następnie otwórz **http://localhost:3000**. Panel nie wymaga `npm install`. W panelu są:
- **Pulpit:** status bota, lista kontrolna połączenia z Discordem, statystyki i ostatnie sprawy.
  Przycisk „Sprawdź i zarejestruj komendy” wymusza konfigurację od razu.
- **Ustawienia:** kanał logów, kanał ogłoszeń, role moderatorów, DM, dopisek o odwołaniach i emoji reakcji.
- **Wygląd embedów:** edycja każdej akcji z podglądem na żywo w stylu Discorda (na kanale i w DM).
- **Ostrzeżenia:** czas wygasania (domyślnie 60 dni), domyślne punkty, progi automatycznych kar
  i wszystkie aktywne ostrzeżenia z odliczaniem na żywo oraz przyciskiem „Usuń”.
- **Sprawy:** przeszukiwalna historia wszystkich akcji.
- **Tymczasowe bany:** odliczanie do końca bana i przycisk „Odbanuj teraz”.

Zmiany zapisujesz przyciskiem na dole i działają od razu. Panel nasłuchuje tylko na `127.0.0.1`, a hasło
dopisuje sam do każdego zapytania do Supabase.

## Komendy

| Komenda | Co robi |
| --- | --- |
| `/ban uzytkownik powod [czas] [jednostka] [usun_wiadomosci]` | Ban na czas albo permanentny (bez podania czasu). Bot sam zdejmuje tymczasowego bana. |
| `/unban uzytkownik [powod]` | Zdejmuje bana; lista zbanowanych pojawia się w podpowiedziach. |
| `/timeout uzytkownik czas jednostka powod` | Wyciszenie na czas (maksymalnie 28 dni, bo tyle pozwala Discord). |
| `/untimeout uzytkownik [powod]` | Zdejmuje wyciszenie. |
| `/kick uzytkownik powod` | Wyrzuca z serwera. |
| `/warn dodaj uzytkownik powod [punkty]` | Ostrzeżenie z punktami. |
| `/warn status uzytkownik` | Ostrzeżenia, punkty, pasek poziomu i odliczanie do wygaśnięcia każdego ostrzeżenia (widzi tylko moderator). |
| `/warn usun numer` · `/warn wyczysc uzytkownik` | Usuwanie ostrzeżeń. |
| `/warn ranking` | Kto ma najwięcej punktów, czyli kto najbardziej przegina. |
| `/historia uzytkownik` | Wszystkie kary danej osoby. |
| `/sprawa pokaz numer` · `/sprawa powod numer nowy_powod` | Podgląd i edycja sprawy. |
| `/clear ilosc [uzytkownik]` | Usuwa wiadomości. |
| `/slowmode sekundy [kanal]` | Tryb powolny. |
| `/lock` · `/unlock` | Blokada i odblokowanie pisania na kanale. |
| `/pomoc` | Lista komend. |

**Jednostki czasu** wybierasz z listy: minuty, godziny, dni, tygodnie albo miesiące (30 dni). W wiadomości
czas jest odmieniony po polsku („1 dzień”, „2 tygodnie”), a obok widać datę wygaśnięcia i odliczanie Discorda.

**Każda kara:**
- ma własny, konfigurowalny embed na kanale;
- **oznacza ukaranego** i trafia do niego w **DM** (przy banie i kicku DM idzie przed wyrzuceniem);
- pokazuje powód, czas, datę wygaśnięcia i numer sprawy;
- ląduje w logach moderacji.

Pod każdą odpowiedzią na wiadomość o karze bot dodaje reakcję 🫓 (`:flatbread:`).

**Ostrzeżenia:**
- mają punkty, które widzi tylko moderacja;
- **każde wygasa samo po 60 dniach**, z własnym odliczaniem;
- opcjonalnie działają automatyczne kary za przekroczenie progu punktów, np. 10 pkt → timeout na 1 dzień.

**Zabezpieczenia:**
- nie da się ukarać siebie, bota, właściciela serwera ani osoby z rolą równą lub wyższą;
- gdy Discord odrzuci karę, bot wycofuje sprawę i wysłany DM;
- każde żądanie od Discorda jest weryfikowane podpisem Ed25519.

## Co działa inaczej niż w zwykłym bocie

Supabase uruchamia kod na żądanie i nie trzyma stałego połączenia z Discordem (gateway), dlatego:
- **Bot jest widoczny jako offline** na liście członków. Komendy działają normalnie.
- **Reakcja 🫓 pojawia się z opóźnieniem do ~30 s.** Bot sprawdza odpowiedzi co 30 sekund, a nie natychmiast.
- **Darmowy plan Supabase wstrzymuje projekt po tygodniu bez aktywności.** Wtedy wejdź na dashboard i kliknij „Restore”.
  Regularne używanie komend liczy się jako aktywność.

## Dla deweloperów

```bash
npm install   # tylko do testów (PGlite = Postgres w pamięci)
npm test      # testy kar, ostrzeżeń, wygasania, podpisów, crona, panelu i SQL na prawdziwym Postgresie
```

Struktura:
```
supabase/
  migrations/                     schemat "bot" + zadanie pg_cron
  functions/hopkostki-bot/
    index.ts                      wejście funkcji Edge (Deno): postgres.js + sekrety
    lib/app.js                    router: / (Discord), /cron, /panel/*, /health
    lib/interactions.js           obsługa interakcji (odroczone odpowiedzi + praca w tle)
    lib/commands.js               definicje i obsługa komend slash
    lib/moderation.js             wspólny przebieg każdej kary + automatyczne kary
    lib/cron.js                   rejestracja komend, wygasanie, reakcje 🫓
    lib/panel.js                  API panelu
    lib/store.js                  zapytania SQL
    lib/embeds.js, defaults.js    wygląd i domyślne ustawienia
panel/
  server.js                       lokalny panel (bez zależności) — przekazuje /api do Supabase
  public/                         frontend panelu
```

Wdrożenie zmian: `supabase functions deploy hopkostki-bot --no-verify-jwt` (Supabase CLI) albo przez MCP.
