# DGE 査読劇 v4: 「乗り換える気にならない」— 厳しい声

> Date: 2026-04-07
> Structure: ⚖ 査読劇 (tribunal) — Round 4 (harsh)
> Evaluators: 🏗️ Traefik SRE / 🌐 Proxy専門家(きつめ) / 🔧 App開発者
> Subject: volta-gateway Phase 2 完了版

## Verdicts

| Reviewer | Verdict |
|----------|---------|
| 🏗️ Traefik SRE | **Reject** — 本番運用の基本機能不足 |
| 🌐 Proxy専門家 | **Major Revision** — WebSocket + streaming なしは SaaS proxy として不可 |
| 🔧 App開発者 | **Major Revision** — CORS もエラーページもない。使いたいと思えない |

## Key Insight

**volta-gateway は Traefik の代替ではない。**
小規模 SaaS 向け認証特化 proxy。

大規模 (50+ サービス, K8s, Canary) → Traefik + volta-auth-proxy
小規模 (5-10 サービス, 認証レイテンシ重要) → volta-gateway

## Gaps (9 new)

| # | Gap | Severity | Phase |
|---|-----|----------|-------|
| GW-18 | README positioning 変更 | High | 今すぐ |
| GW-19 | WebSocket | Critical | Phase 3 |
| GW-20 | Streaming body | High | Phase 3 |
| GW-21 | CORS Processor | High | Phase 3 |
| GW-22 | Config hot reload | High | Phase 3 |
| GW-23 | Load balancing (round-robin) | Critical | Phase 3 |
| GW-24 | Pretty log | Low | Phase 3 |
| GW-25 | カスタムエラーページ | Medium | Phase N |
| GW-26 | IPv6 Host パース | Low | Phase 3 |

## SRE の言葉（記録に値する）

「50サービスの YAML を手動管理？ 冗談でしょう。」
「証明書管理を『別のサービスでやって』は proxy の仕事を放棄してる。」
「単一 backend = SPOF。本番に出せない。」

## App 開発者の言葉

「proxy なんか意識したくない。labels 書けばルーティングされる。それが壊れなければいい。」
「SM, FlowDefinition, Processor — 知らない概念が多すぎる。使い方がわからない。」
「4ms → 0.5ms の差はユーザーには見えない。」
