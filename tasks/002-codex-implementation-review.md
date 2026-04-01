# Task 002: Codex 実装レビュー（Phase 2-4 コミット）

## 対象

Codex コミット: `5ed8ede` — "Implement phases 2-4 features, harden auth flows, and add SAML signature verification"
4,370 行追加。Phase 2-4 を一気に実装。

## レビュー項目

### 🔴 Critical（必ず確認）

1. **招待フローの membership 競合（Bug 1）は直ったか？**
   - /callback で初回ユーザーの findMembership が失敗するバグ
   - AuthService.java の callback 処理を確認
   - invite_code 付きの場合に membership チェックをスキップしているか

2. **CSRF 保護は入っているか？**
   - POST/DELETE/PATCH に CSRF トークン検証があるか
   - SecurityUtils.java に CSRF 関連のコードがあるか
   - V3__csrf_token.sql が適用されているか
   - JSON API は CSRF 免除されているか

3. **最後の OWNER 降格防御**
   - SqlStore.java or AuthService.java で OWNER 数チェックがあるか
   - updateMemberRole で最後の OWNER 変更を拒否しているか

### 🟠 High（重要）

4. **ForwardAuth (/auth/verify) の実装**
   - X-Volta-* ヘッダが正しく返されるか
   - tenant suspended の場合の 403 処理
   - Content Negotiation (JSON には 302 を返さない)

5. **SAML 署名検証**
   - SamlService.java で XML 署名検証が実装されているか
   - SAML_SKIP_SIGNATURE 環境変数で開発時スキップ可能か

6. **M2M 認証 (Client Credentials)**
   - POST /oauth/token の実装
   - client_id / client_secret の検証
   - service-scoped JWT の発行

7. **MFA/TOTP**
   - TOTP setup / verify のフロー
   - テナント MFA 強制の仕組み
   - recovery codes

8. **Webhook 配信**
   - OutboxWorker.java の実装
   - リトライ + 指数バックオフ
   - HMAC-SHA256 署名

### 🟡 Medium（確認）

9. **テンプレートの品質**
   - admin/audit.jte, idp.jte, tenants.jte, users.jte, webhooks.jte
   - ちゃんと動く HTML が書かれているか

10. **テスト**
    - SamlServiceTest.java の内容
    - mvn test が通るか（✅ 確認済み）

11. **マイグレーション整合性**
    - V4-V11 が正しい順序で適用されるか
    - 既存テーブルとの FK 整合性

## コマンド

```bash
# テスト
mvn test

# 差分確認
git show --stat 5ed8ede
git diff 0a9c966..5ed8ede -- src/main/java/

# 特定ファイルの差分
git diff 0a9c966..5ed8ede -- src/main/java/com/volta/authproxy/AuthService.java
git diff 0a9c966..5ed8ede -- src/main/java/com/volta/authproxy/Main.java
```
