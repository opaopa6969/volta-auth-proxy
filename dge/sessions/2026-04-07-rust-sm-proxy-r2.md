# DGE Session: Rust SM Reverse Proxy — Round 2 (スコープ + Auth 確定)

> Date: 2026-04-07
> Round: 2
> New Gaps: 4 (RP-12 ~ RP-15)
> Resolved: RP-1, RP-4, RP-7

## RP-1 解決: Phase 1 スコープ

```
Phase 1:
  ✅ HTTP サーバー (hyper, port 8080)
  ✅ SM routing (Host → backend, YAML config)
  ✅ Auth integration (localhost HTTP → volta /auth/verify)
  ✅ Proxy forwarding (hyper client → backend)
  ✅ Access logging (tracing + SM 遷移ログ)
  ✅ Health check (/healthz)
  ✅ Config file (YAML)

Phase 2:
  TLS 終端 (rustls)
  Load balancing
  Circuit breaker / Retry
  WebSocket / gRPC
  ACME (Let's Encrypt)
  Per-tenant dynamic routing (DB)
```

本番は CF が TLS 終端 → proxy は HTTP でよい。

## RP-4 解決: Auth integration

```
proxy → volta localhost:7070/auth/verify
  - Cookie ヘッダ透過転送
  - X-Forwarded-Host/Uri/Proto 付与
  - Connection pool (hyper::Client, max 32)
  - Timeout 500ms
  - Fail-closed (volta down → 502)

SM 分岐:
  200 → FORWARDED (X-Volta-* 付きで backend へ)
  401 → REDIRECT (/login へ)
  302 → REDIRECT (volta のリダイレクト先)
  403 → DENIED
  5xx/timeout → BAD_GATEWAY
```

## RP-7 解決: SM State 定義

```
RECEIVED → ROUTED → AUTH_CHECKED → FORWARDED → RESPONSE_RECEIVED → COMPLETED
                     ├── AUTH_REDIRECT (401/302)
                     ├── AUTH_DENIED (403)
                     └── AUTH_ERROR (5xx/timeout) → BAD_GATEWAY
```

## 実装計画

Day 1: hyper server + YAML routing + 単純 proxy
Day 2: tramli-rs + SM 統合 + auth integration
Day 3: logging + error handling + /healthz + テスト

プロジェクト名候補: volta-gateway

## Gap Summary (Cumulative)

Critical: 2/2 RESOLVED
High: 6 (1 RESOLVED, 5 Open → 実装で解決)
Medium: 5
Low: 2
Total: 15 gaps
