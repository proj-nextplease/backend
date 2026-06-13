# Next Please Supabase Database Design

This backend uses Supabase as PostgreSQL/Auth infrastructure, but Spring Boot remains the source of truth for trust-critical business logic: RBAC, Reputation Score, EXP, NP wallet, Premium status, verification, and payments.

## Migration

Run Flyway against the Supabase PostgreSQL connection string. The schema is versioned under:

- `src/main/resources/db/migration/V1__init_identity_and_rbac.sql`
- `src/main/resources/db/migration/V2__nextplease_supabase_domain_schema.sql`

`V1` creates identity/RBAC basics. `V2` expands the database for the FunctionList backlog: profiles, schools, companies, authority nodes, skills, experiences, reputation/EXP events, jobs, applications, quests, wallet/payment/premium, shop, reports, notifications, QR check-in, badges, B2B orders, and auth verification security tables.

Expected backend env for Supabase:

```env
DATABASE_URL=jdbc:postgresql://<supabase-host>:5432/postgres?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=<supabase-db-password>
SUPABASE_ISSUER=https://<project-ref>.supabase.co/auth/v1
SUPABASE_JWKS_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
APP_SECURITY_JWT_ENABLED=true
```

## Mentor Answer: Authentication Verification Data

When a user authenticates, Next Please should not store raw Supabase access tokens, refresh tokens, OTPs, API keys, client secrets, webhook secrets, or private keys in plain text.

Recommended storage:

| Data | Table | Storage |
| --- | --- | --- |
| Supabase user identity | `app_users.supabase_user_id` | Plain UUID, safe to store |
| Public verification metadata | `auth_verification_providers` | Plain issuer/JWKS URI/public config |
| Decoded verification claims | `user_verification_claims` | Plain minimal claims: issuer, subject, status, assurance |
| JWT/OTP/magic-link/reset token | `user_verification_claims.raw_token_hash_sha256`, `verification_challenges.token_hash_sha256` | SHA-256 hash only |
| Refresh token audit/revocation | `auth_sessions.refresh_token_hash_sha256` | SHA-256 hash only |
| Access token replay/audit | `auth_sessions.access_token_jti` | JTI only, not the raw JWT |
| Provider API keys/client secrets/webhook secrets/private keys | `auth_verification_credentials.encrypted_secret_ciphertext` | Ciphertext only, encrypted by backend/KMS/env key |
| Secret lookup/rotation | `auth_verification_credentials.secret_fingerprint_sha256` | SHA-256 fingerprint only |
| Login location/device | `user_login_events` | IP/geolocation/device fingerprint hash/user agent |

Plain text is acceptable only for public metadata such as issuer URLs, JWKS URLs, provider code, verification status, and decoded non-secret claims needed by the application.

Sensitive values must be either:

- Hashed when the app only needs comparison/revocation/audit, such as OTPs, refresh tokens, reset tokens, QR tokens, and token fingerprints.
- Encrypted when the app must later use the secret, such as PayOS checksum keys, webhook secrets, provider API keys, OAuth client secrets, or private keys.

The encryption key must not live in PostgreSQL. Keep it in environment variables or a managed KMS/secret manager and rotate credentials through `auth_verification_credentials.status`, `valid_from`, and `valid_until`.

## Transaction Rules

These workflows must run in a Spring `@Transactional` service:

- Approve experience: update `experiences`, insert `reputation_events`, insert `exp_events`, update `profiles`.
- Apply Now: check `profiles.reputation_score`, Premium status from `subscriptions`/`app_users.premium_until`, then insert `applications`.
- Wallet top-up webhook: verify signature, insert `payment_webhook_events`, update `payment_requests`, insert `wallet_transactions`, update `wallets`.
- Premium purchase: debit `wallets`, insert `wallet_transactions`, create/extend `subscriptions`, update `app_users.premium_until`/roles.
- Digital item purchase: debit wallet, insert transaction, insert `user_items`; never create RS events.

## Notes

Supabase Row Level Security can be added later for direct client access, but the current MVP should keep database writes behind Spring Boot APIs. Frontend displays trust-critical values returned by backend and must not calculate RS/EXP/NP/Premium locally.
