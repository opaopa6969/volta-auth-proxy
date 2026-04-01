# Backlog 004: Phase 4 未実装分

## Phase: 4
## Priority: Medium
## Status: 仕様なし → DGE セッションで設計が必要

---

## 未実装一覧

### 1. Policy Engine（jCasbin 統合）

```
仕様あり（docs/dsl-overview.md にドライバー戦略を定義済み）。

内容:
  - PolicyEvaluator interface 実装
  - jCasbin (Pure Java) をドライバーとして統合
  - volta policy.yaml → model.conf + policy.csv 変換
  - volta.can("edit", "document", context) SDK 拡張
  - casbin_rules テーブル

DGE: 仕様あり。実装タスク化可能。
```

### 2. GDPR データエクスポート / Right to be Forgotten

```
仕様なし。DGE で設計が必要。

考えられる機能:
  - POST /api/v1/users/{id}/export → ユーザーデータの JSON エクスポート
  - DELETE /api/v1/users/{id}/data → ユーザーデータ完全削除
  - Gateway + App 横断でのデータ削除オーケストレーション
  - Webhook: user.data_export_requested, user.data_deletion_requested
  - 削除確認 + 猶予期間（30 日等）

DGE で決めること:
  - エクスポート対象（users, memberships, sessions, audit_logs?）
  - App 側のデータ削除をどう伝えるか（Webhook? Internal API?）
  - 猶予期間の長さ
  - 監査証跡の保持（削除してもログは残す？）
```

### 3. デバイストラスト

```
仕様なし。DGE で設計が必要。

考えられる機能:
  - 既知のデバイスを記憶（device fingerprint or cookie）
  - 未知デバイスからのアクセスに追加検証
  - 「信頼済みデバイス一覧」管理 UI
  - 新デバイスログイン時の通知

DGE で決めること:
  - デバイス識別方法（fingerprint? persistent cookie? WebAuthn?）
  - DB テーブル設計（trusted_devices）
  - 「信頼」の有効期限
  - Phase 3 の条件付きアクセスとの統合
```

### 4. モバイル SDK (iOS/Android)

```
仕様なし。DGE で設計が必要。

考えられる機能:
  - ネイティブ iOS/Android SDK
  - 招待フローのディープリンク対応
  - 生体認証連携（Face ID, Touch ID, fingerprint）
  - PKCE + Custom URL Scheme for OIDC
  - セキュアな token 保存（Keychain/Keystore）

DGE で決めること:
  - Swift? Kotlin? React Native? Flutter?
  - Web View vs ネイティブ OIDC フロー
  - token 保存方式
  - volta-sdk-js との機能パリティ
```
