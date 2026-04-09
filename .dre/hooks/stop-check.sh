#!/usr/bin/env bash
# DRE Stop Enforcement — check workflow obligations before stopping
# Returns JSON: {"ok": true} or {"ok": false, "reason": "..."}

set -euo pipefail

# Load notification function
HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HOOK_DIR/notify.sh" ] && . "$HOOK_DIR/notify.sh"
[ -f ".dre/hooks/notify.sh" ] && . ".dre/hooks/notify.sh"

CTX_FILE=".dre/context.json"
CONFIG_FILE=".dre/dre-config.json"

if [ ! -f "$CTX_FILE" ]; then
  echo '{"ok": true}'
  exit 0
fi

CURRENT_PHASE=$(jq -r '.current_phase // empty' "$CTX_FILE" 2>/dev/null)
SUB_STATE=$(jq -r '.sub_state // empty' "$CTX_FILE" 2>/dev/null)
REASONS=""

# ─── DGE: gap_extraction phase ───
if [ "$CURRENT_PHASE" = "gap_extraction" ]; then
  TODAY=$(date +%Y-%m-%d)
  RECENT=$(find dge/sessions/ -name "${TODAY}*.md" -newer "$CTX_FILE" 2>/dev/null | wc -l || echo 0)

  if [ "$RECENT" -eq 0 ]; then
    REASONS="${REASONS}DGE gap_extraction phase active but no session saved today. MUST save dialogue. "
  else
    LATEST=$(find dge/sessions/ -name "${TODAY}*.md" -newer "$CTX_FILE" 2>/dev/null | sort | tail -1)
    if [ -n "$LATEST" ]; then
      HAS_DIALOGUE=$(grep -cE "Scene|先輩|ナレーション" "$LATEST" 2>/dev/null || echo 0)
      if [ "$HAS_DIALOGUE" -eq 0 ]; then
        REASONS="${REASONS}Session ${LATEST} has no dialogue. Save full dialogue text. "
      fi
    fi
  fi
fi

# ─── Stack: pending items ───
STACK_LEN=$(jq '.stack | length' "$CTX_FILE" 2>/dev/null || echo 0)
if [ "$STACK_LEN" -gt 1 ]; then
  STACK_TOP=$(jq -r '.stack[-1]' "$CTX_FILE" 2>/dev/null)
  REASONS="${REASONS}Workflow stack depth=${STACK_LEN} (top: ${STACK_TOP}). Pop or complete before stopping. "
fi

# ─── DVE: build needed check ───
if [ -f "dve/dist/graph.json" ]; then
  GRAPH_TIME=$(stat -c %Y dve/dist/graph.json 2>/dev/null || echo 0)
  NEWEST_SESSION=$(find dge/sessions/ -name "*.md" -newer dve/dist/graph.json 2>/dev/null | wc -l || echo 0)
  NEWEST_DD=$(find dge/decisions/ -name "DD-*.md" -newer dve/dist/graph.json 2>/dev/null | wc -l || echo 0)
  if [ "$NEWEST_SESSION" -gt 0 ] || [ "$NEWEST_DD" -gt 0 ]; then
    REASONS="${REASONS}[info] DVE graph.json is stale (${NEWEST_SESSION} new sessions, ${NEWEST_DD} new DDs). Consider: dve build. "
  fi
fi

# ─── Pending decisions check ───
PENDING_FILE=".dre/pending-decisions.json"
if [ -f "$PENDING_FILE" ]; then
  PENDING_COUNT=$(jq '.pending | length' "$PENDING_FILE" 2>/dev/null || echo 0)
  if [ "$PENDING_COUNT" -gt 0 ]; then
    PENDING_TEXTS=$(jq -r '[.pending[-5:][].text] | join("; ")' "$PENDING_FILE" 2>/dev/null | head -c 300)
    REASONS="${REASONS}[info] ${PENDING_COUNT} implicit decisions detected. Consider creating DDs: ${PENDING_TEXTS} "
  fi
fi

# ─── Commit DD reference check ───
# Check if recent commits lack DD references
if command -v git &>/dev/null && [ -d ".git" ]; then
  RECENT_NO_REF=$(git log --oneline -5 2>/dev/null | grep -cvE "Ref:.*DD-|DD-\d+" || true)
  DD_COUNT=$(find dge/decisions/ -name "DD-*.md" 2>/dev/null | wc -l || echo 0)
  if [ "$DD_COUNT" -gt 0 ] && [ "$RECENT_NO_REF" -gt 3 ]; then
    REASONS="${REASONS}[info] Recent commits lack DD references (Ref: DD-NNN). "
  fi
fi

# ─── Return ───
if [ -n "$REASONS" ]; then
  ESCAPED=$(echo "$REASONS" | sed 's/"/\\"/g' | tr '\n' ' ')
  # Notify for critical issues
  if echo "$REASONS" | grep -qE "MUST|gap_extraction|no dialogue"; then
    type dre_notify &>/dev/null && dre_notify "critical" "Stop blocked" "$REASONS"
  elif echo "$REASONS" | grep -q "implicit decisions"; then
    type dre_notify &>/dev/null && dre_notify "daily" "Pending decisions" "$REASONS"
  fi
  echo "{\"ok\": false, \"reason\": \"${ESCAPED}\"}"
else
  echo '{"ok": true}'
fi
