# Environment Variable

[日本語版はこちら](environment-variable.ja.md)

---

## What is it in one sentence?

An environment variable is a setting stored outside your code that tells your application how to behave -- like the difference between a restaurant's "open" and "closed" sign, which changes behavior without remodeling the building.

---

## The real-world analogy: settings outside the machine

Think about a vending machine. The drinks inside the machine are like your code -- they are built in and do not change. But there are settings that the operator can adjust without opening the machine:

- **Price per drink** -- can be changed with a key
- **Temperature** -- adjusted from a control panel on the back
- **"Out of order" sign** -- flipped on or off from outside

These are like environment variables. They are not part of the machine itself, but they change how the machine behaves. The operator can adjust them without rebuilding the machine.

In software:
- **The vending machine** = your application code
- **The external settings** = environment variables
- **The operator** = you (the developer or ops person deploying the app)

---

## Why passwords do not go in code

This is one of the most important lessons for new engineers:

**Never, ever put passwords, API keys, or secrets directly in your code.**

Here is why:

```
  BAD (secret in code):
  ────────────────────
  // database.js
  const password = "super-secret-password-123";
  db.connect("localhost", "volta", password);

  Problems:
  1. Anyone who reads your code sees the password
  2. If you push this to GitHub, the whole world sees it
  3. You need different passwords for development vs production
  4. Changing the password means changing and redeploying your code
```

```
  GOOD (secret in environment variable):
  ──────────────────────────────────────
  // database.js
  const password = process.env.DB_PASSWORD;
  db.connect("localhost", "volta", password);

  Benefits:
  1. The code does not contain the secret
  2. Safe to push to GitHub
  3. Different environments use different values
  4. Change the password by changing the variable, no code change needed
```

---

## What is a .env file?

A `.env` file is a simple text file that stores environment variables. It lives in your project directory and is never committed to version control (Git). It looks like this:

```
DB_HOST=localhost
DB_PORT=54329
DB_PASSWORD=my-secret-password
GOOGLE_CLIENT_ID=abc123
JWT_PRIVATE_KEY_PEM=replace-me
```

Each line is a variable name, an equals sign, and a value. That is it. No fancy syntax.

The critical rule: **add `.env` to your `.gitignore` file** so it never gets pushed to GitHub. Instead, you provide a `.env.example` file that shows the variable names without real values:

```
DB_HOST=localhost
DB_PORT=54329
DB_PASSWORD=replace-me
GOOGLE_CLIENT_ID=replace-me
JWT_PRIVATE_KEY_PEM=replace-me
```

New developers copy `.env.example` to `.env` and fill in their own values.

---

## volta's environment variable configuration

volta-auth-proxy uses environment variables for all its configuration. Here are the key groups:

**Database connection:**
```
DB_HOST=localhost
DB_PORT=54329
DB_NAME=volta_auth
DB_USER=volta
DB_PASSWORD=volta
```

**Google login (OIDC):**
```
GOOGLE_CLIENT_ID=replace-me
GOOGLE_CLIENT_SECRET=replace-me
GOOGLE_REDIRECT_URI=http://localhost:7070/callback
```

**Security tokens:**
```
JWT_ISSUER=volta-auth
JWT_AUDIENCE=volta-apps
JWT_PRIVATE_KEY_PEM=replace-me
JWT_PUBLIC_KEY_PEM=replace-me
JWT_TTL_SECONDS=300
VOLTA_SERVICE_TOKEN=replace-me
```

**Application settings:**
```
PORT=7070
BASE_URL=http://localhost:7070
DEV_MODE=false
APP_CONFIG_PATH=volta-config.yaml
```

Notice how sensitive values like `GOOGLE_CLIENT_SECRET` and `JWT_PRIVATE_KEY_PEM` are environment variables, not hardcoded in the application. This means:
- The same code can run in development (with test keys) and production (with real keys)
- Secrets are never in the Git repository
- You can rotate secrets without changing code

---

## A simple example

When you first set up volta-auth-proxy for development:

```
  Step 1: Copy the example file
  $ cp .env.example .env

  Step 2: Edit .env with your values
  DB_PASSWORD=my-local-password
  GOOGLE_CLIENT_ID=my-google-id
  GOOGLE_CLIENT_SECRET=my-google-secret

  Step 3: Run the application
  $ ./mvnw spring-boot:run
  (The app reads .env and uses your values)

  Step 4: Later, in production
  (Same code, different .env values)
  DB_PASSWORD=production-ultra-secure-password
  GOOGLE_CLIENT_ID=production-google-id
  GOOGLE_CLIENT_SECRET=production-google-secret
```

The code is identical. Only the environment variables change.

---

## Further reading

- [docker-compose.md](docker-compose.md) -- How environment variables are set in Docker containers.
- [sdk.md](sdk.md) -- SDKs that help your app read volta headers configured via env vars.
