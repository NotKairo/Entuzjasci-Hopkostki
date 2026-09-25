# 🫓 Entuzjaści Hopkostki — bot moderacyjny

Bot moderacyjny na Discorda dla serwera **Entuzjaści Hopkostki**: bany i timeouty na określony czas, kicki,
ostrzeżenia z punktami (znikają same po 60 dniach) i panel konfiguracyjny na `http://localhost:3000`.

## Funkcje

| Komenda | Co robi |
| --- | --- |
| `/ban uzytkownik powod [czas] [jednostka] [usun_wiadomosci]` | Ban na czas albo permanentny (bez podania czasu). Bot sam zdejmuje tymczasowego bana. |
| `/unban uzytkownik [powod]` | Zdejmuje bana; lista zbanowanych pojawia się w podpowiedziach. |
| `/timeout uzytkownik czas jednostka powod` | Wyciszenie na czas (maksymalnie 28 dni, bo tyle pozwala Discord). |
| `/untimeout uzytkownik [powod]` | Zdejmuje wyciszenie. |
| `/kick uzytkownik powod` | Wyrzuca z serwera. |
| `/warn dodaj uzytkownik powod [punkty]` | Ostrzeżenie z punktami. |
| `/warn status uzytkownik` | Liczba ostrzeżeń i punktów, pasek poziomu, odliczanie do wygaśnięcia każdego ostrzeżenia (widzi tylko osoba, która użyła komendy). |
| `/warn usun numer` · `/warn wyczysc uzytkownik` | Usuwanie ostrzeżeń. |
| `/warn ranking` | Kto ma najwięcej punktów, czyli kto najbardziej przegina. |
| `/historia uzytkownik` | Wszystkie kary danej osoby. |
| `/sprawa pokaz numer` · `/sprawa powod numer nowy_powod` | Podgląd i edycja sprawy. |
| `/clear ilosc [uzytkownik]` | Usuwa wiadomości. |
| `/slowmode sekundy [kanal]` | Tryb powolny. |
| `/lock` · `/unlock` | Blokada i odblokowanie pisania na kanale. |
| `/pomoc` | Lista komend. |

**Jednostki czasu** wybierasz z listy: minuty, godziny, dni, tygodnie albo miesiące (30 dni). W wiadomości
czas jest odmieniony po polsku, np. „1 dzień”, „2 tygodnie”, „5 miesięcy”, a obok widać datę wygaśnięcia
i odliczanie Discorda („za 14 dni”).

**Każda kara** (ban, timeout, kick, ostrzeżenie):
- ma własny embed ostylizowany pod komendę: kolor, emoji, tytuł i opis. Wszystko to zmienisz w panelu.
- pokazuje, **dlaczego** ktoś dostał karę (powód), **na ile** (czas i data wygaśnięcia), **kiedy** ją dostał i od kogo.
- jest publikowana na kanale, na którym użyto komendy (albo na wybranym kanale ogłoszeń), i **oznacza ukaranego użytkownika**.
- trafia do ukaranego **prywatnie w DM**. Przy banie i kicku DM idzie jeszcze przed wyrzuceniem, bo potem bot nie miałby jak do niego napisać.
- dostaje numer sprawy i jest zapisywana w historii oraz na kanale logów moderacji.
- dostaje reakcję 🫓 (`:flatbread:`) pod każdą odpowiedzią (reply) na wiadomość o karze.

**Ostrzeżenia:**
- mają punkty, które widzi tylko moderacja. Użytkownik dostaje ostrzeżenie bez informacji o punktach (można to zmienić w panelu).
- **każde ostrzeżenie wygasa samo po 60 dniach** i ma własne odliczanie liczone od chwili nadania. Odliczanie widać w `/warn status`, w embedzie ostrzeżenia i na żywo w panelu. Po wygaśnięciu bot usuwa ostrzeżenie i zapisuje to w logach. Liczbę dni zmienisz w panelu (0 oznacza, że ostrzeżenia nie wygasają).
- opcjonalnie działają **automatyczne kary** po przekroczeniu progu punktów, np. 10 pkt → timeout na 1 dzień, 20 pkt → ban na 7 dni. Zamiast kary próg może też tylko wysłać alert, który pinguje moderatorów.

**Zabezpieczenia:**
- nie da się ukarać siebie, bota, właściciela serwera ani osoby z rolą równą lub wyższą od swojej.
- gdy Discord odrzuci karę (np. przez brak uprawnień), bot wycofuje sprawę i wysłany DM.
- ręczne zdjęcie bana w ustawieniach serwera usuwa go z listy tymczasowych banów.

