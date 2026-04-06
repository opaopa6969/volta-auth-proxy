# DGE Session: デバイストラスト (004-3)

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 🎩千石 / 😈Red Team / 🏥ハウス / 🎨深澤
> Theme: デバイストラストのライフサイクル、管理UI、Passkey連携
> Round: 1 (003-2 で基盤設計済み、差分のみ)
> Gaps: 8

## 前提 (003-2 で確定済み)

- デバイス識別: Persistent Cookie (__volta_device_trust, UUID, 90日, HttpOnly)
- DB: trusted_devices テーブル (max 10台, LRU削除)
- テナント設定: tenant_security_policies (new_device_action: notify/step_up)
- fraud-alert 連携: ExternalRiskService 経由

## Scene 1: ライフサイクル

信頼の開始: ユーザーが「記憶する」選択 → DB INSERT + cookie 発行
信頼の継続: ログイン時に cookie + DB マッチ → last_seen_at 更新、cookie リフレッシュ
信頼の失効:
  A. cookie 期限切れ (90日未使用) → 新デバイス扱い
  B. ユーザー手動削除 (設定画面)
  C. 管理者強制削除 (全デバイス)
  D. パスワード/MFA リセット時 → 全デバイス自動失効
  E. DB GC (180日 last_seen_at から)

→ Gap DT-1: デバイス失効 ≠ アクセスブロック の UX 説明 [Medium]
→ Gap DT-2: パスワード/MFA リセット時の全デバイス自動失効トリガー [High]
→ Gap DT-3: DB GC 設計 [Low]

## Scene 2: API + UI

ユーザー API:
  GET    /api/v1/users/me/devices         → 一覧 (IP一部マスク)
  DELETE /api/v1/users/me/devices/{id}    → 個別削除
  DELETE /api/v1/users/me/devices         → 全削除

管理者 API:
  GET    /api/v1/tenants/{tid}/users/{uid}/devices  → 閲覧のみ
  DELETE /api/v1/tenants/{tid}/users/{uid}/devices   → 全削除

デバイス名: User-Agent からブラウザ + OS を自動推定
  例: "Chrome on macOS", "Safari on iPhone"

→ Gap DT-4: API 設計 + IP マスク [Medium]
→ Gap DT-5: UA → デバイス名の共通化 [Low]

## Scene 3: Passkey との関係

Passkey = 認証方法（秘密鍵）。信頼デバイス = 通知ポリシー（cookie）。別概念。
Passkey でログイン → 自動デバイス信頼 (デフォルト ON、テナント設定で OFF 可)。
理由: Passkey = そのデバイスに秘密鍵がある = 本人のデバイスの可能性が高い。
ただしクラウド同期あり → 100% ではない → paranoid なテナントは OFF に。

→ Gap DT-6: Passkey 自動信頼設定 [Medium]
→ Gap DT-7: Passkey vs 信頼デバイスの関係文書化 [Low]

## Scene 4: セキュリティ設定画面

/settings/security に「信頼済みデバイス」セクション追加:
  - デバイス一覧 (名前, 最終アクセス, IP)
  - 個別削除 / 全削除
パスキー一覧とは別セクション（混同防止）。

→ Gap DT-8: 設定画面セクション追加 [Medium]

## Gap Summary

| # | Gap | Severity |
|---|-----|----------|
| DT-1 | 失効 ≠ ブロックの UX 説明 | Medium |
| DT-2 | パスワード/MFA リセット時の全デバイス失効 | High |
| DT-3 | DB GC (180日) | Low |
| DT-4 | デバイス API + IP マスク | Medium |
| DT-5 | UA → デバイス名推定の共通化 | Low |
| DT-6 | Passkey 自動信頼設定 | Medium |
| DT-7 | Passkey vs 信頼デバイスの文書化 | Low |
| DT-8 | 設定画面セクション追加 | Medium |

High: 1 / Medium: 4 / Low: 3 — Total: 8
003-2 の基盤上のライフサイクル + UI 差分のみ。構造的設計判断は完了。
