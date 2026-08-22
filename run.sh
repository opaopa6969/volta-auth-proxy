#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec node mcp/server.mjs