## Panel konfiguracyjny (localhost)

Po uruchomieniu bota otwórz **http://localhost:3000**. W panelu są:
- **Pulpit:** status bota, statystyki i ostatnie sprawy.
- **Ustawienia:** kanał logów, kanał ogłoszeń, role moderatorów, DM, dopisek o odwołaniach i emoji reakcji.
- **Wygląd embedów:** edycja każdej akcji z podglądem na żywo w stylu Discorda (na kanale i w DM).
- **Ostrzeżenia:** czas wygasania, domyślne punkty, progi automatycznych kar i lista wszystkich aktywnych ostrzeżeń z odliczaniem oraz przyciskiem „Usuń”.
- **Sprawy:** przeszukiwalna historia wszystkich akcji.
- **Tymczasowe bany:** odliczanie do końca bana i przycisk „Odbanuj teraz”.

Zmiany działają od razu, bez restartu. Panel domyślnie nasłuchuje tylko na `127.0.0.1`, więc otworzysz go
wyłącznie z komputera, na którym działa bot. Hasło ustawisz w `PANEL_PASSWORD`.

## Instalacja

Potrzebujesz **Node.js 18.17 lub nowszego** (zalecany 22 LTS): https://nodejs.org

1. **Utwórz bota albo użyj istniejącego:** https://discord.com/developers/applications → Twoja aplikacja → **Bot** → **Reset Token** i skopiuj token.
   Bot nie potrzebuje żadnych „Privileged Gateway Intents”.
2. **Zaproś bota na serwer.** Otwórz poniższy link, podmieniając `TWOJE_CLIENT_ID` na *Application ID* z zakładki General Information:
   ```
   https://discord.com/oauth2/authorize?client_id=TWOJE_CLIENT_ID&scope=bot+applications.commands&permissions=1374658325590
   ```
   Link nadaje uprawnienia: banowanie, wyrzucanie, timeout, zarządzanie wiadomościami, kanałami i uprawnieniami kanałów (do `/lock`), wysyłanie wiadomości, embedy i reakcje.
3. **Przenieś rolę bota wyżej** (Ustawienia serwera → Role). Bot może karać tylko osoby z rolami **niższymi** niż jego własna.
4. **Skonfiguruj i uruchom:**
   ```bash
   npm install
   cp .env.example .env      # na Windowsie: copy .env.example .env
   # uzupełnij w .env: DISCORD_TOKEN i GUILD_ID
   npm start
   ```
5. Wejdź na http://localhost:3000 i wybierz kanał logów moderacji.

> ⚠️ **Token to hasło do bota.** Trzymaj go tylko w pliku `.env`, który jest w `.gitignore`. Nie wklejaj go
> na czat, do kodu ani na GitHuba. Jeśli wyciekł, od razu kliknij **Reset Token** w Developer Portalu.

## Konfiguracja `.env`

| Zmienna | Opis |
| --- | --- |
| `DISCORD_TOKEN` | Token bota (wymagany). |
| `GUILD_ID` | ID serwera. Komendy rejestrują się wtedy natychmiast, a bot działa tylko na tym serwerze. |
| `PANEL_PORT` | Port panelu (domyślnie `3000`). |
| `PANEL_HOST` | `127.0.0.1` (domyślnie, dostęp tylko z tego komputera) lub `0.0.0.0` (dostęp z sieci, wymaga hasła). |
| `PANEL_PASSWORD` | Hasło do panelu (opcjonalne). |

Dane (sprawy, ostrzeżenia, tymczasowe bany, ustawienia) są zapisywane w `data/db.json`. Zrób kopię tego pliku,
jeśli przenosisz bota na inny komputer.

## Dla deweloperów

```bash
npm test   # testy logiki kar, ostrzeżeń, wygasania, konfiguracji i panelu (bez łączenia z Discordem)
```

Struktura:
```
src/
  index.js              start bota, rejestracja komend, panel
  commands/             komendy slash (jeden plik = jedna komenda)
  events/               interakcje, reakcja 🫓 na odpowiedzi, ręczne unbany
  lib/moderation.js     wspólny przebieg każdej kary (DM → akcja → ogłoszenie → log) + automatyczne kary
  lib/embeds.js         wygląd embedów (na podstawie konfiguracji)
  lib/db.js             baza w pliku JSON
  lib/scheduler.js      co 30 s: wygasanie tymczasowych banów i ostrzeżeń
  config/defaults.js    domyślne ustawienia
  panel/                serwer panelu (Express) + frontend
```
