# Client Credentials（OAuth 2.0グラントタイプ）

[English version](client-credentials.md)

---

## これは何？

Client Credentialsグラントは、サーバー間（マシン間、M2M）通信のために設計されたOAuth 2.0のフローです。ユーザーが関与する他のOAuthフローとは異なり、Client Credentialsはサービスが自分自身として認証できます。人間はループに入りません。サービスが独自の認証情報（client_idとclient_secret）を提示し、アクセストークンを受け取ります。

ロボット用の社員証のようなものです。ロボットは人間の従業員のような個人のアイデンティティを持ちません。でもシステムに登録され、独自のバッジを与えられ、特定のタスクを実行する権限が与えられています。ロボットが部屋に入るとき、バッジをかざします。人間のふりはしません。

---

## なぜ重要なのか？

現代のアーキテクチャでは、サービス同士が常に通信しています：

```
  ┌────────────┐     ┌────────────┐     ┌────────────┐
  │ 請求       │────►│ ユーザー    │────►│ 通知        │
  │ サービス   │     │ サービス    │     │ サービス    │
  └────────────┘     └────────────┘     └────────────┘
```

各サービスは、呼び出し元のサービスが認可されているか検証する必要があります。適切なM2M認証がないと：

- どのサービス（または攻撃者）でも内部APIを呼べてしまう
- 各サービスができることを制限する方法がない
- サービス間呼び出しの監査証跡がない
- 認証情報のローテーションに手動の調整が必要

Client Credentialsは、適切なスコープ、有効期限、ローテーション付きで、サービスが互いに認証するための標準化された方法を提供します。

---

## どう動くのか？

### フロー

```
  サービスA（クライアント）            volta-auth-proxy（認証サーバー）
  ========================            ================================

  1. サービスAがvoltaのAPIを呼んでテナントメンバーをリスト。
     ユーザーの代理ではない。独自のアイデンティティが必要。

  2. サービスAが認証情報を送信：

     POST /oauth/token
     Content-Type: application/x-www-form-urlencoded

     grant_type=client_credentials
     &client_id=billing-service
     &client_secret=s3cr3t-k3y-for-billing
     &scope=read:members write:billing

  ──────────────────────────────────────────────────────────►

                                        3. voltaがチェック：
                                           - client_idは登録済みか？
                                           - client_secretは一致するか？
                                           - リクエストされたスコープは
                                             このクライアントに許可されるか？

                                        4. voltaがJWTを発行：
                                           {
                                             "sub": "billing-service",
                                             "volta_client": true,
                                             "volta_client_id": "billing-service",
                                             "volta_tid": "acme-uuid",
                                             "volta_roles": ["read:members",
                                                             "write:billing"],
                                             "exp": <現在から5分後>
                                           }

  ◄──────────────────────────────────────────────────────────

  5. サービスAが受信：
     {
       "access_token": "eyJhbGci...",
       "token_type": "bearer",
       "expires_in": 300
     }

  6. サービスAがトークンを使ってAPIを呼ぶ：

     GET /api/v1/tenants/acme-uuid/members
     Authorization: Bearer eyJhbGci...

  ──────────────────────────────────────────────────────────►

                                        7. voltaがJWTを検証
                                           - volta_client=trueをチェック
                                           - スコープに"read:members"
                                             が含まれるかチェック
                                           - メンバーリストを返す

  ◄──────────────────────────────────────────────────────────

  8. サービスAがメンバーリストを受信して処理。
```

### Client Credentials vs Authorization Codeの使い分け

| シナリオ | グラントタイプ | 理由 |
|---------|-------------|------|
| ユーザーがブラウザでログイン | Authorization Code | 人間がいる。権限を承認できる。 |
| モバイルアプリがユーザーデータにアクセス | Authorization Code + PKCE | 人間がいる。PKCEがコードを保護。 |
| バックエンドサービスが別のサービスを呼ぶ | **Client Credentials** | 人間が関与しない。サービスが自分として認証。 |
| Cronジョブがデータを処理 | **Client Credentials** | 人間が関与しない。スケジュールで実行。 |
| Webhookハンドラ | **Client Credentials** | 人間が関与しない。外部イベントでトリガー。 |

ルールはシンプル：**人間が関与するならAuthorization Code。マシンだけならClient Credentials。**

---

## volta-auth-proxyはM2M認証をどう扱うか？

### Phase 1：静的サービストークン（現在）

Phase 1では、voltaは静的トークンを使ったシンプルなM2Mメカニズムを提供します：

```
  設定：
    VOLTA_SERVICE_TOKEN=my-very-long-random-secret-token

  使用方法：
    Authorization: Bearer volta-service:my-very-long-random-secret-token

  voltaがサービスプリンシパルを返す：
    userId:    00000000-0000-0000-0000-000000000000
    email:     service@volta.local
    roles:     ["SERVICE"]
```

機能的ですが制限があります：

