# 🫓 Entuzjaści Hopkostki — bot moderacyjny

Bot moderacyjny na Discorda dla serwera **Entuzjaści Hopkostki**. Działa 24/7 na **Supabase**, więc nie
musisz trzymać włączonego komputera. Panel konfiguracyjny to strona na GitHub Pages — wystarczy
przeglądarka, nic nie trzeba instalować ani uruchamiać lokalnie.

```
Discord ──(komendy /ban, /warn…)──▶ Supabase Edge Function "hopkostki-bot" ──▶ Postgres (sprawy, ostrzeżenia, ustawienia)
                    ▲            ▲                    ▲                              ▲
                    │            │   pg_cron co 30 s ─┘ (wygasanie banów/ostrzeżeń)   │
        gateway co minutę        │   pg_cron co minutę ─ sesja gateway (status online)│
   (status online, reakcje 🫓)   └── API panelu …/hopkostki-bot/panel/* ◀── strona na GitHub Pages
```

## Szybki start (3 kroki)

Projekt Supabase **„Entuzjaści Hopkostki”** (`ucjmbdogtzztrkorqzjq`) jest już utworzony. Baza, funkcja bota
i harmonogram są wdrożone. Brakuje tylko Twojego tokenu bota:

1. **Zresetuj token bota:** https://discord.com/developers/applications → Twoja aplikacja → **Bot** → **Reset Token**.
   Token, który był w czacie, uznaj za spalony.
2. **Dodaj sekret w Supabase:** https://supabase.com/dashboard/project/ucjmbdogtzztrkorqzjq/functions/secrets
   | Nazwa | Wartość |
   | --- | --- |
   | `DISCORD_TOKEN` | nowy token bota |
   | `PANEL_PASSWORD` *(opcjonalnie)* | własne hasło do panelu — jeśli go nie ustawisz, bot wygeneruje je sam przy pierwszym otwarciu panelu |
   | `GUILD_ID` *(opcjonalnie)* | ID serwera; bez niego bot bierze pierwszy serwer, na którym jest |
3. **Poczekaj około minuty.** Harmonogram sam:
   - zarejestruje komendy slash na serwerze,
   - ustawi w Discordzie **Interactions Endpoint URL**,
   - połączy bota z gatewayem, żeby świecił na zielono i pokazywał status.

   Jeśli ustawienie adresu się nie uda (widać to w panelu), wklej go ręcznie w Developer Portal →
   **General Information** → **Interactions Endpoint URL** → **Save**.

Jeśli bota nie ma jeszcze na serwerze, zaproś go tym linkiem (podmień `TWOJE_CLIENT_ID` na *Application ID*):
```
https://discord.com/oauth2/authorize?client_id=TWOJE_CLIENT_ID&scope=bot+applications.commands&permissions=1374658325590
```
Potem przesuń rolę bota **wyżej** niż role osób, które ma karać (Ustawienia serwera → Role).

## Panel konfiguracyjny

Nic nie instalujesz, wystarczy otworzyć w przeglądarce:

```
https://notkairo.github.io/Entuzjasci-Hopkostki/
```

Stary adres `…/functions/v1/hopkostki-bot/panel/` przekierowuje tutaj. Supabase nie pozwala funkcjom Edge
wyświetlać stron HTML (zamienia je na zwykły tekst), więc sama strona leży na GitHub Pages (gałąź `gh-pages`,
publikowana automatycznie z `panel/public/` po każdej zmianie na `main`), a dane i hasło obsługuje API bota
na Supabase — strona tylko je woła.

Przy pierwszym wejściu panel zapyta o **hasło** — to `PANEL_PASSWORD` z sekretów Supabase, albo (jeśli go
nie ustawiłeś) hasło wygenerowane automatycznie przy pierwszym użyciu i zapamiętane przez bota w bazie;
zmienisz je potem samodzielnie w zakładce **Ustawienia → 🔒 Hasło panelu**. Hasło zapamiętuje przeglądarka
na czas karty (do „Wyloguj” albo zamknięcia karty).

W panelu są:
- **Pulpit:** status bota, lista kontrolna połączenia z Discordem (w tym sesja gateway), statystyki
  i ostatnie sprawy. Przycisk „Sprawdź i zarejestruj komendy” wymusza konfigurację od razu.
- **Ustawienia:** kanał logów, kanał ogłoszeń, role moderatorów, DM, dopisek o odwołaniach, emoji reakcji,
  status i rotujące opisy bota oraz hasło panelu.
- **Uprawnienia:** dla każdej komendy możesz nadpisać domyślne uprawnienie Discorda i ograniczyć ją do
  wybranych ról — plus tabela pokazująca na żywo, która rola może użyć której komendy.
