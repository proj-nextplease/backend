-- P0 verification: let candidates attach proof images (data URLs / hosted URLs)
-- so admins have real evidence to verify against. Array of strings.
alter table experiences
    add column if not exists proof_images jsonb;
