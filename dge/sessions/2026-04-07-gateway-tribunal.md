# DGE 査読劇: volta-gateway — Traefik が査読する

> Date: 2026-04-07
> Structure: ⚖ 査読劇 (tribunal)
> Evaluators: 🌐Proxy専門家 / 🎩千石 / 😈Red Team
> Respondents: ☕ヤン (先輩) + 🤝後輩
> Subject: volta-gateway v0.1.0 — Rust SM reverse proxy
> Verdict: Major Revision (all 3 reviewers)

## Phase 1: 独立評価

### 🌐 Proxy専門家 — Major Revision
Strengths: SM可視化の新規性, fail-closed, X-Volta-* stripping
Weaknesses: HTTP/2未対応, graceful shutdown なし, connection tuning なし,
  chunked body制限なし, access logにclient IPなし

### 🎩 千石 — Major Revision
Strengths: YAML 1ファイル設定, 教育的README, tramli レビュー公開の誠実さ
Weaknesses: エラーメッセージ不親切, config validation なし,
  メトリクス不足, 移行ガイドなし

### 😈 Red Team — Major Revision
Strengths: SM構造的制約で middleware bypass不可能, X-Volta-* strip, fail-closed default
Weaknesses: cargo audit なし, rate limiting 未実装, mTLS 非対応,
  IP allowlist なし, セキュリティヘッダ未付与

## Phase 2: 反論

今すぐ (5件):
  GW-2 エラーメッセージ reason (generic to client, detailed to log)
  GW-3 Global rate limiting (tower::RateLimitLayer)
  GW-4 セキュリティヘッダ (HSTS, nosniff, DENY)
  GW-5 Graceful shutdown (SIGTERM → drain → exit)
  GW-6 Access log client_ip

Phase 2 (7件):
  GW-1 HTTP/2 (auto::Builder), GW-7 chunked body, GW-8 config validation,
  GW-9 Prometheus, GW-10 移行ガイド, GW-12 IP allowlist, GW-14 connection tuning

Phase N (1件): GW-11 mTLS
CI (1件): GW-13 cargo audit

## Gap Summary

Critical: 1 (GW-5 graceful shutdown)
High: 3 (GW-1 HTTP/2, GW-3 rate limit, GW-4 security headers)
Medium: 6
Low: 4
Total: 14
