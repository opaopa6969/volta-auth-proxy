# DGE Session: volta-gateway Phase 2 設計

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 🌐Proxy専門家 / ⚔リヴァイ / 😈Red Team
> Theme: Phase 2 の8項目を設計

## Decisions

### HTTP/2
auto::Builder でインバウンド H1/H2 自動判別。
backend H2 は routing config に `h2: bool`。
TLS 終端は Phase 3（CF 前提は変わらない）。

### Per-IP Rate Limiting
HashMap<IpAddr, (count, window_start)> + Mutex。
Background GC task (30秒ごと、60秒以上 idle のエントリを削除)。
Config: global_rps: 1000, per_ip_rps: 100。

### Prometheus
crate 不要。AtomicU64 カウンタ + plain text exposition format。
/metrics エンドポイント。~80行。
Counters: requests_total, sm_terminal_total, rate_limited_total。
Histogram: request_duration_seconds。Gauge: active_connections。

### Config Validation
起動時に validate()。失敗は error + exit(1)。warning ではない。
チェック: routing 空、port 範囲、host 重複。

### IP Allowlist
Per-route 設定: ip_allowlist: ["10.0.0.0/8"]
ipnet crate で CIDR パース。
VALIDATED state でチェック。範囲外 → DENIED。

### Connection Pool
Config: max_idle_per_host, idle_timeout_secs, max_retries。
hyper::Client に渡すだけ。

### Chunked Body
hyper max_buf_size(10MB)。1行。

### 移行ガイド
docs/migration-from-traefik.md。
Traefik labels → volta-gateway.yaml のマッピング表。

## Implementation Plan

Day 1: PH2-1 (H2) + PH2-2 (per-IP rate limit) + PH2-7 (chunked)
Day 2: PH2-3 (Prometheus) + PH2-5 (IP allowlist) + PH2-4 (config) + PH2-6 (pool)
Day 3: PH2-8 (移行ガイド) + tribunal v3

## Gap Summary

High: 2 (PH2-1, PH2-2)
Medium: 2 (PH2-3, PH2-5)
Low: 4 (PH2-4, PH2-6, PH2-7, PH2-8)
Total: 8
