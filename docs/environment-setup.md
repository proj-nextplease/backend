# Environment Setup

## Required Supabase Setup

Create a Supabase project before deploying the backend.

Collect these values:

- Project URL: Supabase Dashboard -> Project Settings -> API.
- Database connection string: Supabase Dashboard -> Connect.
- Database password: the password created with the Supabase project.
- JWKS URL: `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json`
- JWT issuer: `https://<project-ref>.supabase.co/auth/v1`

For Spring Boot, the database URL must start with `jdbc:postgresql://`, not `postgresql://`.

Example:

```env
DATABASE_URL=jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres?sslmode=require
DB_USERNAME=postgres.your-project-ref
DB_PASSWORD=your-database-password
```

Use the session pooler or direct connection string from Supabase. Railway may run from an IPv4-only network, so Supabase pooler URLs are often the smoother deployment choice.

## Local Development

Spring Boot does not automatically load `.env` files. Use one of these options:

1. Add the variables to your IntelliJ run configuration.
2. Export variables before running:

```bash
export APP_SECURITY_JWT_ENABLED=false
export APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
export DATABASE_URL='jdbc:postgresql://localhost:5432/nextplease?sslmode=disable'
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export SUPABASE_PROJECT_URL='https://your-project.supabase.co'
export SUPABASE_JWKS_URI='https://your-project.supabase.co/auth/v1/.well-known/jwks.json'
export SUPABASE_ISSUER='https://your-project.supabase.co/auth/v1'

./mvnw spring-boot:run
```

## Railway

Add these variables to the Railway backend service:

```env
APP_SECURITY_JWT_ENABLED=false
APP_CORS_ALLOWED_ORIGINS=https://your-vercel-domain.vercel.app,http://localhost:5173

DATABASE_URL=jdbc:postgresql://your-supabase-host:5432/postgres?sslmode=require
DB_USERNAME=postgres.your-project-ref
DB_PASSWORD=your-database-password

SUPABASE_PROJECT_URL=https://your-project.supabase.co
SUPABASE_JWKS_URI=https://your-project.supabase.co/auth/v1/.well-known/jwks.json
SUPABASE_ISSUER=https://your-project.supabase.co/auth/v1
```

After saving variables, redeploy the Railway service.

Health check:

```text
https://your-railway-public-domain.up.railway.app/api/v1/health
```

## Security Notes

- Keep Supabase service role keys server-side only.
- Do not add service role keys until the backend has a specific service that needs them.
- Keep `APP_SECURITY_JWT_ENABLED=false` while only the public health endpoint and scaffolding exist.
- Turn `APP_SECURITY_JWT_ENABLED=true` when protected APIs and Supabase login are wired end-to-end.
