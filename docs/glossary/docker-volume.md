# Docker Volume

[日本語版はこちら](docker-volume.ja.md)

---

## What is it?

A Docker volume is persistent storage for Docker containers. When a container is deleted, everything inside it is lost -- unless you store data in a volume. Volumes exist outside the container's filesystem, so they survive container restarts, updates, and even complete rebuilds.

Think of it like a USB drive plugged into a laptop. If the laptop breaks, you lose whatever was on its hard drive -- but the USB drive survives. You can plug it into a new laptop and all your files are still there. A Docker volume is the USB drive for your container. The container can be destroyed and recreated, but the volume keeps your data safe.

Docker volumes are the standard way to persist data in containerized applications. Databases, configuration files, uploaded files, logs -- anything that needs to survive a container restart goes in a volume.

---

## Why does it matter?

Without volumes, containers are ephemeral by design. Every time you update your application (pull a new image, recreate the container), all data inside the container is gone. This is intentional -- containers are meant to be disposable. But your data is not.

Consider the consequences of running a database inside a container without a volume:

- Deploy a new version of your application: database wiped.
- Docker restarts after a crash: database wiped.
- Scale from 1 container to 2: each has its own empty database.

Volumes solve this by separating the lifecycle of data from the lifecycle of containers.

---

## How does it work?

### Types of Docker storage

| Type | Persistence | Use case |
|------|------------|----------|
| **Container layer** | Deleted with container | Temporary files, build artifacts |
| **Bind mount** | Host directory mapped into container | Development (live code reload) |
| **Named volume** | Managed by Docker, persists independently | Production data (databases, uploads) |
| **tmpfs mount** | In-memory only, lost on restart | Secrets, sensitive temp files |

### How volumes work

```
  Host machine filesystem
  ┌──────────────────────────────────────────┐
  │                                          │
  │  /var/lib/docker/volumes/               │
  │  └── volta-data/                        │
  │      └── _data/                         │
  │          └── volta.db  (SQLite file)    │
  │                                          │
  └──────────────────────────────────────────┘
           │
           │  mounted at
           ▼
  Container filesystem
  ┌──────────────────────────────────────────┐
  │                                          │
  │  /app/                                   │
  │  ├── volta-auth-proxy.jar  (read-only)  │
  │  └── data/                              │
  │      └── volta.db  ← volume mount       │
  │                                          │
  └──────────────────────────────────────────┘
```

### Docker volume commands

```bash
  # Create a named volume
  docker volume create volta-data

  # List volumes
  docker volume ls

  # Inspect a volume (see where it lives on disk)
  docker volume inspect volta-data

  # Remove a volume (WARNING: deletes all data)
  docker volume rm volta-data

  # Run a container with a volume
  docker run -v volta-data:/app/data volta-auth-proxy
```

### Docker Compose volume configuration

```yaml
  version: "3.8"
  services:
    volta-auth-proxy:
      image: volta-auth-proxy:latest
      volumes:
        - volta-data:/app/data    # Named volume for persistence

  volumes:
    volta-data:                    # Volume declaration
      driver: local                # Default driver (host filesystem)
```

### Volume lifecycle

```
  Create container with volume
       │
       ▼
  Container writes data to /app/data/volta.db
       │
       ▼
  Container stops / restarts
  (Data in volume is SAFE)
       │
       ▼
  Container is deleted (docker rm)
  (Data in volume is STILL SAFE)
       │
       ▼
  New container created with same volume
  (Data is available immediately)
       │
       ▼
  Volume explicitly deleted (docker volume rm)
  (NOW the data is gone)
```

---

## How does volta-auth-proxy use it?

volta uses a Docker volume to persist its SQLite database file. Without the volume, every container restart would create a fresh, empty database -- losing all users, tenants, sessions, and invitations.

### volta's volume configuration

```yaml
  services:
    volta-auth-proxy:
      image: volta-auth-proxy:latest
      volumes:
        - volta-data:/app/data
      environment:
        VOLTA_DB_PATH: /app/data/volta.db

  volumes:
    volta-data:
```

### What is stored in the volume

| File | Purpose | Impact if lost |
|------|---------|---------------|
| `volta.db` | SQLite database (users, tenants, sessions, invitations, roles) | Complete data loss. All users must re-register. |
| `volta.db-wal` | SQLite Write-Ahead Log | Transaction in progress may be lost |
| `volta.db-shm` | SQLite shared memory file | Recreated automatically on restart |

### Why SQLite + volume works for volta

SQLite stores everything in a single file. This makes the volume configuration trivially simple -- mount one directory, and the entire database is persisted. No need for:

- A separate database container
- Network configuration between app and database
- Database credentials management
- Connection pooling configuration

This simplicity is a Phase 1 advantage. When volta moves to PostgreSQL in Phase 2, the database will run in its own container with its own volume.

### Backup strategy

Because the database is a single file in a known volume location, backups are straightforward:

```bash
  # Backup the SQLite database
  docker run --rm -v volta-data:/data -v $(pwd):/backup \
    alpine cp /data/volta.db /backup/volta-backup-$(date +%Y%m%d).db

  # Or use SQLite's built-in backup command
  docker exec volta-auth-proxy \
    sqlite3 /app/data/volta.db ".backup /app/data/volta-backup.db"
```

---

## Common mistakes and attacks

### Mistake 1: Forgetting the volume on first deployment

The most common issue: running `docker run volta-auth-proxy` without `-v`. Everything works until the container restarts, and then all data is gone. Always configure the volume before the first user registers.

### Mistake 2: Bind mount vs. named volume confusion

```yaml
  # Bind mount (maps a HOST directory):
  volumes:
    - ./local-data:/app/data    # . means host path

  # Named volume (Docker-managed):
  volumes:
    - volta-data:/app/data       # no . prefix
```

Bind mounts are great for development (live code changes). Named volumes are better for production (Docker manages location, permissions, and cleanup).

### Mistake 3: Deleting volumes accidentally

`docker system prune --volumes` deletes ALL unused volumes. This can wipe your database. Use `docker volume prune` carefully, and always have backups.

### Mistake 4: Permissions issues

The container process runs as a specific user (often root or a custom user). The volume directory must be writable by that user. Permission mismatches cause "read-only filesystem" or "permission denied" errors.

### Mistake 5: Not backing up volumes

Volumes are persistent, but they are not backed up automatically. If the host machine's disk fails, the volume data is lost. Implement regular backups to an external location.

---

## Further reading

- [docker-label.md](docker-label.md) -- Metadata on containers, often configured alongside volumes.
- [health-check.md](health-check.md) -- Verifying the container is healthy after restart with volume data.
- [Docker Volumes Documentation](https://docs.docker.com/storage/volumes/) -- Official reference.
- [SQLite Backup API](https://www.sqlite.org/backup.html) -- How to safely backup a live SQLite database.
