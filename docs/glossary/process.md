# Process

[日本語版はこちら](process.ja.md)

---

## What is it?

A process is a running instance of a program. When you execute `java -jar volta-auth-proxy.jar`, the operating system creates a process. That process gets its own memory space, its own CPU time, and its own process ID (PID). It runs independently of other processes on the same machine.

Think of it like a worker at a desk. The program (the JAR file) is like a job description -- it describes what the worker should do. The process is the actual worker sitting at a desk, doing the work. You can have multiple workers following the same job description (multiple processes running the same program), and each worker has their own desk and papers (memory space).

When you run volta-auth-proxy, it starts as a single process. Inside that process, the [JVM](jvm.md) manages multiple threads (sub-tasks), but from the OS perspective, it is one process. This is the [single-process](single-process.md) architecture.

---

## Why does it matter?

Understanding processes matters because:

- **Resource management** -- Each process consumes memory and CPU. volta runs as a single process, keeping resource usage predictable.
- **Lifecycle management** -- Starting, stopping, and monitoring volta means managing one process. `kill <PID>` stops it. `docker stop` stops its container process.
- **Isolation** -- Processes are isolated from each other. If volta crashes, it does not take down other applications on the same server.
- **Environment** -- Each process has its own [environment variables](environment-variable.md). volta reads its configuration from the process environment.
- **Port binding** -- Only one process can listen on a given [port](port.md). If volta is running on port 7070, no other process can use that port.

---

## How does it work?

### Process lifecycle

```
  Program (file on disk)              Process (running in memory)
  ──────────────────────              ─────────────────────────────

  volta-auth-proxy.jar                PID: 12345
  (just a file, doing nothing)        State: Running
                                      Memory: 256MB
       │                              CPU: 2.3%
       │                              Port: 7070
       │    java -jar volta.jar       Threads: 24
       └────────────────────────────> (accepting HTTP requests)
                                           │
                                           │  kill 12345 / Ctrl+C
                                           │
                                           ▼
                                      State: Terminated
                                      Memory: freed
                                      Port: released
```

### Process vs. thread

```
  ┌───────────────────────────────────────────┐
  │              Process (PID: 12345)          │
  │              volta-auth-proxy              │
  │                                            │
  │  ┌────────┐  ┌────────┐  ┌────────┐      │
  │  │Thread 1│  │Thread 2│  │Thread 3│ ...   │
  │  │(main)  │  │(HTTP   │  │(HTTP   │       │
  │  │        │  │ req 1) │  │ req 2) │       │
  │  └────────┘  └────────┘  └────────┘       │
  │                                            │
  │  Shared memory space                       │
  │  (all threads see the same data)           │
  │                                            │
  │  ┌─────────────────────────────────────┐   │
  │  │  HikariCP pool (shared)             │   │
  │  │  Caffeine cache (shared)            │   │
  │  │  RSA key pair (shared)              │   │
  │  └─────────────────────────────────────┘   │
  └───────────────────────────────────────────┘
```

A process is a container. Threads are the workers inside that container. Multiple threads share the same memory, which is why [Caffeine cache](caffeine-cache.md) and [HikariCP](hikaricp.md) pools work efficiently -- all threads access the same cache and pool.

### Process identification

Every process gets a unique PID (Process ID):

```bash
# Find volta's process
ps aux | grep volta-auth-proxy
# Output: user  12345  2.3  5.1  java -jar volta-auth-proxy.jar

# Or if running in Docker
docker ps
# Output: CONTAINER_ID  IMAGE  COMMAND  STATUS  PORTS
#         abc123        volta  java...  Up 2h   0.0.0.0:7070->7070
```

### Process environment

Each process inherits environment variables from its parent:

```bash
# These environment variables are available INSIDE the volta process
export VOLTA_PORT=7070
export GOOGLE_CLIENT_ID=abc123.apps.googleusercontent.com
export DATABASE_URL=jdbc:postgresql://localhost:5432/volta

java -jar volta-auth-proxy.jar
# volta reads these with System.getenv("VOLTA_PORT"), etc.
```

### Signals

The OS communicates with processes via signals:

| Signal | Meaning | Effect on volta |
|--------|---------|----------------|
| SIGTERM (kill) | "Please shut down gracefully" | volta closes connections, saves state, exits |
| SIGKILL (kill -9) | "Die immediately" | Process killed, no cleanup |
| SIGINT (Ctrl+C) | "User wants to stop" | Same as SIGTERM |
| SIGHUP | "Terminal disconnected" | Usually ignored by servers |

