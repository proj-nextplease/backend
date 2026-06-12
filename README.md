# Next Please Backend

Java Spring Boot backend API for Next Please, a web-first gamified reputation infrastructure and hyperlocal talent marketplace.

## Tech Stack

- Java 21
- Spring Boot
- Spring Security OAuth2 Resource Server
- Supabase Auth JWT validation
- PostgreSQL
- Flyway
- Spring Data JPA
- Railway deployment target

## Development

```bash
./mvnw spring-boot:run
```

Spring Boot reads environment variables from the process environment. Add values to your IDE run configuration, export them in your shell, or set them in Railway service variables.

## Environment Variables

- `APP_SECURITY_JWT_ENABLED`: Set `true` when Supabase JWT validation should be enforced.
- `APP_CORS_ALLOWED_ORIGINS`: Comma-separated allowed frontend origins.
- `DATABASE_URL`: JDBC URL for PostgreSQL/Supabase.
- `DB_USERNAME`: PostgreSQL username.
- `DB_PASSWORD`: PostgreSQL password.
- `SUPABASE_PROJECT_URL`: Supabase project URL.
- `SUPABASE_JWKS_URI`: Supabase JWKS URL.
- `SUPABASE_ISSUER`: Supabase JWT issuer.

See [docs/environment-setup.md](docs/environment-setup.md) for Supabase, local, and Railway setup details.

## Architecture Notes

- Supabase Auth verifies identity.
- Spring Boot validates Supabase JWTs for protected APIs.
- Application roles and frozen/banned status are enforced by backend business logic.
- Backend owns all trust-critical writes for Reputation Score, EXP, NP balance, Premium status, verification, applications, and payments.
- Flyway owns schema changes. Avoid manual production schema edits in Supabase Dashboard unless captured in a migration afterward.

## Git Workflow

- Work on `main` for small setup and documentation changes.
- Create a feature or fix branch for larger features, experiments, and bug fixes.
- Merge back into `main` after testing.
- Run `node scripts/update-readme-structure.mjs` before pushing when project structure changes.
- A GitHub Action can automate README structure updates after the GitHub token has `workflow` scope.

## API

- `GET /api/v1/health`: service health.
- `GET /api/v1/me`: current authenticated user, once Supabase JWT validation is enabled and an `app_users` row exists.
- `GET /swagger-ui/index.html`: Swagger UI for backend API documentation.
- `GET /v3/api-docs`: OpenAPI JSON specification.

## Project Structure

<!-- PROJECT_STRUCTURE_START -->
```text
.
+- .env.example
+- .gitattributes
+- .github/
  - .github/workflows/
+- .gitignore
+- docs/
  - docs/environment-setup.md
+- mvnw
+- mvnw.cmd
+- pom.xml
+- README.md
+- scripts/
  - scripts/update-readme-structure.mjs
+- src/
  - src/main/
    - src/main/java/
      - src/main/java/com/
        - src/main/java/com/nextplease/
          - src/main/java/com/nextplease/backend/
            - src/main/java/com/nextplease/backend/BackendApplication.java
            - src/main/java/com/nextplease/backend/config/
              - src/main/java/com/nextplease/backend/config/AppCorsProperties.java
              - src/main/java/com/nextplease/backend/config/OpenApiConfig.java
              - src/main/java/com/nextplease/backend/config/SecurityConfig.java
            - src/main/java/com/nextplease/backend/controller/
              - src/main/java/com/nextplease/backend/controller/HealthController.java
              - src/main/java/com/nextplease/backend/controller/MeController.java
            - src/main/java/com/nextplease/backend/dto/
              - src/main/java/com/nextplease/backend/dto/response/
                - src/main/java/com/nextplease/backend/dto/response/ApiResponse.java
                - src/main/java/com/nextplease/backend/dto/response/MeResponse.java
            - src/main/java/com/nextplease/backend/entity/
              - src/main/java/com/nextplease/backend/entity/AppUser.java
            - src/main/java/com/nextplease/backend/enums/
              - src/main/java/com/nextplease/backend/enums/AccountStatus.java
              - src/main/java/com/nextplease/backend/enums/RoleCode.java
            - src/main/java/com/nextplease/backend/exception/
              - src/main/java/com/nextplease/backend/exception/GlobalExceptionHandler.java
              - src/main/java/com/nextplease/backend/exception/ResourceNotFoundException.java
            - src/main/java/com/nextplease/backend/repository/
              - src/main/java/com/nextplease/backend/repository/AppUserRepository.java
            - src/main/java/com/nextplease/backend/security/
              - src/main/java/com/nextplease/backend/security/CurrentUserPrincipal.java
              - src/main/java/com/nextplease/backend/security/SupabaseJwtAuthenticationConverter.java
            - src/main/java/com/nextplease/backend/service/
              - src/main/java/com/nextplease/backend/service/CurrentUserService.java
    - src/main/resources/
      - src/main/resources/application.yml
      - src/main/resources/db/
        - src/main/resources/db/migration/
          - src/main/resources/db/migration/V1__init_identity_and_rbac.sql
      - src/main/resources/static/
      - src/main/resources/templates/
  - src/test/
    - src/test/java/
      - src/test/java/com/
        - src/test/java/com/nextplease/
          - src/test/java/com/nextplease/backend/
            - src/test/java/com/nextplease/backend/BackendApplicationTests.java
```
<!-- PROJECT_STRUCTURE_END -->
