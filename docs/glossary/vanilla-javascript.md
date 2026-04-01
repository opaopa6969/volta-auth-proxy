# Vanilla JavaScript

[日本語版はこちら](vanilla-javascript.ja.md)

---

## What is it?

Vanilla JavaScript is plain JavaScript without any frameworks, libraries, or build tools. No React, no Vue, no Angular, no jQuery, no webpack, no npm. Just the JavaScript that browsers understand natively, written directly and served as-is. The term "vanilla" means "plain" or "unadorned" -- like vanilla ice cream, it is the base flavor without any toppings.

Think of it like cooking from scratch versus using a meal kit. A meal kit (framework) gives you pre-measured ingredients, step-by-step instructions, and specialized tools. Cooking from scratch means you pick your own ingredients, use basic kitchen tools, and control every detail. The meal kit is convenient but inflexible -- you make exactly what the kit contains. Cooking from scratch takes more thought but gives you complete freedom.

volta-auth-proxy's frontend SDK (volta.js) is written in vanilla JavaScript -- approximately 150 lines of plain JS that handle authentication flows without pulling in any npm dependencies.

---

## Why does it matter?

Frameworks have costs that are often invisible:

```
  Framework approach:
  ┌──────────────────────────────────────────────┐
  │  Your auth code:        ~50 lines            │
  │  + React:               ~140 KB (minified)   │
  │  + React DOM:           ~120 KB              │
  │  + Auth library:        ~45 KB               │
  │  + Bundler (webpack):   build step required  │
  │  + node_modules:        200+ packages        │
  │  ──────────────────────────────────────────   │
  │  Total: 305+ KB, 200+ dependencies,          │
  │         complex build pipeline               │
  └──────────────────────────────────────────────┘

  Vanilla approach (volta.js):
  ┌──────────────────────────────────────────────┐
  │  volta.js:              ~150 lines, ~4 KB    │
  │  Dependencies:          0                    │
  │  Build step:            none                 │
  │  node_modules:          none                 │
  │  ──────────────────────────────────────────   │
  │  Total: 4 KB, 0 dependencies,               │
  │         just a <script> tag                  │
  └──────────────────────────────────────────────┘
```

Key benefits of vanilla JS:

- **Zero dependencies**: No supply-chain risk from npm packages
- **No build step**: Works with a `<script>` tag, no webpack/vite needed
- **Tiny size**: Kilobytes instead of megabytes
- **Framework agnostic**: Works with React, Vue, Svelte, or plain HTML
- **Long-lived**: No framework version to keep up with
- **Auditable**: 150 lines can be read and understood completely

---

## How does it work?

### Modern browser APIs are enough

Vanilla JavaScript in modern browsers has everything you need:

```javascript
// Fetch API (no axios needed)
const response = await fetch('/auth/me', {
    credentials: 'include'  // send cookies
});
const user = await response.json();

// DOM manipulation (no jQuery needed)
document.getElementById('user-name').textContent = user.name;

// Event handling (no framework needed)
document.getElementById('logout-btn')
    .addEventListener('click', () => {
        fetch('/auth/logout', { method: 'POST', credentials: 'include' });
    });

// Template literals (no JSX needed)
container.innerHTML = `
    <div class="user-card">
        <h2>${user.name}</h2>
        <p>${user.email}</p>
    </div>
`;
```

### volta.js structure

```
  volta.js (~150 lines):
  ┌──────────────────────────────────────────────┐
  │                                               │
  │  const Volta = {                              │
  │                                               │
  │    // Check if user is logged in              │
  │    async me() { ... }                         │
  │                                               │
  │    // Redirect to login page                  │
  │    login() { ... }                            │
  │                                               │
  │    // Log the user out                        │
  │    async logout() { ... }                     │
  │                                               │
  │    // Refresh the session                     │
  │    async refresh() { ... }                    │
  │                                               │
  │    // Fetch with auto-retry on 401            │
  │    async fetch(url, options) { ... }          │
  │                                               │
  │    // Initialize: check session, set up       │
  │    // auto-refresh                            │
  │    async init(config) { ... }                 │
  │                                               │
  │  };                                           │
  │                                               │
  └──────────────────────────────────────────────┘
```

### Using volta.js in any framework

```html
<!-- Plain HTML -->
<script src="/volta.js"></script>
<script>
  Volta.init({ onLogin: showDashboard, onLogout: showLanding });
</script>
```

