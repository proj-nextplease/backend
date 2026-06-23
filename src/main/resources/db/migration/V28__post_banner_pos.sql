-- Banner focal position (CSS object-position, e.g. "50% 30%") so organizers can
-- adjust how the banner image is framed. Null => center ("50% 50%").
alter table jobs   add column if not exists banner_pos varchar(20);
alter table quests add column if not exists banner_pos varchar(20);
