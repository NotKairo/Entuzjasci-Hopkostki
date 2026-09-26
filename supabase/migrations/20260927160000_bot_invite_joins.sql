-- Kto kogo zaprosił: wykryte przy wejściu zaproszenie (porównanie liczników użyć zaproszeń).
-- Z tego korzysta log wejść/wyjść („zaprosił(a)”, „zaproszony przez”) i komenda /zaproszenia.
create table bot.invite_joins (
  id int generated always as identity primary key,
  user_id text not null,
  inviter_id text,
  code text,
  joined_at timestamptz not null default now()
);
create index invite_joins_user_idx on bot.invite_joins (user_id, joined_at desc);
create index invite_joins_inviter_idx on bot.invite_joins (inviter_id, joined_at desc);

revoke all on bot.invite_joins from anon, authenticated;
