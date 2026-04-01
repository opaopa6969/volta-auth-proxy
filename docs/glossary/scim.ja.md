# SCIM（System for Cross-domain Identity Management）

[English version](scim.md)

---

## これは何？

SCIM（System for Cross-domain Identity Management）は、複数のシステム間でユーザーアカウントを自動管理するための標準プロトコルです。企業がOktaやAzure ADなどのIDプロバイダを使用している場合、SCIMによりそのプロバイダが接続されたすべてのアプリケーションでユーザーアカウントの作成、更新、削除を自動的に行えます。手作業は不要です。

学校の入学管理システムのようなものです。新入生が入学したとき、事務室が図書館システム、食堂システム、メールシステムに一つずつ手動でアカウントを作るわけではありません。代わりに、入学管理システムが接続されたすべてのシステムに自動で伝えます：「新入生アリス、5年生、給食オプションB」。アリスが転校したら、入学管理システムが全システムにアカウント無効化を伝えます。SCIMはエンタープライズソフトウェアで同じことをします。

SCIMは標準化されたエンドポイント（`/Users`、`/Groups`）を持つREST APIと、アイデンティティを表現するJSONスキーマを定義しています。SCIMを実装したアプリケーションは、互換性のあるIDプロバイダからユーザー[プロビジョニング](provisioning.md)コマンドを受信できます。

---

## なぜ重要なのか？

SCIMがないと、IT管理者は悪夢に直面します：

```
  手動プロビジョニング（SCIMなし）：
  ┌──────────┐
  │ IT管理者  │──── アプリAにアカウント作成
  │          │──── アプリBにアカウント作成
  │          │──── アプリCにアカウント作成
  │          │──── アプリDにアカウント作成
  │          │──── ...（新入社員ごとに）
  │          │
  │          │──── 退職者？
  │          │──── アプリAから削除（アプリB忘れたかも...）
  └──────────┘

  自動プロビジョニング（SCIMあり）：
  ┌──────────┐     ┌─────────┐     ┌───────┐
  │ IT管理者  │────►│  Okta   │────►│ アプリA │ (SCIM)
  │          │     │ (IdP)   │────►│ アプリB │ (SCIM)
  │          │     │         │────►│ アプリC │ (SCIM)
  │          │     │         │────►│ アプリD │ (SCIM)
  └──────────┘     └─────────┘     └───────┘
  （1つの操作）     （全アプリを自動同期）
```

主な利点：

- **セキュリティ**：プロビジョニング解除されたユーザーが全アプリから即座に削除される
- **コンプライアンス**：監査証跡がアクセスの付与/取り消しの正確なタイミングを示す
- **効率性**：100人のオンボーディングが数時間ではなく数秒で完了
- **正確性**：タイプミスなし、忘れたアプリなし、古いアカウントなし

---

## どう動くのか？

### SCIMエンドポイント

SCIM 2.0（RFC 7644）は以下の標準エンドポイントを定義しています：

| メソッド | エンドポイント | 目的 |
|---------|-------------|------|
| `POST` | `/scim/v2/Users` | ユーザー作成 |
| `GET` | `/scim/v2/Users/{id}` | 特定ユーザー取得 |
| `GET` | `/scim/v2/Users?filter=...` | ユーザー検索 |
| `PUT` | `/scim/v2/Users/{id}` | ユーザー置換（完全更新） |
| `PATCH` | `/scim/v2/Users/{id}` | 部分更新 |
| `DELETE` | `/scim/v2/Users/{id}` | ユーザー削除 |
| `POST` | `/scim/v2/Groups` | グループ作成 |
| `GET` | `/scim/v2/Groups/{id}` | 特定グループ取得 |
| `PATCH` | `/scim/v2/Groups/{id}` | グループメンバーシップ更新 |

### SCIMユーザースキーマ

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
  "id": "volta内のuser-uuid",
  "externalId": "okta-user-id-12345",
  "userName": "alice@acme.com",
  "name": {
    "givenName": "Alice",
    "familyName": "Smith"
  },
  "emails": [
    {
      "value": "alice@acme.com",
      "primary": true
    }
  ],
  "active": true,
  "groups": [
    {
      "value": "group-uuid",
      "display": "Engineering"
    }
  ]
}
```

### プロビジョニングフロー

```
  Okta（IDプロバイダ）                   volta-auth-proxy（サービスプロバイダ）
  ===================                   ====================================

  1. IT管理者がOktaでユーザー「Alice」を作成。
     OktaにvoltaがSCIMアプリとして設定済み。

  2. Oktaが送信：
     POST /scim/v2/Users
     Authorization: Bearer <scim-api-token>
     {
       "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
       "userName": "alice@acme.com",
       "name": {"givenName": "Alice", "familyName": "Smith"},
       "emails": [{"value": "alice@acme.com", "primary": true}],
       "active": true
     }

  ──────────────────────────────────────────────────────────►

                                        3. voltaがユーザーを作成：
                                           - アカウント作成
                                           - テナントに割り当て
                                           - デフォルトロール設定（MEMBER）
                                           - SCIMレスポンスを返す

  ◄──────────────────────────────────────────────────────────

  4. OktaがvoltaのユーザーIDを保存（externalIdマッピング）。
     以降の更新はこのIDを使用。

  === 後日：AliceがOktaで無効化される ===

  5. Oktaが送信：
     PATCH /scim/v2/Users/{volta-user-id}
     {
       "schemas": [
         "urn:ietf:params:scim:api:messages:2.0:PatchOp"
       ],
       "Operations": [
         {"op": "replace", "path": "active", "value": false}
       ]
     }

  ──────────────────────────────────────────────────────────►

                                        6. voltaがユーザーを無効化：
                                           - すべてのセッションを取り消し
                                           - アカウントを非アクティブに
                                           - ログイン不可に
