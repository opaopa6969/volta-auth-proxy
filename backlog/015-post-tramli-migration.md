# Backlog 015: Post-tramli Migration Tasks

## Phase: N (次フェーズ)
## Priority: Medium
## Status: Open

---

## Items

### 1. volta-platform つなぎ込み
- volta-platform 側で tramli 化後の auth-proxy と接続確認
- FlowEngine + FlowStore の結合テスト
- 前回セッションのハンドオフノート参照

### 2. コミット整理 & PR
- tramli 化 + パッケージ移行 (`org.unlaxer.infra.volta`) の変更をまとめて PR
- 不要ファイル（旧コアエンジン）の削除が git history に反映されていること確認

### 3. AUTH-010 完全移行（統合認証フロー）
- auth/ パッケージ（AuthFlowHandler, AuthFlowDefinition, AuthProcessors, AuthData, AuthState）は実装済み
- 残り: /login OIDC redirect + /callback + /mfa/challenge を全て AuthFlowHandler に統一
- OidcFlowRouter / MfaFlowRouter を削除し、1つの FlowDefinition で全認証を管理
- 前回の失敗: verify だけ新フローで callback が旧フロー → flow ID 不一致 → 400
- **全エンドポイントが同じ FlowDefinition を使うことが必須**

### 4. AskOS `org.unlaxer` パッケージ移行
- AskOS プロジェクト群を `org.unlaxer` ベースに移行
- Maven Central publish 要件のため unlaxer.org ドメイン配下に統一
