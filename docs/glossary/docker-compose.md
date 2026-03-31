# Docker Compose

[日本語版はこちら](docker-compose.ja.md)

---

## What is it in one sentence?

Docker Compose is a tool that lets you start multiple services (like a database, a mail server, and a message queue) all at once with a single command, using a configuration file that describes how they fit together.

---

## The band rehearsal analogy

Imagine you are organizing a band rehearsal. You need:

- A drummer (database -- keeps the rhythm/data)
- A bassist (message queue -- keeps things flowing)
- A keyboard player (email server -- handles communication)
- A guitarist (your app -- the main performer)

Without Docker Compose, you would have to call each musician individually, tell them where to go, what time to arrive, what volume to play at, and make sure they all show up to the same studio. If one person does not show up, the rehearsal falls apart.

With Docker Compose, you write one setlist (a `docker-compose.yml` file) that says:
- Who is playing (which services)
- Where they sit (which ports)
- How loud (what configuration)
- What order they start in (dependencies)

Then you say "go" once (`docker compose up`), and everyone starts playing together.

---

## What is Docker (the basics)?

Before we understand Docker Compose, we need to understand Docker itself. Docker lets you run software in "containers." A container is like a sealed box that contains everything an application needs to run:

- The application code
- All libraries and dependencies
- The operating system files it needs
- Configuration

The beauty is: it does not matter what computer you run it on. The container works the same everywhere. This solves the infamous "it works on my machine" problem.

```
  Without Docker:
  "I installed PostgreSQL 16 on my Mac, but you have
   PostgreSQL 14 on Ubuntu, and production has PostgreSQL 15
   on Amazon Linux. Everything works differently."

  With Docker:
  "We all run the same container: postgres:16-alpine.
   It's identical everywhere."
```

---

## volta's docker-compose.yml explained

volta-auth-proxy uses Docker Compose to run its supporting services. Let us read the file piece by piece:

```yaml
services:
  postgres:                              # Service name: "postgres"
    image: postgres:16-alpine            # Use PostgreSQL 16 (Alpine Linux, small)
    container_name: volta-auth-postgres  # Give it a friendly name
    ports:
      - "54329:5432"                     # Map host port 54329 to container port 5432
    environment:                         # Environment variables for this container
      POSTGRES_DB: volta_auth            # Create a database called "volta_auth"
      POSTGRES_USER: volta               # Create a user called "volta"
      POSTGRES_PASSWORD: volta           # Set the password to "volta"
    volumes:
      - volta_auth_pgdata:/var/lib/postgresql/data  # Store data persistently
    healthcheck:                         # Check if the database is ready
      test: ["CMD-SHELL", "pg_isready -U volta -d volta_auth"]
      interval: 5s
      timeout: 5s
      retries: 10
```

What this means in plain language: "Start a PostgreSQL 16 database. Call it volta-auth-postgres. Make it accessible on port 54329 on my computer. Create a database called volta_auth with username volta and password volta. Save the data so it survives restarts. Check every 5 seconds that it is running."

The file also defines other services like Redis (for caching), Mailpit (for testing emails), Kafka (for event streaming), and Elasticsearch (for search and audit logs).

---

## How to read docker-compose.yml

Here is a cheat sheet for the most common settings:

| Setting | What it means | Example |
|---|---|---|
| `image` | Which software to run | `postgres:16-alpine` = PostgreSQL version 16 |
| `container_name` | A friendly name for the container | `volta-auth-postgres` |
| `ports` | "host:container" port mapping | `"54329:5432"` = access via port 54329 on your machine |
| `environment` | Settings passed to the container | `POSTGRES_DB: volta_auth` = create this database |
| `volumes` | Persistent storage | Data survives when you restart the container |
| `healthcheck` | How to check if the service is ready | Runs a command every N seconds |

---

## Common commands

```bash
# Start all services defined in docker-compose.yml
docker compose up -d
# (-d means "detached" -- run in the background)

# See what is running
docker compose ps

# View logs from all services
docker compose logs

# View logs from just postgres
docker compose logs postgres

# Stop all services
docker compose down

# Stop all services AND delete all data
docker compose down -v
# (the -v flag removes volumes -- your database data will be gone!)
```

---

## A simple example

When you start working on volta-auth-proxy for the first time:

```
  Step 1: Start the services
  $ docker compose up -d

  Docker starts:
    ✓ volta-auth-postgres    (database on port 54329)
    ✓ volta-auth-redis       (cache on port 6379)
    ✓ volta-auth-mailpit     (fake email on port 8025)
    ✓ volta-auth-kafka       (events on port 9092)
    ✓ volta-auth-elasticsearch (search on port 9200)

  Step 2: Start volta-auth-proxy itself
  $ ./mvnw spring-boot:run

  volta connects to postgres on port 54329, redis on port 6379, etc.
  Everything works because Docker Compose made sure all services are running.

  Step 3: When you're done for the day
  $ docker compose down
  (Everything stops. Your data is saved in volumes.)
```

---

## Further reading

- [environment-variable.md](environment-variable.md) -- The settings that configure both Docker containers and volta itself.
- [database-migration.md](database-migration.md) -- How volta sets up its database tables inside the PostgreSQL container.
