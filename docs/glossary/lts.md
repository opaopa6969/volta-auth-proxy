# LTS (Long Term Support)

[日本語版はこちら](lts.ja.md)

---

## What is it?

LTS stands for Long Term Support. It means a software release that the maintainers promise to support with security patches and bug fixes for an extended period -- typically several years. Non-LTS releases are supported for only a few months before you must upgrade to the next version.

Think of it like buying a car. A regular model (non-LTS) gets manufacturer support for 2 years, and then you are expected to buy a new one. An LTS model gets 5+ years of support -- oil changes, warranty repairs, and safety recalls. You can drive it for years without worrying that the manufacturer has forgotten about you. For software that handles authentication (where security patches are critical), you want the LTS car.

volta-auth-proxy uses **Java 21 LTS**, which receives security updates until at least September 2029. This gives volta a stable, secure foundation without the pressure of upgrading Java every 6 months.

---

## Why does it matter?

- **Security patches for years.** Authentication code must stay patched against vulnerabilities. An unsupported Java version means no patches.
- **Stability.** LTS releases are battle-tested by thousands of organizations. Non-LTS versions are used by fewer people and have less real-world testing.
- **Dependency compatibility.** Libraries like Javalin, HikariCP, and Flyway test against LTS versions first. Non-LTS compatibility is often "best effort."
- **Enterprise requirements.** Customers in regulated industries often require LTS software for compliance reasons.
- **Reduced upgrade churn.** Upgrading Java every 6 months (non-LTS cycle) is disruptive. LTS lets you upgrade every 2-3 years instead.

---

## How does it work?

### Java release schedule

Oracle releases a new Java version every 6 months. Only every 4th release (every 2 years) is designated LTS:

```
  Java Release Timeline:
  ──────────────────────────────────────────────────────▶ time

  2021    2022    2023    2024    2025    2026    2027
   │       │       │       │       │       │       │
   17      18      19      20      21      22      23
   LTS     │       │       │       LTS     │       │
   │       │       │       │       │       │       │
   │       6mo     6mo     6mo     │       6mo     6mo
   │     support support support   │     support support
   │                               │
   └── Supported until             └── Supported until
       Sep 2029                        Sep 2031 (est.)

  Non-LTS (18, 19, 20, 22, 23):
  ┌──────────────────┐
  │ 6 months support │ → then you MUST upgrade
  └──────────────────┘

  LTS (17, 21, 25):
  ┌──────────────────────────────────────────────┐
  │ 5+ years of security patches                 │
  └──────────────────────────────────────────────┘
```

### What LTS includes

| What you get | LTS | Non-LTS |
|-------------|-----|---------|
| **Security patches** | Years | 6 months |
| **Bug fixes** | Years | 6 months |
| **Performance updates** | Yes | Yes |
| **Community testing** | Extensive | Limited |
| **Library support** | Guaranteed | Best effort |
| **Docker images** | Always maintained | Quickly abandoned |
| **Production readiness** | From day one | After stabilization |

### LTS in the broader ecosystem

LTS is not just a Java concept. Many projects follow the same pattern:

| Software | LTS Versions | Support Period |
|----------|-------------|----------------|
| **Java** | 17, 21, 25 | 5+ years |
| **Node.js** | 18, 20, 22 | 30 months |
| **Ubuntu** | 22.04, 24.04 | 5 years |
| **PostgreSQL** | Each major version | 5 years |
| **.NET** | 6, 8 | 3 years |

### The LTS upgrade path

```
  volta on Java 17 LTS (current support until Sep 2029)
       │
       │  When Java 25 LTS releases (Sep 2025):
       │  - Test volta on Java 25
       │  - Verify dependencies work
       │  - Migrate at your pace
       │  - Java 21 still supported
       │
       ▼
  volta on Java 21 LTS ← current
       │
       │  When Java 29 LTS releases (~2027):
       │  - Same process
       │
       ▼
  volta on Java 25 LTS (future)
```

---

## How does volta-auth-proxy use it?

### Java 21 LTS as volta's foundation

volta-auth-proxy requires Java 21, which was released in September 2023 as an LTS version. This gives volta:

```
  ┌────────────────────────────────────────────────┐
  │  Java 21 LTS Benefits for volta                │
  │                                                 │
  │  Security patches:    Until Sep 2029+           │
  │  Virtual threads:     Better concurrency        │
  │  Pattern matching:    Cleaner code              │
  │  Record classes:      Less boilerplate          │
  │  Text blocks:         Readable SQL/templates    │
  │  Sealed classes:      Type safety               │
  │  Library support:     All major libs tested     │
  │  Docker images:       eclipse-temurin:21-jre    │
  └────────────────────────────────────────────────┘
```

### pom.xml configuration

```xml
<properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>
```

### Why not Java 22, 23, or 24?

| Version | Type | Support ends | Why not? |
|---------|------|-------------|----------|
| Java 22 | Non-LTS | Mar 2025 | Already unsupported |
| Java 23 | Non-LTS | Sep 2025 | Only 6 months of patches |
| Java 24 | Non-LTS | Mar 2026 | Only 6 months of patches |
| **Java 21** | **LTS** | **Sep 2029+** | **Stable, long-term patches** |

For an authentication proxy that handles security-critical operations, stability and long-term security patches outweigh access to the latest language features.

### LTS and Maven dependency stability

Using an LTS Java version also means more stable [dependencies](dependencies.md):

```
  Javalin 6.x    → tested on Java 21 LTS ✓
  HikariCP 5.x   → tested on Java 21 LTS ✓
  Flyway 10.x    → tested on Java 21 LTS ✓
  Caffeine 3.x   → tested on Java 21 LTS ✓

  These libraries prioritize LTS compatibility.
  Non-LTS Java versions may have untested edge cases.
```

---

## Common mistakes and attacks

### Mistake 1: Using non-LTS Java in production

Running Java 23 in production means you get security patches for only 6 months. After that, any discovered vulnerability (like a JVM escape or a crypto flaw) will not be patched for your version.

### Mistake 2: Never upgrading LTS versions

LTS does not mean "use forever." Java 11 LTS is nearing end of support. Plan your LTS upgrades -- do not wait until the old version is unsupported.

### Mistake 3: Mixing Java versions across environments

Development on Java 24, production on Java 21 means code that compiles locally might use features not available in production. Pin the same LTS version everywhere.

### Mistake 4: Confusing LTS with "no updates needed"

LTS still releases quarterly updates (e.g., 21.0.1, 21.0.2, 21.0.3). These contain security patches you must apply. LTS means the major version is stable, not that you never update.

### Mistake 5: Choosing LTS for the wrong reasons

If you are building a personal project with no users, the latest non-LTS version is fine. LTS matters for production software with real users and security requirements.

---

## Further reading

- [java.md](java.md) -- The programming language volta uses.
- [jvm.md](jvm.md) -- The runtime that Java LTS provides.
- [runtime.md](runtime.md) -- How the JVM executes volta's code.
- [dependencies.md](dependencies.md) -- Why LTS improves dependency stability.
- [what-is-production-ready.md](what-is-production-ready.md) -- LTS is a production-readiness criterion.
