# Dashboard (Auth Context)

[日本語版はこちら](dashboard.ja.md)

---

## What is it?

In the auth world, a "dashboard" is a web-based graphical interface where administrators configure and manage their authentication system. It is the point-and-click alternative to editing configuration files or writing code.

Think of it like the control panel of a car. Instead of opening the hood and adjusting engine cables, you have a dashboard with buttons, dials, and gauges. Turn a knob to adjust the temperature. Push a button to turn on the lights. The dashboard gives you a visual interface to control something complex.

---

## Why does it matter?

When teams evaluate auth solutions, "does it have a dashboard?" is often one of the first questions. Auth0's dashboard is famously polished. Keycloak has an extensive admin console. volta-auth-proxy has... a YAML file and environment variables.

This is a deliberate choice, and understanding why requires thinking about the trade-offs between GUI-driven and code-driven configuration.

---

## Dashboard-driven configuration (Auth0, Keycloak)

### Auth0's Dashboard

Auth0 provides a web dashboard at `manage.auth0.com` where you can:

- Create and configure applications (OAuth2 clients)
- Toggle social login providers on/off
- Configure authentication rules
- View user profiles and logs
- Set up Organizations
- Manage API permissions

```
  Auth0 Dashboard:
  ┌─────────────────────────────────────────────────┐
  │ [Auth0 Logo]                         Team ▼     │
  ├─────────┬───────────────────────────────────────┤
  │         │                                       │
  │ Apps    │  Application: My SaaS                 │
  │ APIs    │  ┌─────────────────────────────────┐  │
  │ Users   │  │ Client ID:  abc123...            │  │
  │ Rules   │  │ Allowed URLs: https://...        │  │
  │ Orgs    │  │ Social: [✓] Google [✗] GitHub    │  │
  │ Logs    │  │ MFA: Enabled                     │  │
  │ ...     │  └─────────────────────────────────┘  │
  │         │                                       │
  │         │  [Save Changes]                       │
  └─────────┴───────────────────────────────────────┘
```

This is visual, intuitive, and easy to start with.

### Keycloak's Admin Console

Keycloak has a built-in admin console with even more options:

- Realm management (each realm is a tab)
- Client scopes, protocol mappers, authentication flows
- User federation, identity providers
- Session management, event logging

The Keycloak admin console has dozens of pages, each with many settings.

---

## Code-driven configuration (volta)

volta-auth-proxy does not have a web dashboard for configuration. All configuration is done through files:

**Environment variables (.env):**
```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/volta_auth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
SESSION_SECRET=your-random-secret
BASE_URL=https://auth.example.com
```

**Application config (volta-config.yaml):**
```yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

That is the entire configuration. No web interface.

(volta does have an **admin UI** for operational tasks like managing members, invitations, and sessions -- but this is for day-to-day operations, not for system configuration.)

---

## GUI vs code-based configuration trade-offs

| Aspect | Dashboard (GUI) | Code (files) |
|--------|----------------|-------------|
| **Discoverability** | Explore options visually | Must know what's available |
| **Learning curve** | Low initial barrier | Requires reading docs |
| **Version control** | Hard to track changes in git | Natural git integration |
| **Reproducibility** | "Click these 7 things in order" | `cp .env.example .env` |
| **Automation** | Requires API wrappers | Already code, already scriptable |
| **Team review** | "I changed a setting" (no diff) | Pull request with diff |
| **Disaster recovery** | Re-click everything | `git checkout` + deploy |
| **Multiple environments** | Configure each one manually | Copy config, change values |
| **Audit trail** | Depends on provider's logging | Git history |

### The hidden cost of dashboards

Dashboards feel easy at first. But consider this scenario:

**Setting up a new environment:**

With Auth0's dashboard:
1. Log in to Auth0 dashboard
2. Create a new application (5 clicks)
3. Configure redirect URIs (copy-paste, hope you got them right)
4. Toggle social connections (3 clicks per provider)
5. Configure rules (code editor inside the dashboard)
6. Set up Organizations (many clicks)
7. Configure API permissions (more clicks)
8. Hope you did not miss anything

With volta's config files:
1. Copy `.env.example` to `.env`
2. Fill in 5 values
3. Deploy

**Reproducing a broken environment:**

With Auth0: Remember every setting you clicked. Or export/import JSON (which is essentially code-based configuration with extra steps).

With volta: `git log` shows every change ever made. `git revert` undoes mistakes.

---

## Why volta chose code over dashboard

volta's philosophy is that **configuration is better as code** for production systems:

1. **Code is reviewable.** A teammate can review your `.env` changes in a pull request. Nobody reviews a dashboard click.

2. **Code is reproducible.** `cp .env.staging .env.production` creates an identical environment. Dashboard settings must be manually recreated.

3. **Code is versionable.** Git tracks every change with timestamps and authors. Dashboard changes may or may not be logged.

4. **Code is simple.** volta's entire configuration fits on one screen. There is nothing to "discover" because there is almost nothing to configure.

5. **Configuration should not be a daily activity.** You set up volta once. After that, you manage users and tenants through the admin UI or API -- not through configuration.

---

## Further reading

- [config-hell.md](config-hell.md) -- Why too many configuration options is a problem.
- [yaml.md](yaml.md) -- The configuration format volta uses.
- [environment-variable.md](environment-variable.md) -- How .env files work.
- [keycloak.md](keycloak.md) -- Keycloak's dashboard and its complexity.
- [auth0.md](auth0.md) -- Auth0's dashboard approach.
