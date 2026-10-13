create extension if not exists pg_trgm;

create table users (
  id                 uuid primary key default gen_random_uuid(),
  email              text not null unique,
  password_hash      text not null,
  nickname           text not null unique check (char_length(nickname) between 2 and 12),
  daily_goal_seconds int  not null default 21600
                     check (daily_goal_seconds between 1800 and 57600),
  review_weekday     smallint not null default 1 check (review_weekday between 0 and 6),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create table user_stats (
  user_id                 uuid primary key references users(id) on delete cascade,
  total_net_focus_seconds bigint not null default 0,
  current_streak          int not null default 0,
  longest_streak          int not null default 0,
  last_gold_day           date,
  stamps_received         int not null default 0,
  stamps_given            int not null default 0,
  updated_at              timestamptz not null default now()
);

create table refresh_tokens (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references users(id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index idx_refresh_tokens_user on refresh_tokens (user_id);
