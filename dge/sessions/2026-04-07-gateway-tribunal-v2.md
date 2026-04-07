# DGE 査読劇 v2: volta-gateway — 論文再提出

> Date: 2026-04-07
> Structure: ⚖ 査読劇 (tribunal) — Round 2
> Subject: volta-gateway v0.1.0 + 5 tribunal fixes

## 改訂内容

5 fixes from v1 tribunal:
  GW-5 (Critical): Graceful shutdown → implemented (SIGTERM drain)
  GW-3 (High): Rate limiting → implemented (1000 req/sec global)
  GW-4 (High): Security headers → implemented (HSTS, nosniff, DENY)
  GW-2 (Medium): Error reason → implemented (generic to client)
  GW-6 (Medium): client_ip in log → implemented

## Verdicts

| Reviewer | v1 | v2 |
|----------|----|----|
| 🌐 Proxy専門家 | Major Revision | **Minor Revision** |
| 🎩 千石 | Major Revision | **Accept** |
| 😈 Red Team | Major Revision | **Accept** |

## New Gaps (v2)

| # | Gap | Severity | Action |
|---|-----|----------|--------|
| GW-15 | Drain timeout (30s forced exit) | Medium | 今すぐ |
| GW-16 | Per-IP rate limiting | Medium | Phase 2 |

## Remaining from v1 (Phase 2)

GW-1 HTTP/2, GW-7 chunked body, GW-8 config validation,
GW-9 Prometheus, GW-10 移行ガイド, GW-12 IP allowlist,
GW-14 connection tuning

## Summary

v1: 14 gaps, 3x Major Revision
v2: 2 new gaps, 2x Accept + 1x Minor Revision
Consensus: **Accept with drain timeout fix**
