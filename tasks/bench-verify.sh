#!/bin/bash
# End-to-end /auth/verify benchmark (single-threaded, serial).
# Uses the service-token path so we exercise JWT signing without needing
# an OIDC login. Override via env vars:
#   VOLTA_URL (default http://localhost:7070)
#   VOLTA_SERVICE_TOKEN (required)
#   N (default 300)
set -u

URL="${VOLTA_URL:-http://localhost:7070}/auth/verify"
TOKEN="${VOLTA_SERVICE_TOKEN:?set VOLTA_SERVICE_TOKEN}"
N="${N:-300}"
AUTH="Authorization: Bearer volta-service:${TOKEN}"

echo "=== /auth/verify serial benchmark ==="
echo "URL: ${URL}"
echo "N:  ${N}"

# Warmup (let JIT settle, prime any cache)
for i in $(seq 1 20); do
  curl -s -m 5 -o /dev/null -H "${AUTH}" "${URL}" || true
done

# Verify 200
code=$(curl -s -m 5 -o /dev/null -w "%{http_code}" -H "${AUTH}" "${URL}")
if [ "${code}" != "200" ]; then
  echo "ERROR: verify returned ${code}, expected 200"
  exit 1
fi

# Measure
TMP=$(mktemp)
for i in $(seq 1 ${N}; ); do
  curl -s -m 10 -o /dev/null -w "%{time_total}\n" -H "${AUTH}" "${URL}" >> "${TMP}"
done

awk '
  { ms = $1 * 1000; sum += ms; n++; a[n] = ms; if (ms > max) max = ms; if (min=="" || ms < min) min = ms }
  END {
    asort(a)
    printf "N         : %d\n", n
    printf "Mean      : %.3f ms\n", sum/n
    printf "Min       : %.3f ms\n", min
    printf "Max       : %.3f ms\n", max
    printf "p50       : %.3f ms\n", a[int(n*0.5)+1]
    printf "p90       : %.3f ms\n", a[int(n*0.9)+1]
    printf "p99       : %.3f ms\n", a[int(n*0.99)+1]
    printf "Throughput: %.0f req/s (serial)\n", 1000/(sum/n)
  }
' "${TMP}"
rm -f "${TMP}"
echo "=========================================="
