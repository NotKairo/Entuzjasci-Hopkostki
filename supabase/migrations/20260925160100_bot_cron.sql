-- Co 30 sekund wywołuje funkcję bota: wygasanie banów i ostrzeżeń, reakcje 🫓 na odpowiedzi,
-- rejestracja komend. Sekret crona jest w bot.state i nigdy nie opuszcza bazy/funkcji.

create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

select cron.schedule(
  'hopkostki-bot-cron',
  '30 seconds',
  $$
  select net.http_post(
    url := 'https://ucjmbdogtzztrkorqzjq.supabase.co/functions/v1/hopkostki-bot/cron',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-cron-secret', (select value #>> '{}' from bot.state where key = 'cron_secret')
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 25000
  );
  $$
);
