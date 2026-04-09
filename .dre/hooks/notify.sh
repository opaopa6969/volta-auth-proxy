#!/usr/bin/env bash
# DRE Notification — send alerts via Slack/Discord/webhook/desktop
# Source this file from other hooks: . .dre/hooks/notify.sh

dre_notify() {
  local LEVEL="${1:-info}"    # critical / daily / info
  local TITLE="${2:-DRE}"
  local BODY="${3:-}"
  local PROJECT="${4:-$(basename "$(pwd)")}"

  local CONFIG_FILE=".dre/dre-config.json"
  local CHANNEL="none"
  local MIN_LEVEL="critical"

  if [ -f "$CONFIG_FILE" ]; then
    CHANNEL=$(jq -r '.notifications.channel // "none"' "$CONFIG_FILE" 2>/dev/null || echo "none")
    MIN_LEVEL=$(jq -r '.notifications.min_level // "critical"' "$CONFIG_FILE" 2>/dev/null || echo "critical")
  fi

  # Level filter
  case "$MIN_LEVEL" in
    critical) [ "$LEVEL" != "critical" ] && return 0 ;;
    daily)    [ "$LEVEL" = "info" ] && return 0 ;;
    all)      ;;
    none)     return 0 ;;
  esac

  local URL="${DRE_NOTIFY_URL:-}"

  # Send based on channel
  if [ -n "$URL" ]; then
    case "$CHANNEL" in
      slack)
        curl -sf -X POST "$URL" -H 'Content-Type: application/json' \
          -d "{\"text\": \"*[DRE ${LEVEL}]* ${PROJECT}: ${TITLE}\n${BODY}\"}" \
          >/dev/null 2>&1 || true
        ;;
      discord)
        curl -sf -X POST "$URL" -H 'Content-Type: application/json' \
          -d "{\"content\": \"**[DRE ${LEVEL}]** ${PROJECT}: ${TITLE}\n${BODY}\"}" \
          >/dev/null 2>&1 || true
        ;;
      webhook)
        curl -sf -X POST "$URL" -H 'Content-Type: application/json' \
          -d "{\"level\": \"${LEVEL}\", \"project\": \"${PROJECT}\", \"title\": \"${TITLE}\", \"body\": \"${BODY}\", \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
          >/dev/null 2>&1 || true
        ;;
      desktop)
        if command -v notify-send &>/dev/null; then
          notify-send "DRE: ${TITLE}" "${BODY}" 2>/dev/null || true
        elif command -v osascript &>/dev/null; then
          osascript -e "display notification \"${BODY}\" with title \"DRE: ${TITLE}\"" 2>/dev/null || true
        fi
        ;;
    esac
  fi

  # Always log to file
  mkdir -p .dre 2>/dev/null || true
  local NOTIFICATIONS_FILE=".dre/notifications.json"
  local ENTRY="{\"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"level\": \"${LEVEL}\", \"project\": \"${PROJECT}\", \"title\": \"${TITLE}\", \"body\": \"$(echo "$BODY" | head -c 200 | sed 's/"/\\"/g')\"}"

  if [ ! -f "$NOTIFICATIONS_FILE" ]; then
    echo "[${ENTRY}]" > "$NOTIFICATIONS_FILE"
  else
    # Append to JSON array
    local TMP=$(mktemp)
    jq --argjson entry "$ENTRY" '. += [$entry]' "$NOTIFICATIONS_FILE" > "$TMP" 2>/dev/null && mv "$TMP" "$NOTIFICATIONS_FILE" || rm -f "$TMP"
  fi
}
