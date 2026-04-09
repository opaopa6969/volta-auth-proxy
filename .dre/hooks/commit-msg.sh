#!/usr/bin/env bash
# DRE commit-msg hook — suggest DD reference in commit messages
# Install: cp dre/kit/hooks/commit-msg.sh .git/hooks/commit-msg && chmod +x .git/hooks/commit-msg

COMMIT_MSG_FILE="$1"
MSG=$(cat "$COMMIT_MSG_FILE")

# Skip merge commits, amends, etc.
if echo "$MSG" | grep -qE "^(Merge|Revert|fixup!|squash!)"; then
  exit 0
fi

# Check if DD reference exists
if echo "$MSG" | grep -qE "Ref:\s*DD-\d+|DD-\d+"; then
  exit 0
fi

# Check if there are active DDs in the project
DD_DIR="dge/decisions"
if [ ! -d "$DD_DIR" ]; then
  exit 0  # No decisions directory — skip
fi

DD_COUNT=$(find "$DD_DIR" -name "DD-*.md" 2>/dev/null | wc -l)
if [ "$DD_COUNT" -eq 0 ]; then
  exit 0  # No DDs yet
fi

# List active DDs
echo ""
echo "💡 DRE: このコミットに関連する DD はありますか？"
echo ""
for f in "$DD_DIR"/DD-*.md; do
  [ -f "$f" ] || continue
  ID=$(basename "$f" .md | grep -oP 'DD-\d+')
  TITLE=$(head -1 "$f" | sed 's/^# //' | sed "s/^${ID}[: ]*//" | head -c 60)
  echo "   ${ID}: ${TITLE}"
done
echo ""
echo "   コミットメッセージに Ref: DD-NNN を追加してください。"
echo "   スキップするには Ref: none を追加。"
echo ""

# Soft enforcement — warn but don't block
# To make it hard: uncomment the next line
# exit 1
exit 0
