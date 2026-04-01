# Mermaid

[日本語版はこちら](mermaid.ja.md)

---

## What is it?

Mermaid is a text-based diagramming tool. You write diagrams as plain text using a simple syntax, and Mermaid renders them into visual diagrams -- flowcharts, sequence diagrams, state diagrams, entity-relationship diagrams, and more. No drag-and-drop, no image editor, no binary files.

Think of it like writing sheet music instead of recording audio. A musician can read the text (notation) and produce the sound (diagram) from it. The notation is version-controllable, diffable, and readable by both humans and machines. Mermaid does the same thing for diagrams: you write the description, and the tool draws the picture.

Mermaid is supported natively in GitHub Markdown, GitLab, Notion, Docusaurus, and many other documentation tools. You can embed a diagram in a README.md file, and GitHub renders it as a visual diagram without any plugins.

---

## Why does it matter?

Traditional diagrams are created in tools like Visio, Lucidchart, or draw.io. These produce binary files (PNG, SVG, .drawio) that:

- Cannot be diffed in pull requests
- Require a specific tool to edit
- Get out of date because updating them is friction
- Live outside the codebase (in a separate folder, a wiki, or someone's laptop)

Mermaid diagrams solve these problems because they are text:

| Aspect | Binary diagrams | Mermaid (text) |
|--------|----------------|----------------|
| **Version control** | Binary blob in git | Text diff in pull requests |
| **Editing** | Requires Visio/Lucidchart | Any text editor |
| **Code review** | Cannot review changes | Changes visible line by line |
| **Freshness** | Quickly stale | Easy to update, so it stays fresh |
| **Automation** | Manual creation only | Can be generated from code |

That last point -- generation from code -- is particularly important for volta.

---

## How does it work?

### Basic Mermaid syntax

**Flowchart:**
```
  flowchart TD
    A[Start] --> B{Is user logged in?}
    B -->|Yes| C[Show dashboard]
    B -->|No| D[Redirect to /login]
    D --> E[Google OIDC]
    E --> F[Callback]
    F --> C
```

**Sequence diagram:**
```
  sequenceDiagram
    Browser->>volta: GET /login
    volta->>Google: Redirect to OIDC
    Google->>Browser: Login page
    Browser->>Google: Credentials
    Google->>volta: Callback with code
    volta->>Google: Exchange code for tokens
    Google->>volta: ID token + access token
    volta->>Browser: Set session cookie
```

**State diagram:**
```
  stateDiagram-v2
    [*] --> Anonymous
    Anonymous --> Authenticating: GET /login
    Authenticating --> Authenticated: Callback success
    Authenticating --> Anonymous: Callback failure
    Authenticated --> Anonymous: Session expired
    Authenticated --> Authenticated: API request
```

**Entity-relationship diagram:**
```
  erDiagram
    USER ||--o{ MEMBERSHIP : has
    TENANT ||--o{ MEMBERSHIP : has
    TENANT ||--o{ INVITATION : has
    MEMBERSHIP {
      string userId
      string tenantId
      string role
    }
```

### Where Mermaid renders

| Platform | Support |
|----------|---------|
| **GitHub** | Native in Markdown (```mermaid code blocks) |
| **GitLab** | Native in Markdown |
| **VS Code** | Via extension (Markdown Preview Mermaid) |
| **Notion** | Native support |
| **Docusaurus** | Plugin available |
| **CLI** | `mmdc` (mermaid-cli) for PNG/SVG generation |

### Mermaid live editor

The [Mermaid Live Editor](https://mermaid.live/) lets you write Mermaid syntax and see the rendered diagram in real-time. Useful for prototyping complex diagrams.

---

## How does volta-auth-proxy use it?

volta uses Mermaid in two ways: hand-written diagrams in documentation, and **generated diagrams from the DSL**.

### Hand-written diagrams in docs

volta's glossary and architecture documentation use Mermaid for inline diagrams. Because these are text, they can be reviewed in pull requests and stay synchronized with the code.

### DSL-generated state diagrams

This is the more interesting use. volta's DSL can generate Mermaid state diagrams from auth rule definitions. When you define rules in the DSL, volta can output a Mermaid diagram showing the state machine of authentication flows.

```yaml
  # volta DSL rule definition:
  rules:
    - name: "public-health"
      path: "/healthz"
      action: allow-anonymous

    - name: "login-flow"
      path: "/login"
      action: redirect-to-oidc

    - name: "api-authenticated"
      path: "/api/**"
      guard: "user.authenticated == true"
      action: allow

    - name: "api-deny"
      path: "/api/**"
      action: deny
```

```
  # Generated Mermaid output:
  stateDiagram-v2
    [*] --> RequestReceived

    RequestReceived --> AllowAnonymous: path == /healthz
    RequestReceived --> RedirectToOIDC: path == /login
    RequestReceived --> EvaluateGuard: path == /api/**

    EvaluateGuard --> Allow: user.authenticated == true
    EvaluateGuard --> Deny: otherwise

    RedirectToOIDC --> GoogleOIDC
    GoogleOIDC --> CallbackProcessing
    CallbackProcessing --> SessionCreated
    SessionCreated --> RequestReceived: next request
```

### Why generated diagrams matter

| Benefit | Explanation |
|---------|------------|
| **Always accurate** | The diagram comes from the same source as the running rules |
| **No manual sync** | Change a rule, regenerate the diagram. Zero effort. |
| **Visualize complex policies** | 20 rules are hard to reason about as text. A state diagram makes the flow obvious. |
| **Documentation as code** | The diagram is a build artifact, not a manual creation |
| **Review tool** | Before deploying new rules, generate the diagram and visually verify the flow |

### Integration with volta's CEL-like guards

The generated diagrams show guard expressions as transition labels, making it easy to see which conditions lead to which states. See [cel.md](cel.md) for details on guard expressions.

---

## Common mistakes and attacks

### Mistake 1: Over-complex diagrams

Mermaid diagrams should be simple and focused. A diagram with 50 nodes and 100 edges is unreadable. Split complex systems into multiple focused diagrams.

### Mistake 2: Diagrams that duplicate code comments

If the diagram says exactly what the code comments say, it adds no value. Diagrams should show relationships and flows that are hard to see in code -- the big picture, not the details.

### Mistake 3: Not updating diagrams

A stale diagram is worse than no diagram. It misleads readers. If diagrams are generated from code (as volta does with DSL rules), this problem is eliminated. For hand-written diagrams, update them in the same PR that changes the behavior.

### Mistake 4: Using Mermaid for everything

Mermaid is excellent for flowcharts, sequence diagrams, and state diagrams. It is less suitable for detailed architecture diagrams, network topologies, or UI wireframes. Use the right tool for the job.

### Mistake 5: Ignoring rendering differences

Mermaid renders slightly differently across platforms (GitHub vs. GitLab vs. VS Code). Test your diagrams on the platform where they will be viewed most.

---

## Further reading

- [cel.md](cel.md) -- Guard expressions that appear as transitions in generated diagrams.
- [Mermaid Documentation](https://mermaid.js.org/) -- Official syntax reference.
- [Mermaid Live Editor](https://mermaid.live/) -- Interactive diagram editor.
- [GitHub Mermaid Support](https://github.blog/2022-02-14-include-diagrams-markdown-files-mermaid/) -- How GitHub renders Mermaid.