```javascript
// React (just import and use)
useEffect(() => {
    Volta.init({ onLogin: setUser, onLogout: () => setUser(null) });
}, []);

// Vue (same pattern)
onMounted(() => {
    Volta.init({ onLogin: (u) => user.value = u });
});

// Svelte (same pattern)
onMount(() => {
    Volta.init({ onLogin: (u) => user = u });
});
```

Because volta.js is vanilla, it works everywhere without adapters or wrapper libraries.

---

## How does volta-auth-proxy use it?

### volta.js: the frontend SDK

volta.js is the official client-side SDK for volta-auth-proxy. It handles:

1. **Session checking**: `Volta.me()` calls `/auth/me` to check the current [session](session.md)
2. **Login redirect**: `Volta.login()` redirects to the [OAuth2](oauth2.md) login flow
3. **Logout**: `Volta.logout()` calls `/auth/logout` to destroy the [session](session.md) and [cookies](cookie.md)
4. **Auto-refresh**: Automatically refreshes the session before it expires
5. **Retry on 401**: `Volta.fetch()` wraps `fetch()` with automatic [retry](retry.md) on 401

### How volta.js handles authentication

```
  ┌──────────────────────────────────────────────┐
  │  Volta.init()                                 │
  │  │                                            │
  │  ├── Call /auth/me                            │
  │  │   ├── 200 → User is logged in              │
  │  │   │        → Call onLogin(user)             │
  │  │   │        → Start auto-refresh timer       │
  │  │   │                                        │
  │  │   └── 401 → User is not logged in          │
  │  │            → Call onLogout()                │
  │  │                                            │
  │  └── Set up Volta.fetch() with retry logic    │
  │      │                                        │
  │      ├── Call /api/something                   │
  │      ├── If 401 → try /auth/refresh            │
  │      │           → retry original request      │
  │      └── If still 401 → call onLogout()        │
  └──────────────────────────────────────────────┘
```

### Why volta chose vanilla JS over a framework

| Consideration | Framework SDK | Vanilla volta.js |
|--------------|---------------|------------------|
| npm supply chain risk | High (100+ deps) | **Zero** (0 deps) |
| Framework lock-in | Yes (React-only, Vue-only, etc.) | **No** (works everywhere) |
| Bundle size | 50-300 KB | **~4 KB** |
| Build required | Yes (webpack, vite, etc.) | **No** (`<script>` tag) |
| Auditability | Hard (thousands of lines) | **Easy** (~150 lines) |
| Maintenance burden | Update for every framework version | **Minimal** (browser APIs are stable) |

This aligns with volta's philosophy: understand what you ship, minimize dependencies, and keep control.

### volta.js and cookies

volta.js relies on [cookies](cookie.md) set by volta-auth-proxy (HttpOnly, Secure, SameSite). The JavaScript code does not read or manipulate cookies directly -- it just sends requests with `credentials: 'include'` and lets the browser handle cookies automatically:

```javascript
// volta.js does NOT do this:
document.cookie = "session=...";  // NEVER

// volta.js DOES do this:
fetch('/auth/me', { credentials: 'include' });
// Browser automatically includes volta cookies
```

---

## Common mistakes and attacks

### Mistake 1: "Vanilla JS means no structure"

Vanilla does not mean spaghetti code. volta.js uses a clean module pattern with clear function boundaries. Vanilla means no framework, not no organization.

### Mistake 2: Reinventing everything

Vanilla JS does not mean you should write your own HTTP client from scratch. Use browser APIs (`fetch`, `URL`, `crypto.subtle`). They are well-tested and performant.

### Mistake 3: Not considering older browsers

Modern vanilla JS uses `async/await`, `fetch`, `template literals`, etc. If you need IE11 support, you need polyfills. volta.js targets modern browsers only (no IE11).

### Mistake 4: Mixing DOM manipulation patterns

Pick one approach (template literals, `createElement`, or `textContent`) and be consistent. volta.js uses minimal DOM interaction -- it is mostly API calls.

### Attack: Supply chain attack via npm

A compromised npm package can steal tokens, inject crypto miners, or exfiltrate data. volta.js has zero npm dependencies, eliminating this entire attack surface.

### Attack: XSS token theft

If an attacker can run JavaScript on your page (XSS), they could steal tokens. Defense: volta uses HttpOnly cookies that JavaScript cannot read. The session token is never exposed to client-side code.

---

## Further reading

- [cookie.md](cookie.md) -- How volta.js interacts with browser cookies.
- [session.md](session.md) -- The session lifecycle managed by volta.js.
- [oauth2.md](oauth2.md) -- The login flow that volta.js initiates.
- [retry.md](retry.md) -- How volta.js retries failed requests.
- [header.md](header.md) -- HTTP headers used in volta.js requests.
