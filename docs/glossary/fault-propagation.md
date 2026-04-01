# Fault Propagation

[日本語版はこちら](fault-propagation.ja.md)

---

## What is it?

Fault propagation is when a failure in one part of a system causes failures in other parts -- like a chain reaction. One thing breaks, and because other things depend on it, they break too, and the things that depend on THOSE break, and soon everything is down.

Think of it like a row of dominoes. You knock over the first one, and it hits the second, which hits the third, and within seconds the entire row is flat. The first domino is the "fault." The chain of falling dominoes is "propagation." The whole row being flat is "cascading failure."

In real life: a power plant goes offline, the grid becomes overloaded, other plants shut down to protect themselves, and now an entire region has a blackout. One fault propagated to bring down the whole system.

---

## Why auth failures are especially dangerous

Authentication is unique among system components because it sits on the critical path of EVERY request. If your search feature breaks, users cannot search -- but they can still use everything else. If your auth system breaks, users cannot access ANYTHING.

```
Search failure:
  ┌─────────┐     ┌─────────┐     ┌─────────┐
  │ Wiki    │ OK  │ Admin   │ OK  │ Chat    │ OK
  │         │     │         │     │         │
  └─────────┘     └─────────┘     └─────────┘
  Search is broken, but everything else works.

Auth failure:
  ┌─────────┐     ┌─────────┐     ┌─────────┐
  │ Wiki    │ ✗   │ Admin   │ ✗   │ Chat    │ ✗
  │ 401     │     │ 401     │     │ 401     │
  └─────────┘     └─────────┘     └─────────┘
  NOTHING works. Every app depends on auth.
```

This is fault propagation by nature. Auth is a single point that all applications depend on. When it fails, the failure propagates to every protected application simultaneously.

---

## Why more components means more propagation paths

Some architects argue: "Split auth into microservices, so if one piece fails, the others keep working." This sounds logical but misses a critical point about auth: the pieces are not independent.

```
Microservice auth:
  ┌──────────┐    ┌──────────┐    ┌──────────┐
  │ OIDC     │───►│ Session  │───►│ JWT      │
  │ Service  │    │ Service  │    │ Service  │
  └──────────┘    └──────────┘    └──────────┘

  If Session Service dies:
  - OIDC Service cannot create sessions → login fails
  - JWT Service cannot validate sessions → ForwardAuth fails
  - ALL apps become inaccessible

  Splitting into 3 services did NOT prevent propagation.
  It just added 2 more things that can fail.
```

Each connection between services is a potential failure point: network timeouts, DNS failures, connection pool exhaustion, serialization bugs. More services means more connections means more things that can break.

```
Single process (volta):
  Failure points: volta process + database = 2 things

Microservice auth:
  Failure points: OIDC service + Session service + JWT service
                  + network between them + load balancers
                  + service discovery + database(s) = 7+ things
```

volta has 2 things that can fail. A microservice auth system has 7 or more. Each additional failure point is another domino in the chain.

---

## volta's approach: minimize components, minimize propagation

volta's strategy against fault propagation is simple: have fewer things that can fail.

1. **One process.** No internal network failures possible. Method calls within a process do not have timeouts, DNS failures, or connection pool issues.

2. **One database.** No distributed data consistency problems. No "Session Service has the data but JWT Service cannot reach it" scenarios.

3. **No external service dependencies at runtime.** volta does not call Auth0, Google, or any external service during ForwardAuth verification. The OIDC IdP is only contacted during login -- not on every request.

4. **Graceful degradation.** If the database is temporarily unreachable, cached sessions can still serve ForwardAuth checks. The blast radius of a database blip is reduced.

```
volta's fault propagation surface:
  ┌──────────────┐     ┌──────────┐
  │ volta-auth-  │────►│ Postgres │
  │ proxy        │     │          │
  └──────────────┘     └──────────┘

  What can fail:
  1. volta process crashes → restart (200ms startup)
  2. Postgres unreachable → cached sessions still work briefly
  3. Both fail → auth is down (but this is true for ANY auth system)
```

---

## In volta-auth-proxy

volta minimizes fault propagation by running as a single process with a single database dependency, eliminating the network connections, service discovery, and distributed coordination that create additional failure paths in microservice auth systems.

---

## Further reading

- [single-process.md](single-process.md) -- Why one process has fewer failure points.
- [microservice.md](microservice.md) -- The architecture that adds more propagation paths.
- [external-dependency.md](external-dependency.md) -- Why fewer dependencies means fewer faults.
- [network-hop.md](network-hop.md) -- Each hop is a potential failure point.
