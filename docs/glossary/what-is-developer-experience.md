# What Is Developer Experience, Actually?

[日本語版はこちら](what-is-developer-experience.ja.md)

---

## The misconception

"Great DX."

Developer experience has become a marketing term. "Great DX" usually means "we have a CLI" or "it installs in one command" or "look at our pretty documentation site." These things are nice. They are not developer experience.

Developer experience is not about the first five minutes. It is about the first five months. And the five months after that. It is the total cognitive and emotional cost of using a tool over its entire lifecycle in your project.

---

## What developer experience actually means

Developer experience is the sum of every interaction a developer has with your tool, from the moment they hear about it to the moment they are debugging a production issue at midnight six months later. It includes:

### Time to first success

How long does it take a developer who has never seen your tool to go from zero to "it works and I understand why"? Not just "it runs" -- but "I understand the mental model well enough to predict what will happen when I change something."

For many tools, the time to first success is measured in days or weeks. For some, it is measured in minutes of installation followed by months of confusion.

### Error messages that help

This is where most developer experience falls apart. The happy path is easy. Every tool works on the happy path. The question is: what happens when something goes wrong?

Bad DX: `Error: invalid configuration`
What configuration? What is invalid about it? What should I do instead?

Good DX: `Error: volta-config.yaml field "apps[0].allowed_roles" contains "SUPERADMIN" which is not a valid role. Valid roles are: OWNER, ADMIN, MEMBER, VIEWER`

The difference is not cleverness. It is empathy. Someone wrote that error message by asking "if I were seeing this for the first time, what would I need to know?"

### Documentation that teaches

Documentation comes in layers:

1. **Reference documentation** answers "what does this parameter do?" This is the minimum. Most projects stop here.
2. **Tutorial documentation** answers "how do I accomplish this specific task?" This is better.
3. **Conceptual documentation** answers "why does this work this way? What is the mental model?" This is rare.
4. **Glossary documentation** answers "what does this word even mean?" This is almost unheard of.

Most developers struggle not because they cannot read API docs, but because they do not understand the underlying concepts well enough to know which API docs to read.

### Predictability

Good DX means the tool does what you expect. When you change a configuration value, the result is what you predicted. When you call an API, the response format is consistent. When an error occurs, you can guess where to look.

Predictability requires consistency. Consistent naming. Consistent error formats. Consistent behavior across similar operations. This is tedious to implement and invisible when done right. You only notice it when it is missing.

### Debuggability

When something goes wrong (and it will), how hard is it to figure out why? Can you see the request flow? Are there logs that tell a coherent story? Can you reproduce the issue locally?

The worst DX is when a tool fails silently or produces an error that requires reading the source code to understand. The best DX is when the error message tells you not just what went wrong, but what to do about it.

---

## volta's approach to developer experience

volta-auth-proxy treats developer experience as a first-class design concern, not an afterthought. Here is how:

### ForwardAuth: zero auth code in your app

The single most impactful DX decision in volta is ForwardAuth. Your downstream app never implements authentication. It never parses JWTs. It never manages sessions. It reads HTTP headers:

```
X-Volta-User-Id: 550e8400-e29b-41d4-a716-446655440000
X-Volta-Tenant-Id: abcd1234-5678-9012-3456-789012345678
X-Volta-Roles: ADMIN
X-Volta-Display: Taro Yamada
```

That is it. Your app trusts these headers because Traefik only sends them after volta has verified the session. The time from "I need auth" to "I have auth" is one configuration block in your Traefik config, not weeks of OAuth integration.

### The 283-article glossary

This is the part that sounds insane and is actually the most important DX investment volta makes. Every technical term in the README links to a glossary article. Every glossary article explains the concept as if the reader has never seen it before.

Why? Because the biggest DX problem in authentication is not "how do I configure OIDC?" It is "what is OIDC and why should I care?" If you do not understand the concepts, no amount of API documentation will help you. You will copy-paste configuration, get it working by accident, and have no ability to debug it when it breaks.

The glossary is not documentation for the tool. It is education for the developer. That distinction matters.

### Configuration simplicity

volta's entire configuration is:
- `.env` file for secrets (Google client ID/secret, encryption key, service token)
- `volta-config.yaml` for app definitions and routing

Not hundreds of settings. Not a 500-line realm.json. Not a dashboard with 47 tabs. Two files, each with clear purpose.

When configuration is simple, mistakes are obvious. When configuration is complex, mistakes hide.

### Dev mode

`POST /dev/token` generates test JWTs for local development. You do not need to set up Google OIDC, create a test user, and go through the login flow just to test your app's protected endpoint. You request a dev token, and you have a valid JWT in milliseconds.

This is a small feature that saves hours. DX is often about these small features.

### Content negotiation

JSON API requests never receive HTML redirects. This sounds minor, but it eliminates an entire class of debugging frustration. When your SPA's fetch request hits an auth wall, it gets a clean 401 JSON response, not a 302 redirect to a login page that your JavaScript cannot follow.

Good DX anticipates the developer's context. A browser and an API client are different contexts. They should get different responses.

---

## The DX that matters most

If you asked me to rank the DX elements in order of actual impact:

1. **Error messages.** Nothing else comes close. A developer who gets stuck on a bad error message will waste hours. A developer who gets a helpful error message will fix the problem in minutes.

2. **Conceptual documentation.** Understanding why things work the way they do eliminates entire categories of mistakes.

3. **Time to working example.** Not time to installation. Time to a working, understandable example that the developer can modify.

4. **Predictability.** Consistent behavior across the tool reduces the total number of things a developer needs to remember.

5. **Pretty website.** Dead last. It is nice but it does not help anyone debug a 401 at midnight.

---

## It is OK not to know this

If you thought DX was about ease of installation or pretty docs, you had the visible part right but were missing the depth. Real DX is the cumulative experience over time -- not the first impression, but the hundredth interaction.

The tools that developers love are not always the ones with the nicest websites. They are the ones that respect the developer's time, anticipate their confusion, and help them succeed even when things go wrong.

Now you know.
