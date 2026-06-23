-- The pre-existing (legacy) `notifications` table carries extra NOT NULL columns
-- that our insert (user_id, type, title, body, link) doesn't populate — notably
-- `notification_type`. Drop NOT NULL on any such legacy-only required columns so
-- inserts succeed. Guarded so it's a no-op on a fresh DB where the column is absent.
do $$
begin
  if exists (
    select 1 from information_schema.columns
    where table_name = 'notifications' and column_name = 'notification_type'
  ) then
    execute 'alter table notifications alter column notification_type drop not null';
  end if;
end $$;
