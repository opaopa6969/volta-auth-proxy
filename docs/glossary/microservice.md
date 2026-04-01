# Microservice

[日本語版はこちら](microservice.ja.md)

---

## What is it?

A microservice is a small, independent program that does one specific job and communicates with other small programs over the network to form a complete system. Instead of one big application, you have many small ones that work together.

Think of it like a restaurant kitchen. In a small restaurant, one chef does everything: prep, cook, plate, dessert. This is a monolith -- one person, all the work. In a large restaurant, there is a saucier (sauces), a grillardin (grills), a patissier (desserts), and a chef de cuisine (coordination). Each person does one job well. This is microservices -- many specialists, each focused.

The large kitchen can handle more orders and scale individual stations. But it also needs more communication ("Table 7's steak is ready, waiting on the sauce!"), more coordination, and things get chaotic when one station falls behind.

---

## Why microservices are popular

Microservices became popular because they solve real problems that large teams face:

1. **Independent deployment.** The team working on search can deploy without affecting the team working on payments.
2. **Technology choice.** The search team can use Python; the payments team can use Java.
3. **Independent scaling.** If search gets 100x more traffic than payments, you scale search without scaling payments.
4. **Fault isolation (in theory).** If the search service crashes, payments keep working.

For companies with hundreds of engineers building Netflix or Amazon, microservices make sense. The coordination cost is worth paying because the alternative -- 500 engineers working on one giant codebase -- is worse.

---

## Why volta chose NOT to be microservices

volta-auth-proxy could have been built as microservices:

```
Microservice approach (NOT what volta does):
┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐
│ OIDC      │  │ Session   │  │ JWT       │  │ ForwardAuth│
│ Service   │──│ Service   │──│ Service   │──│ Service    │
│ (login)   │  │ (storage) │  │ (signing) │  │ (verify)   │
└───────────┘  └───────────┘  └───────────┘  └───────────┘
     Network hop    Network hop    Network hop

Problems:
- 4 services to deploy, monitor, and debug
- 3 network hops per auth check (latency)
- If Session Service crashes, everything stops
- Error: "timeout connecting to JWT Service" -- where's the bug?
- 4 separate log streams to correlate
```

```
volta's approach (single process):
┌──────────────────────────────────────────┐
│ volta-auth-proxy                          │
│                                          │
│  OIDC → Session → JWT → ForwardAuth     │
│  (all in one process, method calls)      │
│                                          │
└──────────────────────────────────────────┘
     No network hops. One log. One stack trace.
```

### The trade-offs volta accepted

| Microservices advantage | volta's response |
|------------------------|------------------|
| Independent scaling | "Our target is thousands of users, not millions. One process is plenty." |
| Technology choice per service | "Everything is Java. That is the point." |
| Independent deployment | "We deploy one jar. That is simpler, not worse." |
| Fault isolation | "Auth failures propagate regardless. If session storage dies, all auth dies. Splitting into services does not help." |

### The trade-offs volta avoided

| Microservices problem | volta avoids it |
|----------------------|-----------------|
| Network latency between services | Zero -- method calls, not HTTP calls |
| Distributed debugging | One process, one stack trace, one log |
| Deployment complexity | One jar, one Docker container |
| Data consistency | One database, no distributed transactions |
| Operational overhead | One process to monitor, not four |

---

## When microservices make sense (and when they do not)

**Microservices make sense when:**
- You have 50+ engineers who need to work independently
- Different parts of the system have wildly different scaling needs
- You need different technology stacks for different components
- The system is so large that one codebase is unmanageable

**Microservices do NOT make sense when:**
- You have 1-10 engineers
- The system is small enough for one person to understand
- All components use the same technology
- The components are tightly related (like auth steps in a login flow)

volta's auth flow -- OIDC login, session creation, JWT issuance, ForwardAuth verification -- is a pipeline where each step depends on the previous one. Splitting it into services adds complexity without adding value. It is like having one chef chop the onion, then pass it to another chef to put it in the pot. The handoff takes more time than the work.

---

## In volta-auth-proxy

volta deliberately runs as a single process instead of microservices because every auth operation (OIDC, sessions, JWT, ForwardAuth) is part of one tightly connected pipeline where splitting into separate services would add network latency and debugging complexity without meaningful benefits at volta's target scale.

---

## Further reading

- [single-process.md](single-process.md) -- volta's single-process architecture in detail.
- [latency.md](latency.md) -- Why network hops between services add latency.
- [fault-propagation.md](fault-propagation.md) -- Why splitting auth into services does not prevent cascading failures.
- [network-hop.md](network-hop.md) -- What happens each time data crosses a network boundary.
