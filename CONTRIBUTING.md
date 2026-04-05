# Contributing to volta-auth-proxy

Thank you for your interest in contributing!

## Getting Started

1. Fork and clone the repository
2. Copy `.env.example` to `.env` and configure
3. Start PostgreSQL (Docker: `docker run -d -p 54329:5432 -e POSTGRES_DB=volta_auth -e POSTGRES_USER=volta -e POSTGRES_PASSWORD=volta postgres:16-alpine`)
4. `mvn compile exec:java` to start on port 7070

## Development

- Java 21 + Maven
- Javalin web framework
- jte templates (compiled at startup)
- PostgreSQL + Flyway migrations

## Pull Requests

- One feature per PR
- Include tests if adding new endpoints
- Follow existing code style (no formatter enforced)
- Conventional commits: `feat:`, `fix:`, `docs:`, `refactor:`

## Architecture

See [docs/AUTHENTICATION-FLOWS.md](../volta-platform/docs/AUTHENTICATION-FLOWS.md) for auth flow diagrams.

### Adding a new OAuth/OIDC provider

1. Create `YourProviderIdp.java` implementing `IdpProvider` (extend `BaseIdpProvider`)
2. Add credentials to `AppConfig.java` + `fromEnv()`
3. Register in `OidcService.java` `ALL_PROVIDERS`
4. That's it — login.jte auto-discovers enabled providers
