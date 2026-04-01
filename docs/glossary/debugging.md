# Debugging

[日本語版はこちら](debugging.ja.md)

---

## What is it?

Debugging is the process of finding and fixing bugs -- errors or unexpected behavior in software. The name comes from the (possibly apocryphal) story of a literal moth found in a computer relay in 1947. Today, "debugging" means any investigation into why software is not doing what you expect it to do.

Think of it like being a detective. Something went wrong (the crime). You have clues: error messages (witness statements), log files (surveillance footage), stack traces (fingerprints). Your job is to piece together what happened, find the culprit (the bug), and fix it (bring it to justice). The quality of your clues determines whether this takes 5 minutes or 5 hours.

---

## Why "debug in one place" matters

The single hardest thing about debugging is figuring out WHERE the problem is. Once you know the location, fixing it is usually straightforward. The search is the hard part.

In a single-process system, "where" is always somewhere in one codebase:

```
Single process debugging:
  1. Error occurs → stack trace points to SessionService.java:47
  2. Open the file → read the code → understand the bug
  3. Fix it → run tests → deploy
  Time: minutes to hours
```

In a multi-service system, "where" could be anywhere across multiple codebases, networks, and configurations:

```
Multi-service debugging:
  1. Error occurs → "upstream connect error"
  2. Which service? Check ForwardAuth service logs → "timeout calling session service"
  3. Check session service logs → "connection refused to database"
  4. Wait, is it the database? Or the network? Or DNS? Or the connection pool?
  5. Check database → it's fine
  6. Check network → routing looks OK
  7. Check DNS → oh, a DNS cache had a stale entry
  8. Fix DNS config → restart → hope it was actually the problem
  Time: hours to days
```

The multi-service investigation required checking 5 different systems before finding a DNS issue that had nothing to do with any of the services' code. This is real life in microservice architectures.

---

## How multiple services make debugging harder

### Log correlation

Each service produces its own logs. To trace a single request across services, you need:

1. A unique request ID that passes through all services
2. Centralized log aggregation (e.g., ELK stack, Datadog)
3. Synchronized clocks across all servers
4. The patience to search across thousands of log lines

In a single process, all logs are in one place, in order, with the same timestamp source.

### Error context

When an error occurs in a single process, the stack trace includes the full chain of calls. You see the original request, every function it passed through, and exactly where it failed.

When an error crosses a network boundary, context is lost. You see "connection timed out" -- but you do not see what the remote service was doing when it timed out. You have to go find that information in another log, another system, another tool.

### Reproduction

To reproduce a bug in a single process, you run the process locally and send the same request. To reproduce a bug in a multi-service system, you need to run all the services, configure them to talk to each other, set up the right network conditions, and hope the timing-dependent bug shows up again.

---

## volta's single-process advantage

volta was designed so that every auth-related issue can be investigated in one place:

```
Any auth problem in volta:
  1. Check volta's log → see the request, the error, the stack trace
  2. The stack trace points to the exact line of code
  3. Reproduce locally: run volta + Postgres, send the same request
  4. Debug with breakpoints, step through code, inspect variables
  5. Fix → test → deploy

No log aggregation needed.
No distributed tracing needed.
No service mesh debugging needed.
No "which service is broken?" guessing needed.
```

This is not a minor convenience. For a solo developer or small team maintaining an auth system, the difference between "open one log file" and "correlate logs across 4 services" is the difference between a productive evening and a sleepless night.

---

## Debugging auth is especially high-stakes

Auth bugs are not like UI bugs. A CSS misalignment is annoying. An auth bug can mean:

- Users locked out of all applications (availability)
- Unauthorized access to sensitive data (security)
- Session leaks across tenants (data breach)
- Incorrect role assignments (privilege escalation)

When these bugs happen (and they will), you need to find and fix them FAST. Every minute of auth downtime is a minute when all protected applications are inaccessible. The speed of debugging directly affects your system's availability and security.

volta's single-process design means auth bugs are found faster. Faster debugging means shorter outages. Shorter outages mean happier users and fewer security incidents.

---

## In volta-auth-proxy

volta runs as a single process so that every auth issue -- from login failures to session bugs to JWT errors -- can be debugged in one log, one stack trace, one codebase, without distributed tracing or multi-service log correlation.

---

## Further reading

- [stack-trace.md](stack-trace.md) -- The primary debugging tool and why readable ones matter.
- [single-process.md](single-process.md) -- The architecture that enables single-place debugging.
- [microservice.md](microservice.md) -- The architecture that makes debugging harder.
- [fault-propagation.md](fault-propagation.md) -- Why auth bugs affect everything.