- **Wygląd embedów:** edycja każdej akcji z podglądem na żywo w stylu Discorda (na kanale i w DM).
- **Ostrzeżenia:** czas wygasania (domyślnie 60 dni), domyślne punkty, progi automatycznych kar
  i wszystkie aktywne ostrzeżenia z odliczaniem na żywo oraz przyciskiem „Usuń”.
- **Sprawy:** przeszukiwalna historia wszystkich akcji.
- **Tymczasowe bany:** odliczanie do końca bana i przycisk „Odbanuj teraz”.

Zmiany zapisujesz przyciskiem na dole i działają od razu.

### Uprawnienia — kto może użyć jakiej komendy

Domyślnie o dostępie do komendy decyduje: (1) administratorzy serwera — zawsze, (2) odpowiednie uprawnienie
Discorda (np. „Banowanie członków” do `/ban`) i (3) rola z listy **moderatorów** w Ustawieniach — ona daje
dostęp do wszystkich komend wymagających uprawnień. Komendy bez wymaganego uprawnienia (`/serwer`, `/avatar`,
`/pomoc`) są otwarte dla każdego.

W zakładce **Uprawnienia** możesz to nadpisać dla **pojedynczej komendy** — zaznacz „Ogranicz do wybranych
ról” i wybierz dokładnie te role, które mają jej używać. Wtedy liczy się TYLKO ta lista (plus administratorzy),
niezależnie od uprawnień Discorda i listy moderatorów. Tabela na dole tej zakładki liczy się na żywo z ról
serwera i pokazuje dokładnie, kto ma dostęp do czego.

### Uruchomienie panelu lokalnie (opcjonalnie)