| 制限 | 影響 |
|------|------|
| すべてのサービスで1つのトークン | 各サービスができることを制限できない |
| 有効期限なし | 環境変数を変更するまでトークンは永久に有効 |
| サービスごとの監査なし | ログでサービスAとサービスBを区別できない |
| スコープなし | トークンがフルアクセスを持つ |
| ダウンタイムなしのローテーション不可 | トークン変更にリデプロイが必要 |

### Phase 2：Client Credentials（計画）

計画中の実装は適切なM2M認証を追加します：

```
  データベース：oauth_clientsテーブル
  ┌─────────────────────────────────────────────────────┐
  │  client_id:     "billing-service"                    │
  │  client_secret: <bcryptハッシュ>                     │
  │  tenant_id:     acme-uuid                            │
  │  scopes:        ["read:members", "write:billing"]    │
  │  name:          "請求サービス"                        │
  │  active:        true                                 │
  │  created_by:    admin-uuid                           │
  │  created_at:    2026-03-31T09:00:00Z                 │
  └─────────────────────────────────────────────────────┘
```

静的トークンに対する利点：

| 機能 | 静的トークン | Client Credentials |
|------|------------|-------------------|
| サービスごとのID | なし | あり（client_id） |
| スコープ付き権限 | なし | あり（scopes） |
| トークンの有効期限 | なし | あり（5分のJWT） |
| 監査証跡 | 汎用的な「service」 | クライアントごとのログ |
| シークレットのローテーション | リデプロイが必要 | クライアントごとにローテーション |
| テナントスコープ | なし | あり（テナントごとのクライアント） |

### voltaのM2M JWT

サービスがClient Credentialsで認証すると、voltaは特別なM2Mクレーム付きJWTを発行します：

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "billing-service-principal-uuid",
  "exp": 1711900000,
  "iat": 1711899700,
  "volta_v": 1,
  "volta_client": true,
  "volta_client_id": "billing-service",
  "volta_tid": "acme-uuid",
  "volta_roles": ["read:members", "write:billing"]
}
```

`volta_client: true`フラグはvolta（とアプリ）にこれがマシントークンであり人間のトークンではないことを伝えます。これにより異なる処理が可能になります：

- M2Mトークンには異なるレート制限が設定される可能性
- 監査ログがユーザー名の代わりに「billing-service」を記録
- 一部の操作は人間のユーザーのみに制限される可能性

### JwtService.issueM2mToken()メソッド

voltaにはM2Mトークン発行メソッドが既に準備されています：

```java
// JwtService.java
public String issueM2mToken(UUID clientPrincipalId, UUID tenantId,
                            List<String> scopes, List<String> audience,
                            String clientId) {
    AuthPrincipal principal = new AuthPrincipal(
        clientPrincipalId,
        "m2m@" + clientId,
        clientId,
        tenantId, "machine", "machine",
        scopes, true
    );
    return issueToken(principal, audience,
        Map.of("volta_client", true, "volta_client_id", clientId));
}
```

---

## よくある間違いと攻撃

### 間違い1：ユーザー向けフローでClient Credentialsを使う

ユーザーがいるべきときにClient Credentialsを使うと失われるもの：
- どのユーザーがリクエストを送ったか知る能力
- ユーザーの同意（ユーザーは何も承認していない）
- ユーザーごとの監査証跡

### 間違い2：ソースコードにclient_secretをハードコーディングする

ソースコード内のクライアントシークレットはバージョン管理に入ります。環境変数またはシークレットマネージャーを使ってください。

### 間違い3：クライアントの権限をスコープしない

すべてのサービスにフルアクセス（「admin」スコープ）を与えるのは目的に反します。各サービスは必要最小限のスコープを持つべきです（最小権限の原則）。

### 間違い4：長寿命のM2Mトークン

M2Mトークンには有効期限を設けるべきです（volta: 5分）。サービスはトークンの期限切れ時に再認証すべきです。トークンが漏洩した場合の被害を制限します。

### 攻撃：トークンエンドポイントへのクレデンシャルスタッフィング

攻撃者が`/oauth/token`に対して多くのclient_id/client_secretの組み合わせを試す。防御：トークンエンドポイントのレート制限（voltaはIPごとのレート制限を適用）。

### 攻撃：client_secretの窃取

サービスのclient_secretが盗まれた場合（ログ、環境変数などから）、攻撃者はそのサービスになりすませる。防御：定期的にシークレットをローテーション、短命トークンの使用、異常なM2Mアクティビティの監視。

---

## さらに学ぶために

- [RFC 6749 Section 4.4](https://tools.ietf.org/html/rfc6749#section-4.4) -- Client Credentialsグラント仕様。
- [oauth2.md](oauth2.md) -- OAuth 2.0の概要とグラントタイプ。
- [jwt.md](jwt.md) -- M2M JWTの構造。
- [forwardauth.md](forwardauth.md) -- リクエストフローでM2Mトークンがどう検証されるか。
