#!/usr/bin/env bash
# volta-auth-proxy startup — required by volta-gateway (host 192.168.1.8:7070).
# Idempotent and safe to run repeatedly (cron */1 uses this for crash/boot recovery).
#
# NOTE: .env DB_PORT/DB_USER/DB_PASSWORD do NOT match where the real data lives.
# Real data is in the docker-compose volta-auth-postgres (mapped port 54329, user volta/volta).
# We override those three vars here while keeping the rest of .env.
set -euo pipefail
cd "$(dirname "$0")"

# Ensure backing services are up. compose has restart: unless-stopped, but after a
# fresh boot the daemon may not have started them yet, so start on demand too.
for c in volta-auth-postgres volta-auth-redis; do
  if ! docker ps --format '{{.Names}}' | grep -qx "$c"; then
    docker start "$c" >/dev/null 2>&1 || true
  fi
done

# Already running? Match the real jar process cmdline (not this script / cron wrapper).
if pgrep -f 'java -jar target/volta-auth-proxy-.*\.jar' >/dev/null; then
  exit 0
fi

# Wait until postgres actually accepts connections before launching the JVM.
# The app's Hikari pool fail-fasts (and the process exits) if the DB is still
# in "the database system is starting up". Bounded so a cron run can't hang.
for _ in $(seq 1 30); do
  if [ "$(docker inspect -f '{{.State.Health.Status}}' volta-auth-postgres 2>/dev/null)" = "healthy" ]; then
    break
  fi
  sleep 1
done

mkdir -p logs
set -a; . ./.env; set +a
DB_PORT=54329 DB_USER=volta DB_PASSWORD=volta \
  nohup java -jar target/volta-auth-proxy-0.3.0-SNAPSHOT.jar \
  >> logs/auth-proxy.log 2>&1 &

echo "$(date -Is) auth-proxy started, pid=$!, log=logs/auth-proxy.log"
