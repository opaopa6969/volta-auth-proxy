# Docker Label

[日本語版はこちら](docker-label.ja.md)

---

## What is it?

A Docker label is a key-value metadata pair attached to a Docker container, image, volume, or network. Labels do not affect how the container runs -- they are purely informational. However, other tools can read these labels and act on them, which makes labels a powerful mechanism for configuration without files.

Think of it like a name tag at a conference. The name tag does not change who you are, but it tells other people how to interact with you -- your name, your company, your role. Docker labels are name tags for containers. They tell tools like Traefik, Prometheus, or Watchtower how to interact with the container.

The most common use of Docker labels in modern deployments is **reverse proxy configuration**. Instead of writing a separate Nginx or Traefik config file, you put labels on your container that say "route traffic from this domain to this container on this port." The reverse proxy reads the labels and configures itself automatically.

---

## Why does it matter?

Labels enable **configuration-as-code** for infrastructure. Instead of maintaining separate configuration files for your reverse proxy, monitoring, logging, and orchestration tools, you declare everything in one place: the Docker Compose file.

Benefits:

- **Single source of truth**: The container's Compose file contains all configuration.
- **No config file drift**: Labels travel with the container definition. They cannot get out of sync.
- **Dynamic configuration**: Tools like Traefik watch for container changes and reconfigure automatically.
- **No restart required**: When you add a new service, Traefik picks it up without restart.

Without labels, adding a new service requires: updating the Traefik config file, reloading Traefik, hoping you did not break the other routes. With labels, you just add the new service with its labels, and Traefik configures itself.

---

## How does it work?

### Label syntax

```yaml
  # In Docker Compose:
  services:
    my-app:
      image: my-app:latest
      labels:
        - "com.example.description=My application"
        - "com.example.version=1.2.3"
        - "traefik.enable=true"
        - "traefik.http.routers.myapp.rule=Host(`app.example.com`)"
```

```bash
  # In Docker CLI:
  docker run --label com.example.version=1.2.3 my-app:latest
```

### Label conventions

| Prefix | Owner | Example |
|--------|-------|---------|
| `com.example.*` | Your organization | `com.example.team=platform` |
| `org.opencontainers.*` | OCI standard | `org.opencontainers.image.version=1.0` |
| `traefik.*` | Traefik proxy | `traefik.http.routers.app.rule=...` |
| `com.datadoghq.*` | Datadog | `com.datadoghq.ad.check_names=...` |
| `prometheus.*` | Prometheus | `prometheus.io/scrape=true` |

### Traefik label-based routing

Traefik is a reverse proxy that reads Docker labels to configure routing. This eliminates the need for a separate Traefik configuration file.

```
  Internet
     │
     ▼
  ┌──────────────────────────────────┐
  │  Traefik (reverse proxy)         │
  │  Watches Docker for labels       │
  └──────────────────────────────────┘
     │         │            │
     ▼         ▼            ▼
  ┌──────┐  ┌──────┐  ┌────────────┐
  │ App A │  │ App B │  │ volta-auth │
  │       │  │       │  │ -proxy     │
  │ label:│  │ label:│  │ label:     │
  │ Host  │  │ Host  │  │ Host       │
  │ (a.co)│  │ (b.co)│  │ (auth.co)  │
  └──────┘  └──────┘  └────────────┘
```

### How Traefik reads labels

```yaml
  # Traefik sees these labels and creates routing rules:

  labels:
    # Enable Traefik for this container
    - "traefik.enable=true"

    # Create a router named "volta"
    - "traefik.http.routers.volta.rule=Host(`auth.example.com`)"

    # Use HTTPS
    - "traefik.http.routers.volta.tls=true"

    # Auto-generate Let's Encrypt certificate
    - "traefik.http.routers.volta.tls.certresolver=letsencrypt"

    # Route to port 8080 inside the container
    - "traefik.http.services.volta.loadbalancer.server.port=8080"
```

This is equivalent to writing a Traefik config file:

