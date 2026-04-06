# DGE Session: 条件付きアクセス — Round 5 (Final: High 完遂 + トリアージ)

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 🎩千石 / 😈Red Team / 🏥ハウス / 🎨深澤
> Round: 5 (Final)
> New Gaps: 0
> Resolved: G25, G27, G28

## G25 解決: step_up / MFA 整合性バリデーション

双方向バリデーション:
- step_up 有効化 → mfa_required が前提
- risk_action_threshold < 5 → mfa_required が前提
- mfa_required 無効化 → step_up が先に無効化されていること
管理画面: MFA OFF ならstep_up ラジオボタンがグレーアウト。

## G27 解決: テナント ↔ siteId マッピング

volta 全体で 1 siteId。テナント分離は userHash で:
  userHash = SHA256(tenantId + ":" + userId)
スコアリング共通、閾値はテナント別 (tenant_security_policies.risk_action_threshold)。
fraud-alert 側の変更不要。

## G28 解決: userHash 生成

SHA256(tenantId + ":" + userId)。テナント ID を含めてテナント間分離。

## Medium/Low トリアージ

実装時に自然に決まる:
  G6 → JWT再発行時にamr再計算
  G11 → RFC 8176 標準値: pwd, otp, hwk, fed
  G14 → session.user_agent vs req.User-Agent の equals()
  G30 → Router末尾で非同期 fire-and-forget

Spec/文書化が必要:
  G7 → ADR として auth_state/scopes/amr 関係を記録
  G10 → messages_ja/en.properties に通知文追加
  G12 → dge/decisions/ に ADR 記録 (DVE 連携)
  G13 → audit_log 記録のみ (Phase 1)
  G26 → jte テンプレート + Messages

UI/フロント:
  G16 → MFA後に「記憶する」表示
  G23 → admin/security ページに追加
  G24 → /api/v1/users/{id}/devices CRUD
  G29 → error.jte バリエーション

YAGNI / Phase N:
  G17 → 実運用で問題が出てから
  G21 → OutboxWorker 統合
  G31 → Phase 1 は閾値のみ

## Final Status: 全 31 Gap

| Status | Count |
|--------|-------|
| Critical RESOLVED | 3/3 |
| High RESOLVED | 10/10 |
| Medium Open (実装時解決) | 13 |
| Low Open (YAGNI含む) | 5 |

**Critical + High = 全 RESOLVED。設計判断は全て完了。実装着手可能。**
