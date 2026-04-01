# What Is Scalability, Actually?

[日本語版はこちら](what-is-scalability.ja.md)

---

## The misconception

"It scales."

This might be the most meaningless sentence in software engineering. It communicates nothing. Scales how? From 10 users to 100? From 100 to 10 million? Scales reads or writes? Scales with money or with engineering time? Scales until you run out of RAM or until you run out of patience?

When someone says "it scales," what they usually mean is "I have not yet hit a limit, therefore I assume there is no limit." That is not scalability. That is optimism.

---

## What scalability actually means

Scalability is the ability of a system to handle increased load by adding resources, without requiring a fundamental redesign.

The key phrase is "without requiring a fundamental redesign." Every system can handle more load if you rewrite it. Scalability means you can handle more load by doing something simpler: adding more machines, adding more memory, adding more database replicas.

### Horizontal vs. vertical scaling

**Vertical scaling (scale up):** Make the one machine bigger. More CPU, more RAM, faster disk. This is simple, predictable, and has a hard ceiling. You cannot buy a server with 10 TB of RAM (well, you can, but you probably should not).

**Horizontal scaling (scale out):** Add more machines. Run three instances instead of one. Put a load balancer in front. This is theoretically unlimited but practically complex. Now you have distributed state, cache coherence, and network partitions to worry about.

Most real systems use both. You scale vertically until it gets expensive, then you scale horizontally.

### What actually breaks when you scale

Here is what nobody tells you: scaling a stateless API server is the easy part. The hard parts are:

1. **The database.** Your app might handle 10x the requests, but if they all hit the same PostgreSQL instance, you have not scaled anything. You have just moved the bottleneck.

2. **State.** Sessions, caches, locks, rate limit counters -- anything that lives in memory on one server becomes a problem when you have two servers. User logs in on server A, next request goes to server B, session not found. Welcome to distributed systems.

3. **Consistency.** If you have two instances and a user changes their role in one instance, how long until the other instance knows? If the answer is "it depends," congratulations, you now have eventual consistency to think about.

4. **Deployment.** Deploying one server means one deployment. Deploying three servers means coordinating three deployments, handling version mismatches during rolling updates, and managing database migrations that are compatible with both the old and new code.

---

## Why volta chose single-instance for Phase 1

volta-auth-proxy runs as a single instance in Phase 1. Some people look at that and think "it does not scale." They are wrong. What it means is: volta made an honest assessment of the tradeoffs and chose simplicity.

Here is the reasoning:

**The session store is in PostgreSQL.** Sessions are not in memory. They are in the database. This means a second volta instance could read the same sessions. The data layer is already prepared for horizontal scaling.

**JWT verification is stateless.** Downstream apps verify JWTs using volta's JWKS endpoint. They do not call volta on every request. This means volta's request volume is limited to login flows, session checks (ForwardAuth), and token refreshes -- not every API call in the entire system.

**The bottleneck is not volta.** For a typical SaaS with 10,000 active users, the ForwardAuth endpoint might get called a few hundred times per second at peak. A single Javalin instance on a modest server can handle that easily. The bottleneck will be your application database or your frontend CDN long before it is volta.

**Premature horizontal scaling creates real problems.** If volta ran as three instances in Phase 1, it would need to handle: distributed rate limiting (Redis or similar), cache invalidation for JWKS key rotation, coordinated session cleanup, and health check routing. Each of these is solvable, but each adds complexity, dependencies, and failure modes. For what? To handle a load that one instance can serve?

This is not a scalability failure. This is engineering honesty: solve the problems you actually have, and prepare (but do not prematurely build) for the problems you might have later.

---

## The scaling plan volta does have

volta is not ignoring scalability. It is sequencing it:

- **Database connection pooling** (HikariCP) is already in place. This is the first thing that breaks when load increases.
- **Sessions in PostgreSQL** means adding a read replica gives you horizontal read scaling for session verification.
- **JWKS caching** by downstream apps means volta itself is not in the hot path for most API requests.
- **Stateless JWT verification** means adding more downstream app instances requires zero changes to volta.
- **The architecture is ready.** When the time comes, adding a second volta instance behind a load balancer is a configuration change, not a redesign. The hard decisions (external session store, stateless token verification, database-backed state) were made up front.

---

## The honest conversation about scalability

When someone asks "does it scale?" -- the honest answer requires context:

- **For 100 users?** Volta on a $5/month VPS. Done.
- **For 10,000 users?** Volta on a decent server with PostgreSQL tuning. Done.
- **For 100,000 users?** Probably still single-instance with a beefier database. Monitor and evaluate.
- **For 1,000,000 users?** Now we are talking about multiple instances, read replicas, maybe Redis for rate limiting. This is Phase 2+ territory and it is a known, planned evolution.

Anyone who tells you their system "scales to millions" without telling you what that costs in complexity, operational burden, and money is selling you something.

---

## It is OK not to know this

If you thought scalability meant "can handle lots of users," you had the right general idea but were missing the details that matter. Scalability is about how you handle increased load and what breaks along the way. The engineers who impress me are not the ones who say "it scales." They are the ones who say "here is exactly what will break first, and here is our plan for when it does."

Now you know.
