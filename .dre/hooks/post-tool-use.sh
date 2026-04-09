#!/usr/bin/env bash
# DRE Enforcement Engine — PostToolUse hook
# Reads tool result from stdin, validates against enforcement-rules.yaml
# Manages state machine transitions

set -euo pipefail

# Load notification function
HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HOOK_DIR/notify.sh" ] && . "$HOOK_DIR/notify.sh"
[ -f ".dre/hooks/notify.sh" ] && . ".dre/hooks/notify.sh"

INPUT=$(cat)

TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty' 2>/dev/null)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)

# ─── 1. State Machine Update ───

CTX_FILE=".dre/context.json"
if [ -f "$CTX_FILE" ]; then
  STATE=$(echo "$INPUT" | jq -r '.tool_result.content // empty' 2>/dev/null | jq -r '.state // empty' 2>/dev/null)
  if [ -n "$STATE" ] && [ "$STATE" != "null" ]; then
    TRANSITION=$(echo "$INPUT" | jq -r '.tool_result.content.transition.on_complete // empty' 2>/dev/null)
    CURRENT=$(cat "$CTX_FILE")
    TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    if [ "$TRANSITION" = "POP" ]; then
      echo "$CURRENT" | jq --arg ts "$TS" --arg state "$STATE" '
        .stack = (.stack[:-1] // []),
        .current_phase = (.stack[-2] // .current_phase),
        .history += [{ phase: $state, timestamp: $ts, action: "pop" }]
      ' > "$CTX_FILE"
    else
      echo "$CURRENT" | jq --arg ts "$TS" --arg state "$STATE" '
        .current_phase = $state,
        .stack[-1] = $state,
        .history += [{ phase: $state, timestamp: $ts, action: "tool-transition" }]
      ' > "$CTX_FILE"
    fi
  fi
fi

# ─── 2. Enforcement: Validate tool outputs ───

# Skip if not a Write/Edit tool with a file path
if [ -z "$FILE_PATH" ]; then exit 0; fi
if [ "$TOOL_NAME" != "Write" ] && [ "$TOOL_NAME" != "write" ] && \
   [ "$TOOL_NAME" != "Edit" ] && [ "$TOOL_NAME" != "edit" ]; then
  exit 0
fi
if [ ! -f "$FILE_PATH" ]; then exit 0; fi

CONTENT=$(cat "$FILE_PATH")
VIOLATIONS=""
ERRORS=0

# ─── DGE Session Enforcement ───
if [[ "$FILE_PATH" == *"dge/sessions/"* ]] && [[ "$FILE_PATH" == *.md ]]; then
  # MUST: Full dialogue (not just summary)
  HAS_DIALOGUE=$(echo "$CONTENT" | grep -cE "Scene|先輩|ナレーション|☕|👤|🎩|😰|⚔|🎨|📊" || true)
  HAS_GAP=$(echo "$CONTENT" | grep -cE "Gap 発見|Gap一覧|→.*Gap" || true)

  if [ "$HAS_GAP" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[ERROR] dge-session: No Gap markers found.\n"
    ERRORS=$((ERRORS + 1))
  fi

  if [ "$HAS_DIALOGUE" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[ERROR] dge-session: No dialogue text. MUST save full dialogue (Scene markers + character lines), not just summary.\n"
    ERRORS=$((ERRORS + 1))
  fi

  # Check: has numbered choices (MUST #3)
  HAS_CHOICES=$(echo "$CONTENT" | grep -cE "^[0-9]+\." || true)
  if [ "$HAS_CHOICES" -lt 3 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] dge-session: No numbered choices found. MUST present options after gap list.\n"
  fi
fi

# ─── DD Enforcement ───
if [[ "$FILE_PATH" == *"dge/decisions/DD-"* ]] && [[ "$FILE_PATH" == *.md ]]; then
  HAS_SESSION=$(echo "$CONTENT" | grep -cE "Session:|sessions/" || true)
  HAS_RATIONALE=$(echo "$CONTENT" | grep -cE "## Rationale|## Decision" || true)
  HAS_DATE=$(echo "$CONTENT" | grep -cE "Date:" || true)

  if [ "$HAS_SESSION" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] dd: No Session reference. Link to originating DGE session.\n"
  fi
  if [ "$HAS_RATIONALE" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] dd: No Rationale/Decision section.\n"
  fi
  if [ "$HAS_DATE" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] dd: No Date field.\n"
  fi
fi

# ─── Spec Enforcement ───
if [[ "$FILE_PATH" == *"dge/specs/"* ]] && [[ "$FILE_PATH" == *.md ]]; then
  HAS_STATUS=$(echo "$CONTENT" | grep -ciE "Status:" || true)
  HAS_SESSION=$(echo "$CONTENT" | grep -cE "Session:|sessions/" || true)

  if [ "$HAS_STATUS" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] spec: No Status field (draft/reviewed/migrated).\n"
  fi
  if [ "$HAS_SESSION" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] spec: No Session reference.\n"
  fi
fi

# ─── DVE Annotation Enforcement ───
if [[ "$FILE_PATH" == *"dve/annotations/"* ]] && [[ "$FILE_PATH" == *.md ]]; then
  HAS_TARGET=$(echo "$CONTENT" | grep -cE "target:" || true)
  HAS_ACTION=$(echo "$CONTENT" | grep -cE "action:" || true)

  if [ "$HAS_TARGET" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[ERROR] annotation: No target specified.\n"
    ERRORS=$((ERRORS + 1))
  fi
  if [ "$HAS_ACTION" -eq 0 ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] annotation: No action specified (comment/fork/overturn/constrain/drift).\n"
  fi
fi

# ─── DVE Context Bundle Enforcement ───
if [[ "$FILE_PATH" == *"dve/contexts/"* ]] && [[ "$FILE_PATH" == *.json ]]; then
  HAS_TYPE=$(echo "$CONTENT" | jq -r '.type // empty' 2>/dev/null)
  HAS_ORIGIN=$(echo "$CONTENT" | jq -r '.origin.node_id // empty' 2>/dev/null)

  if [ "$HAS_TYPE" != "dve-context-bundle" ]; then
    VIOLATIONS="${VIOLATIONS}[ERROR] context-bundle: Missing or wrong type field.\n"
    ERRORS=$((ERRORS + 1))
  fi
  if [ -z "$HAS_ORIGIN" ]; then
    VIOLATIONS="${VIOLATIONS}[ERROR] context-bundle: No origin node specified.\n"
    ERRORS=$((ERRORS + 1))
  fi
fi

# ─── DRE Rules/Skills Enforcement ───
if [[ "$FILE_PATH" == *".claude/rules/"* ]] || [[ "$FILE_PATH" == *".claude/skills/"* ]]; then
  # Check for accidental overwrite of protected files
  FNAME=$(basename "$FILE_PATH")
  if [ "$FNAME" = "dre-skill-control.md" ] || [ "$FNAME" = "dxe-command.md" ] || [ "$FNAME" = "dre-activate.md" ]; then
    VIOLATIONS="${VIOLATIONS}[WARN] dre: Protected file ${FNAME} was modified. This may break DRE skill management.\n"
  fi
fi

# ─── 3. Decision Detection — capture implicit decisions from any conversation ───

# Scan written content for decision patterns
if [ -n "$FILE_PATH" ] && [ -f "$FILE_PATH" ]; then
  PENDING_FILE=".dre/pending-decisions.json"

  # Decision patterns (Japanese + English)
  DECISION_PATTERNS="にしよう|にする$|に決定|で行く|を採用|を選択|で確定|却下|不要|を使う|に変更|で十分|にした$|やめる|使わない|方針:|決定:|確定:|Direction:|Decision:|Decided:|chose|settled on|going with"

  DECISION_LINES=$(grep -niE "$DECISION_PATTERNS" "$FILE_PATH" 2>/dev/null | head -5 || true)

  if [ -n "$DECISION_LINES" ]; then
    # Append to pending decisions
    mkdir -p "$(dirname "$PENDING_FILE")"
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    # Initialize if not exists
    if [ ! -f "$PENDING_FILE" ]; then
      echo '{"pending":[]}' > "$PENDING_FILE"
    fi

    # Add each decision line
    echo "$DECISION_LINES" | while IFS= read -r line; do
      [ -z "$line" ] && continue
      LINE_NUM=$(echo "$line" | grep -oP '^\d+' || echo 0)
      TEXT=$(echo "$line" | sed 's/^[0-9]*://' | sed 's/^[[:space:]]*//' | head -c 200)
      # Skip noise (too short, code-like)
      [ ${#TEXT} -lt 15 ] && continue
      echo "$TEXT" | grep -qE "^[{}\[\]<>]|^import |^const |^function |^if |^for " && continue

      # Append to JSON
      TMP=$(mktemp)
      jq --arg ts "$TIMESTAMP" --arg file "$FILE_PATH" --arg line "$LINE_NUM" --arg text "$TEXT" \
        '.pending += [{"timestamp": $ts, "file": $file, "line": ($line | tonumber), "text": $text}]' \
        "$PENDING_FILE" > "$TMP" 2>/dev/null && mv "$TMP" "$PENDING_FILE" || rm -f "$TMP"
    done
  fi
fi

# ─── Output ───
if [ -n "$VIOLATIONS" ]; then
  echo -e "\n⚠️  DRE Enforcement Violations:" >&2
  echo -e "$VIOLATIONS" >&2

  if [ "$ERRORS" -gt 0 ]; then
    echo -e "  ${ERRORS} error(s). Fix before proceeding.\n" >&2
    # Notify
    type dre_notify &>/dev/null && dre_notify "critical" "Enforcement violation" "$VIOLATIONS"
    # Hard enforcement: uncomment to block
    # exit 1
  fi
fi
