# DGE Session: Rust SM Reverse Proxy — Traefik/CF を自前で置き換える

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / ⚔リヴァイ / 😈Red Team / 🏥ハウス / 👑ラインハルト / 🌐Proxy専門家
> Theme: Reverse proxy を Rust + SM パターンで自作する是非と設計
> Round: 1
> Gaps: 11

## Key Decisions

1. hyper + tower の上に SM routing layer。HTTP 処理は hyper に全委譲
2. Auth: proxy=Rust, auth=Java volta (localhost HTTP 呼出)。将来 Rust 完全移行の余地残す
3. 差別化: auth-aware routing (latency 半減), per-tenant 動的ルーティング, 全遷移可観測性
4. 実装量: ~1,600行 (tramli-rs ~500, proxy SM ~100, auth client ~200, routing ~200, tower service ~300, TLS ~100, config ~200)

## Scene 1: なぜ自作するか

Traefik の問題:
  - 設定が外部ファイルに散らばる (Docker labels, traefik.yml, middleware chain)
  - デバッグ困難（Traefik 内部が不透明）
  - ForwardAuth = 2 HTTP 往復 (4-10ms 追加)

自作のメリット:
  - auth check がパイプライン内部 (0.5-1ms)
  - per-tenant 動的ルーティング（DB ベース）
  - 全遷移が可観測 (Mermaid 図)
  - L7 攻撃を SM で構造的に排除

→ Gap RP-1: スコープ定義 [Critical]

## Scene 2: SM パターンが効くポイント

HTTP リクエストライフサイクルを SM で管理:
  RECEIVED → ROUTED → AUTH_CHECKED → FORWARDED → RESPONSE_RECEIVED → COMPLETED

メリット:
  - 各 Processor が独立テスト可能
  - 想定外の状態遷移（HTTP smuggling 等）を構造的に拒否
  - 1遷移 300-500ns、6遷移で ~2μs。全体の1%未満

→ Gap RP-2: ForwardAuth 廃止の影響 [High]
→ Gap RP-3: L7 攻撃の SM 防御具体設計 [High]

## Scene 3: Rust で何を作るか

使う crate: hyper, tokio, rustls, tower
自作: SM フレームワーク (tramli-rs), routing, auth client, config

Auth integration は E 方式:
  proxy=Rust, auth=Java volta (localhost HTTP)
  → Traefik ForwardAuth より 5-10 倍速い
  → volta の Java 資産をそのまま活用
  → 将来 Rust 完全移行 (D) も可能

→ Gap RP-4: Auth integration 方式 [Critical]
→ Gap RP-5: tramli-rs 設計 [High]

## Scene 4: 差別化

1. Auth-aware routing: auth がパイプラインの一部 → latency 半減
2. 全遷移可観測: access log がステップ別 → ボトルネック特定
3. Per-tenant 動的 routing: DB → routing table → テナント別 backend
4. Fraud check 統合: RiskCheckProcessor がパイプライン内
5. Mermaid 図: proxy 設定を可視化

→ Gap RP-6: Per-tenant routing 設計 [High]
→ Gap RP-7: SM state 定義 [Medium]
→ Gap RP-8: Traefik 移行パス [High]

## Scene 5: リスク

hyper が HTTP パース/HTTP2/keep-alive を全部やる。
SM は「routing → auth → forwarding」のビジネスレイヤーのみ制御。
HTTP 仕様の低レベル処理には触らない → バグリスク最小化。

```
tower::Service パイプライン:
  hyper (HTTP parse) → SM middleware → backend proxy (hyper client)
```

→ Gap RP-9: hyper + tower + SM 統合 [High]
→ Gap RP-10: WebSocket / SSE / gRPC [Medium]
→ Gap RP-11: Let's Encrypt ACME [Medium]

## Gap Summary

| # | Gap | Severity |
|---|-----|----------|
| RP-1 | スコープ (何を置き換えるか) | Critical |
| RP-2 | ForwardAuth 廃止の影響 | High |
| RP-3 | L7 攻撃の SM 防御 | High |
| RP-4 | Auth integration 方式 | Critical |
| RP-5 | tramli-rs 設計 | High |
| RP-6 | Per-tenant dynamic routing | High |
| RP-7 | SM state 定義 | Medium |
| RP-8 | Traefik 移行パス | High |
| RP-9 | hyper + tower + SM 統合 | High |
| RP-10 | WebSocket / SSE / gRPC | Medium |
| RP-11 | Let's Encrypt ACME | Medium |

Critical: 2 / High: 6 / Medium: 3 — Total: 11