```

### SCIMフィルタリング

IDプロバイダはユーザー作成前に既存チェックのためフィルタリングに依存します（重複回避）：

```
  GET /scim/v2/Users?filter=userName eq "alice@acme.com"

  レスポンス：
  {
    "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
    "totalResults": 1,
    "Resources": [
      {
        "id": "volta-user-uuid",
        "userName": "alice@acme.com",
        ...
      }
    ]
  }
```

---

## volta-auth-proxyではどう使われている？

### voltaのSCIMエンドポイント

voltaは`/scim/v2/*`配下にSCIM 2.0サービスプロバイダエンドポイントを実装しています：

```
  volta-auth-proxy SCIMエンドポイント：
  ┌─────────────────────────────────────────────────┐
  │  POST   /scim/v2/Users          → ユーザー作成   │
  │  GET    /scim/v2/Users/:id      → ユーザー取得   │
  │  GET    /scim/v2/Users?filter=  → ユーザー検索   │
  │  PUT    /scim/v2/Users/:id      → ユーザー置換   │
  │  PATCH  /scim/v2/Users/:id      → ユーザー更新   │
  │  DELETE /scim/v2/Users/:id      → ユーザー削除   │
  │                                                  │
  │  POST   /scim/v2/Groups         → グループ作成   │
  │  GET    /scim/v2/Groups/:id     → グループ取得   │
  │  PATCH  /scim/v2/Groups/:id     → グループ更新   │
  │  DELETE /scim/v2/Groups/:id     → グループ削除   │
  │                                                  │
  │  GET    /scim/v2/ServiceProviderConfig            │
  │  GET    /scim/v2/Schemas                          │
  │  GET    /scim/v2/ResourceTypes                    │
  └─────────────────────────────────────────────────┘
```

### SCIM認証

IDプロバイダからのSCIMリクエストは、SCIM統合専用の[Bearerトークン](bearer-scheme.md)で認証されます。このトークンはテナント管理者がSCIMプロビジョニングを設定する際に生成され、ユーザーの[セッション](session.md)トークンとは別物です。

### SCIMとvoltaの概念のマッピング

| SCIMの概念 | voltaの概念 |
|-----------|------------|
| User | [テナント](tenant.md)のメンバー |
| Group | [ロール](role.md)グループ |
| `active: false` | ユーザー[停止](suspension.md) |
| `externalId` | IDプロバイダのユーザーID |
| `userName` | メールアドレス（voltaの主識別子） |

### SCIM + Webhook

SCIM操作でユーザーが作成・更新されると、voltaが[Webhook](webhook.md)イベントを発火してアプリケーションの同期を維持します：

```
  Okta → SCIM POST /Users → voltaがユーザー作成
                                  ↓
                            webhook: user.created
                                  ↓
                            アプリが通知を受信
```

---

## よくある間違いと攻撃

### 間違い1：SCIMフィルタリングを実装しない

IDプロバイダはユーザーの存在チェックにフィルタリングに依存します。`filter=userName eq "..."`がないと、プロビジョニングのたびに重複が作成されます。

### 間違い2：`active`フィールドを無視する

IdPが`active: false`を送ったら、即座にアクセスを取り消す必要があります。フィールドは更新するが[セッション](session.md)を無効化しない実装があります。セッションが自然に期限切れになるまでユーザーはログインしたままになります。

### 間違い3：SCIM DELETEをハードデリートとして扱う

ベストプラクティスはソフトデリート：ユーザーを非アクティブにしてコンプライアンスのためにデータを保持。IdPが後で同じユーザーを再プロビジョニングする可能性があります。

### 間違い4：PATCHをサポートしない

PUTのみ（完全置換）をサポートする実装もあります。最新のIdPは効率のためPATCHを好みます。Oktaは特に無効化にPATCHを使います。

### 攻撃：SCIMトークンの窃取

SCIMのBearerトークンが盗まれると、攻撃者がテナントに管理者ユーザーを作成できます。防御：SCIMエンドポイントを既知のIdP IPレンジに制限、トークンの定期ローテーション、全SCIM操作のログ記録。

### 攻撃：大量プロビジョニング解除

侵害されたIdPアカウントがSCIM経由で全ユーザーを無効化する可能性。防御：SCIMエンドポイントのレート制限、一括操作のアラート、大量プロビジョニング解除の手動確認ステップ。

---

## さらに学ぶために

- [RFC 7644](https://tools.ietf.org/html/rfc7644) -- SCIM 2.0プロトコル仕様。
- [RFC 7643](https://tools.ietf.org/html/rfc7643) -- SCIM 2.0コアスキーマ。
- [provisioning.md](provisioning.md) -- ユーザープロビジョニングの広い概念。
- [tenant.md](tenant.md) -- voltaがテナントにユーザーをどう整理するか。
- [role.md](role.md) -- SCIMグループがvoltaロールにどうマッピングされるか。
- [webhook.md](webhook.md) -- SCIM操作時に発火されるイベント。
