# DGE Session: Rust SM Reverse Proxy — Round 3 (L7防御 + tower統合)

> Date: 2026-04-07
> Round: 3
> New Gaps: 3 (RP-16 ~ RP-18)
> Resolved: RP-3, RP-9

## RP-3 解決: L7 攻撃の SM 防御

```
hyper が防ぐ: HTTP smuggling, header injection, HTTP/2 violations
SM VALIDATED state: host validation (known hosts only), path normalization (reject ../),
  header/body size limits (8KB headers, 10MB body)
tower: rate limiting (100 req/sec), total timeout (30s)
hyper config: header_read_timeout(10s) for Slowloris
```

VALIDATED state を RECEIVED と ROUTED の間に追加:
```
RECEIVED → VALIDATED → ROUTED → AUTH_CHECKED → FORWARDED → RESPONSE_RECEIVED → COMPLETED
```

RequestValidator processor:
  1. Header size check (8KB)
  2. Body size check (10MB configurable)
  3. Host header must exist and be in known hosts
  4. Path normalization (reject .., //)

## RP-9 解決: hyper + tower + SM 統合

B方式: SM は sync 判断のみ、async I/O は SM の外

```
flow.start() → RECEIVED → VALIDATED → ROUTED (sync, μs)
── async: volta /auth/verify ──
flow.resume(auth_result) → AUTH_CHECKED (sync, μs)
── async: backend HTTP forward ──
flow.resume(response) → FORWARDED → COMPLETED (sync, μs)
```

メリット:
  - tramli-rs は sync のまま（Java 版と同じ設計）
  - async の複雑性は tower パイプラインに閉じる
  - SM は「判断」、tower は「実行」の関心分離

tower::ServiceBuilder スタック:
  TraceLayer → RateLimitLayer → TimeoutLayer → SmProxyService

External 遷移が2つ: auth check + backend forward
  → 異なる state からの External なので SM ルール上問題なし

## New Gaps

| # | Gap | Severity |
|---|-----|----------|
| RP-16 | backend からの X-Volta-* strip (偽装防止) | High |
| RP-17 | 2 External の FlowContext 設計 | Medium |
| RP-18 | Streaming response の扱い | Medium |

## Cumulative: 18 gaps

Critical: 2/2 RESOLVED
High: 8 (4 RESOLVED, 1 user-owned tramli-rs, 3 Open)
Medium: 7
Low: 2
