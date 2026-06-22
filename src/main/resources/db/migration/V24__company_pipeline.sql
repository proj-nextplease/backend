-- Per-company recruitment pipeline customization (low-code).
-- Stores display label/color/visibility for the FIXED canonical application
-- statuses. Scoring/transition logic still keys off canonical status — this is
-- presentation only, so it can never break RS/EXP rules.
-- config shape: [{ "status": "SHORTLISTED", "label": "Phỏng vấn", "color": "#d97706", "hidden": false }, ...]

alter table companies
    add column if not exists pipeline_config jsonb;
