-- V42: Portfolio public/private visibility toggle (Account Settings > Quyền
-- riêng tư). Public share links (/portfolio/view/:userId) already existed
-- unauthenticated (permitAll) with no way to opt out — this adds that switch.
-- Defaults to true so existing shared links keep working unless the owner
-- explicitly flips it off.
set lock_timeout = '5s';

alter table profiles
    add column if not exists is_public boolean not null default true;
