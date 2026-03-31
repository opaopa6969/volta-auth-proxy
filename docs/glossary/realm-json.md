# realm.json

[日本語版はこちら](realm-json.ja.md)

---

## What is it?

**realm.json is a gigantic configuration file for Keycloak -- like a 500-page instruction manual for a building's entire security system.**

It's OK not to know this! This is very specific to Keycloak, and most people never have to deal with it.

---

## A real-world analogy

Imagine you're the security manager for a huge office building. You need to write down EVERY rule about how the building's security works:

- Who is allowed in which doors?
- What hours are the doors locked?
- How long can someone stay before they need to show their badge again?
- What happens when someone enters the wrong password 3 times?
- What does the welcome message on the lobby screen say?
- What color is the badge for visitors vs. employees?
- ...and 500 more rules like this

Now imagine ALL of these rules must be written in ONE massive document, in a very specific format, where a single misplaced comma breaks everything.

That's `realm.json`.

---

## What does it look like?

Here's a tiny piece of a real realm.json (the full file can be thousands of lines):

```json
{
  "realm": "my-company",
  "enabled": true,
  "sslRequired": "external",
  "bruteForceProtected": true,
  "maxFailureWaitSeconds": 900,
  "loginTheme": "my-theme",
  "accessTokenLifespan": 300,
  "ssoSessionMaxLifespan": 28800,
  "clients": [
    {
      "clientId": "my-app",
      "redirectUris": ["https://my-app.example.com/*"],
      "webOrigins": ["https://my-app.example.com"],
      ...hundreds more settings per client...
    }
  ],
  "roles": { ... },
  "users": { ... },
  "authenticationFlows": { ... },
  ...hundreds more sections...
}
```

A real-world realm.json file can easily be **2,000-5,000 lines long**. Editing it by hand is terrifying.

---

## Why is realm.json painful?

```
  Imagine this scenario:

  Boss:    "Can you change the login timeout from 5 minutes to 10 minutes?"
  You:     "Sure!"

  Step 1:  Open a 3,000-line file
  Step 2:  Search for the right setting among dozens of similar-sounding ones
           (is it accessTokenLifespan? ssoSessionIdleTimeout?
            ssoSessionMaxLifespan? offlineSessionIdleTimeout?)
  Step 3:  Change the number
  Step 4:  Pray you didn't accidentally delete a comma on line 2,847
  Step 5:  Restart Keycloak
  Step 6:  It doesn't work. Back to step 2.
```

This is not a fun afternoon.

---

## What does volta use instead?

volta-auth-proxy replaces this giant file with simple settings you can put in a small `.env` file:

```
# volta's way: simple and clear
VOLTA_SESSION_TIMEOUT=10m
VOLTA_MAX_LOGIN_FAILURES=5
VOLTA_LOCKOUT_DURATION=15m
```

That's it. No 3,000-line file. No hunting for the right setting name. No misplaced commas breaking everything.

---

## In volta-auth-proxy

**In volta-auth-proxy:** There is no realm.json. All configuration is done through simple environment variables in a .env file, making setup dramatically simpler than Keycloak's approach.
