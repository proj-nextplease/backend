<h1 align="center">fonlio · Backend API</h1> <p align="center"> The Spring Boot backend that powers <a href="https://www.fonlio.online"><b>fonlio</b></a>, a gamified reputation and hyperlocal talent marketplace for Gen Z in Vietnam. </p> <p align="center"> <a href="https://github.com/proj-nextplease/backend/actions/workflows/ci.yml"><img src="https://github.com/proj-nextplease/backend/actions/workflows/ci.yml/badge.svg" alt="CI" /></a> <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21" /> <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5" /> <img src="https://img.shields.io/badge/PostgreSQL-Supabase-4169E1?logo=postgresql&logoColor=white" alt="PostgreSQL" /> <img src="https://img.shields.io/badge/Deployed%20on-Railway-0B0D0E?logo=railway&logoColor=white" alt="Railway" /> </p> <p align="center"> <a href="https://www.fonlio.online"><b>Live product</b></a> · <a href="https://github.com/proj-nextplease/frontend">Web client</a> · <a href="https://github.com/proj-nextplease/frontMobile">Mobile app</a> </p>
Overview

fonlio lets students build a verified reputation through jobs, quests and credentials, and lets businesses find and hire local talent. The platform is live with 100+ users.

This repository is the single source of truth for the platform. The React web app and the Flutter mobile app are thin clients: every value that involves trust or money (Reputation Score, EXP, NP wallet balance, Premium status, verification results, payments) is calculated and enforced here, never on the client.

	
REST endpoints	150+, across 31 controllers
Database	75 PostgreSQL tables, versioned through 70+ Flyway migrations
Actors	Candidates, businesses (employers and organizers), admins
Integrations	Supabase Auth, PayOS, Google Play Billing, Google Gemini, SMTP / Brevo
Architecture
Spring Boot API (Railway)
sign in
sign in
signed webhook
JWKS
Bearer JWT
Bearer JWT
React web app(Vercel)
Flutter app(Android)
Supabase Auth
Rate limiter + JWTvalidation
Controllers
Services(business rules)
Scheduled jobs
Async AI worker
PostgreSQLFlyway-managed
PayOS
Google PlayDeveloper API
Google Gemini
Email (SMTP / Brevo)

Request flow: the client signs in with Supabase and sends the access token on every request. The rate limiter runs first, then Spring Security validates the JWT against Supabase's JWKS. A custom converter loads the user's application roles and account status from the database, so a frozen or banned account is rejected even when its token is still valid.

Engineering highlights
💳 Payments that never double-credit

Wallet top-ups go through PayOS, and the PayOS webhook is the only path that adds money to a wallet.

Authenticity: every webhook is verified with HMAC-SHA256 using a constant-time comparison.
Correctness: the paid amount is reconciled against the order stored in the database, never against the number in the webhook. A valid signature proves the payload came from PayOS, not that it matches our order.
Idempotency: PayOS retries webhooks until it receives HTTP 200, and retries can arrive in parallel. Instead of "read the status, then update", the order is moved with a single conditional statement, UPDATE ... SET status = 'PAID' WHERE status = 'PENDING', so only one request can ever win. The wallet row is then locked with SELECT ... FOR UPDATE before its balance changes.
Auditability: every balance change is written to a wallet_transactions ledger with an idempotency key and the balance after the change.

Google Play Billing purchases from the mobile app are verified server-side with the Google Play Developer API before they are acknowledged, and the purchase token is hashed and stored to block replays.

🤖 AI-assisted credential verification

When a candidate submits a credential (a certificate, award or work experience), an @Async worker sends the evidence (up to 3 images plus an external link) to Google Gemini, gets a structured verdict back and notifies the candidate. If AI is turned off in system config, the API key is missing or the call fails, the submission falls back to manual admin review. Nothing is blocked.

🔐 Security
OAuth2 Resource Server that validates Supabase-issued JWTs, with a custom JwtAuthenticationConverter that resolves application roles and account status on every request
RBAC across 6 role tiers: candidate_free, candidate_premium, employer_free, employer_premium, organizer, admin
Account lifecycle (active / frozen / banned) enforced in the security layer
Token-bucket rate limiter with 3 tiers (auth, write, global), client-IP resolution behind proxies, and eviction of idle buckets
Content moderation that combines a Vietnamese blocklist with PurgoMalum. It fails open: content is flagged for admins rather than hard-blocked
⏱ Background jobs
Job	Schedule	Purpose
SubscriptionExpiryScheduler	Hourly	Downgrade expired Premium subscriptions
PostingDeadlineScheduler	Hourly	Close job and quest postings past their deadline
RegistrationCleanupScheduler	Daily, 02:00	Remove abandoned registration attempts
🗄 Schema as code

Every schema change is a Flyway migration, and the database is never edited by hand in the Supabase dashboard. Pricing and feature flags live in a system_configs table, so admins can change prices or turn AI verification on and off without a redeploy.