```toml
  [http.routers.volta]
    rule = "Host(`auth.example.com`)"
    service = "volta"
    [http.routers.volta.tls]
      certResolver = "letsencrypt"

  [http.services.volta.loadBalancer]
    [[http.services.volta.loadBalancer.servers]]
      url = "http://volta-auth-proxy:8080"
```

But the label approach is better because it lives with the service definition, not in a separate file.

---

## How does volta-auth-proxy use it?

volta uses Docker labels primarily for Traefik reverse proxy configuration. The labels tell Traefik how to route external traffic to the volta container.

### volta's Docker Compose labels

```yaml
  services:
    volta-auth-proxy:
      image: volta-auth-proxy:latest
      labels:
        # Enable Traefik routing
        - "traefik.enable=true"

        # Route auth.example.com to this container
        - "traefik.http.routers.volta.rule=Host(`auth.example.com`)"

        # Enable HTTPS with auto-certificate
        - "traefik.http.routers.volta.tls=true"
        - "traefik.http.routers.volta.tls.certresolver=letsencrypt"

        # volta listens on port 8080
        - "traefik.http.services.volta.loadbalancer.server.port=8080"

        # Health check for Traefik
        - "traefik.http.services.volta.loadbalancer.healthcheck.path=/healthz"
        - "traefik.http.services.volta.loadbalancer.healthcheck.interval=10s"
```

### Why labels work well for volta

| Aspect | Labels approach | Config file approach |
|--------|----------------|---------------------|
| **Single file** | Everything in docker-compose.yml | docker-compose.yml + traefik.yml + dynamic.yml |
| **Adding a service** | Add labels to new service | Edit Traefik config, reload Traefik |
| **Visibility** | Configuration next to the service | Configuration in a separate file |
| **Version control** | One file to track | Multiple files to keep in sync |

### The full deployment picture

```
  docker-compose.yml
  ┌──────────────────────────────────────────┐
  │  services:                               │
  │    traefik:                              │
  │      image: traefik:v3                   │
  │      ports: ["80:80", "443:443"]         │
  │      volumes:                            │
  │        - /var/run/docker.sock:/var/run/  │
  │          docker.sock                     │
  │                                          │
  │    volta-auth-proxy:                     │
  │      image: volta-auth-proxy:latest      │
  │      labels:                             │
  │        traefik.enable: true              │
  │        traefik.http.routers.volta.rule:  │
  │          Host(`auth.example.com`)        │
  │      volumes:                            │
  │        - volta-data:/app/data            │
  │                                          │
  │  volumes:                                │
  │    volta-data:                           │
  └──────────────────────────────────────────┘
```

Labels + volumes + health checks form the complete deployment configuration, all in one Compose file.

---

## Common mistakes and attacks

### Mistake 1: Exposing the Docker socket without protection

Traefik reads labels via the Docker socket (`/var/run/docker.sock`). If an attacker gains access to the socket, they can control all containers. In production, use Traefik's Docker socket proxy or read-only socket access.

### Mistake 2: Typos in label names

Labels are strings. A typo like `traefik.http.router.volta.rule` (missing "s" in "routers") silently fails. Traefik ignores unknown labels without error. Always test routing after changes.

### Mistake 3: Forgetting traefik.enable=true

By default, Traefik may expose all containers or none (depending on configuration). Best practice: set `exposedByDefault: false` in Traefik config and explicitly add `traefik.enable=true` to services that need routing.

### Mistake 4: Label conflicts between services

Two services with the same router name (`traefik.http.routers.app.rule`) will conflict. Use unique router names for each service.

### Mistake 5: Not using the health check label

Without a health check label, Traefik routes traffic to containers that may not be ready. Add `traefik.http.services.X.loadbalancer.healthcheck.path` to ensure Traefik only routes to healthy instances.

---

## Further reading

- [docker-volume.md](docker-volume.md) -- Persistent storage, configured alongside labels.
- [health-check.md](health-check.md) -- The endpoint Traefik uses for health checks via labels.
- [Traefik Docker Documentation](https://doc.traefik.io/traefik/providers/docker/) -- How Traefik reads Docker labels.
- [OCI Image Spec - Labels](https://github.com/opencontainers/image-spec/blob/main/annotations.md) -- Standard label conventions.
