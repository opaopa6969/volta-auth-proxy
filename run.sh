#!/usr/bin/env bash
export NODE_OPTIONS="--max-old-space-size=1536 ${NODE_OPTIONS:-}"  # 常駐サイズの明示(volta-index#48)
set -euo pipefail
cd "$(dirname "$0")"
exec /home/opa/.nvm/versions/node/v20.20.0/bin/node mcp/server.mjs
