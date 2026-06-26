-- Migration V35: Add job_match_alert_monthly plan to subscription_plans
INSERT INTO subscription_plans (code, display_name, price_np, duration_days)
VALUES ('job_match_alert_monthly', 'Job Match Alert Monthly', 19000, 30)
ON CONFLICT (code) DO NOTHING;