---

## How does volta-auth-proxy use it?

### Single-process architecture

volta runs as a single [JVM](jvm.md) process. Everything -- HTTP server, session management, JWT services, database connections, cache -- runs inside one process:

```
  ┌─────────────────────────────────────────────────┐
  │         volta-auth-proxy (single process)        │
  │                                                  │
  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
  │  │  Javalin  │  │  Session  │  │  JwtService  │  │
  │  │  (HTTP)   │  │  Manager  │  │  (RS256)     │  │
  │  └──────────┘  └──────────┘  └──────────────┘  │
  │                                                  │
  │  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
  │  │ HikariCP │  │ Caffeine │  │  Flyway       │  │
  │  │ (DB pool)│  │ (cache)  │  │  (migrations) │  │
  │  └──────────┘  └──────────┘  └──────────────┘  │
  │                                                  │
  │  One PID. One JVM. One process.                 │
  └─────────────────────────────────────────────────┘
```

This simplicity is intentional. No message queues, no worker processes, no process managers. One process handles everything.

### Process startup sequence

When `java -jar volta-auth-proxy.jar` executes:

```
  1. JVM starts
  2. Main.main() runs
  3. Read environment variables (config)
  4. Initialize HikariCP (database connection pool)
  5. Run Flyway migrations (create/update tables)
  6. Load RSA key pair (for JWT signing)
  7. Initialize Caffeine cache
  8. Start Javalin (HTTP server on port 7070)
  9. Register routes and middleware
  10. Process is ready -- accepting requests
```

### Process in Docker

When running in [Docker](docker.md), the volta process is PID 1 inside the container:

```bash
docker run -d \
  -e VOLTA_PORT=7070 \
  -e DATABASE_URL=jdbc:postgresql://db:5432/volta \
  -p 7070:7070 \
  volta-auth-proxy

# Inside the container:
# PID 1 = java -jar volta-auth-proxy.jar
```

Docker maps [port](port.md) 7070 on the host to port 7070 in the container, forwarding traffic to the volta process.

### Graceful shutdown

When volta receives SIGTERM:

```
  SIGTERM received
       │
       ▼
  1. Stop accepting new HTTP requests
  2. Finish in-flight requests (up to timeout)
  3. Close HikariCP connections
  4. Flush any pending writes
  5. Exit with code 0
```

---

## Common mistakes and attacks

### Mistake 1: Running multiple processes on the same port

```bash
java -jar volta-auth-proxy.jar &  # Process 1 on port 7070
java -jar volta-auth-proxy.jar    # Process 2 fails: port already in use!
```

Only one process can bind to a port. If you need multiple instances, use different ports or a load balancer.

### Mistake 2: Using kill -9 as the default

`kill -9` (SIGKILL) terminates the process immediately without cleanup. Database connections are not closed, in-flight requests get no response, and session data might be lost. Always use `kill` (SIGTERM) first and only use `-9` if the process is truly stuck.

### Mistake 3: Not monitoring the process

A process can crash silently. Without monitoring, nobody notices until users complain. Use a process manager (systemd, Docker with restart policy, etc.) to automatically restart crashed processes.

### Attack 1: Resource exhaustion

An attacker sends thousands of requests to exhaust the process's memory or threads:

```
  Attacker → 10,000 concurrent connections
           → Thread pool exhausted
           → Legitimate users get no response
```

Defense: Connection limits, [rate limiting](rate-limiting.md), and timeout configurations in Javalin/Jetty.

### Mistake 4: Running as root

Running the volta process as the root user means a vulnerability in volta could compromise the entire server. Always run as a non-root user. Docker best practice: add `USER 1000` in the Dockerfile.

---

## Further reading

- [single-process.md](single-process.md) -- volta's single-process architecture philosophy.
- [server.md](server.md) -- The machine that hosts the process.
- [port.md](port.md) -- How the process listens for network connections.
- [jvm.md](jvm.md) -- The runtime environment inside the process.
- [environment-variable.md](environment-variable.md) -- How the process receives configuration.
- [docker.md](docker.md) -- Running the process inside a container.
- [fat-jar.md](fat-jar.md) -- The file that becomes the process when executed.
