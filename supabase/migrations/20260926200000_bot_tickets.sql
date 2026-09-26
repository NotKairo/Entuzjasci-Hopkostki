-- Tickety: prywatne kanały pomocy tworzone przyciskiem z panelu ticketów.
create table bot.tickets (
  id int generated always as identity primary key,
  guild_id text not null,
  channel_id text unique,
  user_id text not null,
  user_tag text,
  type text,
  subject text,
  status text not null default 'open',
  claimed_by text,
  closed_by text,
  created_at timestamptz not null default now(),
  closed_at timestamptz
);
create index tickets_user_status_idx on bot.tickets (user_id, status);

revoke all on bot.tickets from anon, authenticated;
