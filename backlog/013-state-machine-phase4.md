# Backlog 013: State Machine — Phase 4 (Invite Flow + Session SM)

## Phase: 4
## Priority: Medium
## Status: Open (blocked by 012)
## Spec: docs/AUTH-STATE-MACHINE-SPEC.md §7.4, §4, §6

---

## Goal

Invite Flow SM + Session SM 導入。Main.java の認証ロジック完全移行。

## Risk: High

## Deliverables

1. Invite Flow SM (account switch, session_id NULL, flow_ref HMAC)
2. Session SM 導入: sessions.auth_state カラム + version カラム
3. session_scopes テーブル (step-up 用)
4. step_up_log テーブル
5. Step-up Router (/step-up, /step-up/verify)
6. RequireScope middleware (backend API 403 + step_up_url)
7. Session cache (Caffeine, 5min TTL, revoke 時即 invalidation)
8. Optimistic locking (auth_flows.version + sessions.version)
9. Rate limiting middleware (IP別 / session別)
10. journey_id による認証ジャーニー追跡
11. Main.java 認証ロジック完全削除