Domain modules
Module	Base path	What it covers
Auth & registration	/api/v1/auth	Candidate registration with OTP, B2B registration, login
Profiles & portfolio	/api/v1/profiles, /api/v1/me	Public profiles, portfolio publishing, experience
Jobs & quests	/api/v1/jobs, /api/v1/quests	Postings, search, saved items, external job feed
Applications	/api/v1/organizer/pipeline	9-state application lifecycle with a status-history audit trail
Companies	/api/v1/company, /api/v1/companies	Company membership, invitations, follows
Credentials	/api/v1/credentials	Evidence submission and AI-assisted verification
Gamification	/api/v1/me/gamification	Reputation Score, EXP, ratings
Wallet & payments	/api/v1/wallet, /api/v1/payments	NP wallet, PayOS top-ups, Google Play Billing
Premium	/api/v1/premium	Subscription purchase and expiry
Community	/api/v1/discussions	Discussion forum with anonymous posting
Notifications	/api/v1/me/notifications	In-app notifications and email delivery
Admin	/api/v1/admin/*	B2B verification, review queue, system config, ambassador program, dashboard

Full, interactive API documentation is available through Swagger UI when the app is running (see below).

Tech stack
Layer	Technology
Language & framework	Java 21, Spring Boot 3.5 (Web, Validation, Actuator, Mail)
Security	Spring Security, OAuth2 Resource Server, Supabase Auth (JWT / JWKS)
Data	PostgreSQL (Supabase), Spring Data JPA, NamedParameterJdbcTemplate, Flyway
Integrations	PayOS, Google Play Developer API, Google Gemini, SMTP / Brevo, PurgoMalum
Docs	springdoc-openapi (Swagger UI)
Testing	JUnit 5, Spring Boot Test, Spring Security Test, H2
CI / CD	GitHub Actions (build and test on every push and PR to main), Railway
Getting started
Prerequisites
Java 21
PostgreSQL 14+ (local) or a Supabase project
Run locally
bash
git clone https://github.com/proj-nextplease/backend.git
cd backend

# Set the environment variables below (IDE run configuration or shell),
# using .env.example as a template. Spring Boot does not load .env files automatically.

./mvnw spring-boot:run

Flyway applies all migrations on startup. The API then runs on http://localhost:8080:

URL	Description
GET /api/v1/health	Health check
/swagger-ui/index.html	Interactive API docs
/v3/api-docs	OpenAPI JSON spec
Run tests
bash
./mvnw test
Configuration

All configuration comes from environment variables. See .env.example for a template and docs/environment-setup.md for Supabase, local and Railway setup.

Group	Variables
Database	DATABASE_URL, DB_USERNAME, DB_PASSWORD, DB_MAX_POOL_SIZE
Auth	APP_SECURITY_JWT_ENABLED, SUPABASE_PROJECT_URL, SUPABASE_JWKS_URI, SUPABASE_ISSUER, SUPABASE_SERVICE_ROLE_KEY
Web	APP_CORS_ALLOWED_ORIGINS, APP_FRONTEND_BASE_URL, PORT
Rate limiting	APP_RATE_LIMIT_ENABLED, APP_RATE_LIMIT_AUTH, APP_RATE_LIMIT_WRITE, APP_RATE_LIMIT_GLOBAL
Payments	PAYOS_CLIENT_ID, PAYOS_API_KEY, PAYOS_CHECKSUM_KEY, GOOGLE_PLAY_PACKAGE_NAME, GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
AI	GEMINI_API_KEY, GEMINI_MODEL
Email	APP_MAIL_ENABLED, SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, BREVO_API_KEY

⚠️ APP_SECURITY_JWT_ENABLED defaults to false for local development. When it is false, every endpoint is public. It must be true in production.

Project structure
text
src/main/java/com/nextplease/backend/
├── config/        Security, CORS and OpenAPI configuration
├── controller/    REST controllers (one per domain module)
├── service/       Business logic, SQL data access, schedulers, integrations
├── repository/    Spring Data JPA repository (user identity)
├── entity/        JPA entity (user identity)
├── dto/           Request / response objects
├── security/      JWT converter, current-user principal, rate limiter
├── enums/         Role codes, account status
├── exception/     Global exception handling
└── util/          Shared helpers

src/main/resources/
├── application.yml
└── db/migration/  Flyway migrations (V1 … V73)
Development workflow
Small setup and documentation changes go straight to main. Features and fixes go on their own branch and are merged through a pull request.
CI builds the project and runs the tests on every push and pull request to main.
Never edit the production schema by hand. Every change must be a new Flyway migration.
<p align="center"> Built and maintained by <a href="https://github.com/PhatNT284"><b>Nghê Tài Phát</b></a> · <a href="https://www.linkedin.com/in/ngtphat">LinkedIn</a> </p>