Panel działa w pełni bez tego — to tylko wygodny skrót, jeśli wolisz `localhost` zamiast GitHub Pages.
Potrzebujesz Node.js 18.17+ (https://nodejs.org), zero zależności do zainstalowania:

```bash
npm run panel
```

Otwiera `http://localhost:3000` — te same pliki co na GitHub Pages, serwer tylko przekazuje resztę żądań
do bota na Supabase (adres z `BOT_URL` w `.env`, domyślnie projekt „Entuzjaści Hopkostki”). Hasło i tak
wpisujesz w przeglądarce.

## Komendy

| Komenda | Co robi |
| --- | --- |
| `/ban uzytkownik powod [czas] [jednostka] [usun_wiadomosci]` | Ban na czas albo permanentny (bez podania czasu). Bot sam zdejmuje tymczasowego bana. |
| `/unban [uzytkownik] [powod]` | Zdejmuje bana; bez podania osoby pokazuje listę zbanowanych ze stronami i wyborem do odbanowania. |
| `/timeout uzytkownik czas jednostka powod` | Wyciszenie na czas (maksymalnie 28 dni, bo tyle pozwala Discord). |
| `/untimeout uzytkownik [powod]` | Zdejmuje wyciszenie. |
| `/kick uzytkownik powod` | Wyrzuca z serwera. |
| `/warn dodaj uzytkownik powod [punkty]` | Ostrzeżenie z punktami. |
| `/warn status uzytkownik` | Ostrzeżenia, punkty, pasek poziomu i odliczanie do wygaśnięcia (strony ◀ 1 2 3 ▶, widzi tylko moderacja). |
| `/warn usun uzytkownik [numer]` | Lista ostrzeżeń tej osoby do usunięcia (albo od razu jedno, jeśli podasz numer). |
| `/warn wyczysc uzytkownik` · `/warn ranking` | Czyszczenie wszystkich ostrzeżeń / ranking punktów. |
| `/historia uzytkownik` | Wszystkie kary danej osoby (strony). |
| `/sprawy [typ]` · `/sprawa pokaz numer` · `/sprawa powod numer nowy_powod` | Lista, podgląd i edycja spraw. |
| `/notatka dodaj uzytkownik tresc` · `/notatka lista uzytkownik` | Prywatne notatki moderacji o osobie. |
| `/info uzytkownik` | Karta użytkownika: konto, role, kary, ostrzeżenia, notatki. |
| `/serwer` · `/avatar [uzytkownik]` | Informacje o serwerze / awatar w dużym rozmiarze. |
| `/nick uzytkownik [nowy_nick]` | Zmiana albo reset pseudonimu. |
| `/rola dodaj/usun uzytkownik rola` | Nadanie/odebranie roli (z kontrolą hierarchii). |
| `/ogloszenie tytul tresc [kolor] [kanal] [oznacz] [obrazek]` | Ogłoszenie w ładnym embedzie. |
| `/clear ilosc [uzytkownik]` · `/slowmode sekundy [kanal]` | Czyszczenie wiadomości / tryb powolny. |
| `/lock` · `/unlock` | Blokada i odblokowanie pisania na kanale. |
| `/pomoc` | Lista komend. |

**Jednostki czasu** wybierasz z listy: minuty, godziny, dni, tygodnie albo miesiące (30 dni). W wiadomości
czas jest odmieniony po polsku („1 dzień”, „2 tygodnie”), a obok widać datę wygaśnięcia i odliczanie Discorda.

**Listy z wieloma wynikami** (`/warn status`, `/warn usun`, `/unban`, `/historia`, `/sprawy`, `/notatka lista`,
`/warn ranking`) mają strony z przyciskami **◀ 1 2 3 ▶**, a tam gdzie trzeba coś usunąć/odbanować — listę
wyboru pod spodem.

**Każda kara:**
- ma własny, konfigurowalny embed z polami (na kanale i w DM);
- **oznacza ukaranego** i trafia do niego w **DM** (przy banie i kicku DM idzie przed wyrzuceniem);
- pokazuje powód, czas, datę wygaśnięcia i numer sprawy;
- ląduje w logach moderacji.

Pod każdą odpowiedzią na wiadomość o karze bot dodaje reakcję 🫓 (`:flatbread:`) — natychmiast przez
gateway, a dodatkowo sprawdzane co 30 s przez pg_cron jako zapas.

**Ostrzeżenia:**
- mają punkty, które widzi tylko moderacja;
- **każde wygasa samo po 60 dniach** (konfigurowalne), z własnym odliczaniem;
- opcjonalnie działają automatyczne kary za przekroczenie progu punktów, np. 10 pkt → timeout na 1 dzień.

**Status bota:** dzięki krótkim sesjom gateway (co minutę) bot świeci na zielono i pokazuje rotujące opisy
(„Gra w…”, „Ogląda…”, własny status…) — konfigurowalne w panelu, z podstawianymi zmiennymi
(`{czlonkowie}`, `{online}`, `{ostrzezenia}`, `{sprawy}`, `{serwer}`).

**Zabezpieczenia:**
- nie da się ukarać siebie, bota, właściciela serwera ani osoby z rolą równą lub wyższą;
- gdy Discord odrzuci karę, bot wycofuje sprawę i wysłany DM;
- każde żądanie od Discorda jest weryfikowane podpisem Ed25519;
- panel wymaga hasła (nagłówek sprawdzany w stałym czasie), dostęp do komend można dodatkowo ograniczyć
  per komenda w zakładce Uprawnienia.

## Co działa inaczej niż w zwykłym bocie

Supabase uruchamia kod na żądanie i nie trzyma stałego połączenia z Discordem, więc bot łączy się z
gatewayem w krótkich sesjach (~55 s co minutę) zamiast trzymać je stale:
- Między sesjami (ułamki minuty) bot bywa chwilowo „w trakcie łączenia” zamiast pełnego online — to
  normalne, komendy HTTP działają cały czas niezależnie od tego.
- **Darmowy plan Supabase wstrzymuje projekt po tygodniu bez aktywności.** Wtedy wejdź na dashboard i kliknij
  „Restore”. Regularne używanie komend i gateway liczą się jako aktywność.

## Dla deweloperów

```bash
npm install        # tylko do testów (PGlite = Postgres w pamięci)
npm test           # testy kar, ostrzeżeń, wygasania, podpisów, crona, gateway, panelu i SQL na prawdziwym Postgresie
```

Struktura:
```
supabase/
  migrations/                     schemat "bot" + zadania pg_cron (30 s: wygasanie/reakcje zapasowe; co minutę: gateway)
  functions/hopkostki-bot/
    index.ts                      wejście funkcji Edge (Deno): postgres.js + sekrety
    lib/app.js                    router: / (Discord), /cron, /gateway, /panel/* (API + CORS), /health
    lib/interactions.js           obsługa interakcji (odroczone odpowiedzi + praca w tle)
    lib/commands.js               definicje i obsługa komend slash
    lib/moderation.js             wspólny przebieg każdej kary, uprawnienia (hasModAccess/roleHasAccess)
    lib/views.js                  widoki ze stronami (◀ 1 2 3 ▶) i listami wyboru
    lib/gateway.js                krótkie sesje gateway: status online, opisy, reakcje 🫓 na żywo
    lib/cron.js                   rejestracja komend, wygasanie, reakcje 🫓 (zapas)
    lib/panel.js                  API panelu
    lib/store.js                  zapytania SQL
    lib/configSchema.js           walidacja konfiguracji z panelu (w tym uprawnienia per komenda)
    lib/embeds.js, defaults.js    wygląd i domyślne ustawienia
panel/
  server.js                       opcjonalny lokalny wrapper (statyka + przezroczysty proxy do Supabase)
  public/                         frontend panelu — publikowany na GitHub Pages (.github/workflows/panel-pages.yml)
```

Wdrożenie zmian w bocie: `supabase functions deploy hopkostki-bot --no-verify-jwt` (Supabase CLI) albo przez
MCP. Zmiany w `panel/public/` trafiają na GitHub Pages same po wypchnięciu na `main`.
