# Enforcement（強制）

[English version](enforcement.md)

---

## これは何？

強制とは、ルール違反を能動的に防ぐ行為です。ルールがあるだけでは不十分で、すべてのアクションをチェックしてルールを破るものをブロックするものが必要です。強制なきポリシーは提案です。強制ありのポリシーは法律です。

高速道路の速度制限のようなものです。標識は「60 km/h」と表示しています。それがポリシーです。しかし警察官やスピードカメラがなければ、ドライバーは120で走るでしょう。警察官こそが強制です。volta-auth-proxyは[YAML DSLファイル](dsl.ja.md)でルールを定義するだけでなく、すべてのリクエストでそれらを強制します。

voltaでは、複数のポイントで強制が行われます：プロキシされるすべてのリクエストでのForwardAuthチェック、すべての内部API呼び出しでのAPI認可、すべての[状態マシン](state-machine.ja.md)[遷移](transition.ja.md)での[ガード](guard.ja.md)チェック。

---

## なぜ重要なのか？

「ルールがある」と「ルールを強制している」の間のギャップこそ、セキュリティ侵害が起こる場所です：

- **未強制のロールチェック**：policy.yamlはADMIN+がメンバーを管理できると言うが、コードがチェックしない -- VIEWERでもメンバーを削除できる
- **クライアントサイドのみの強制**：UIは非ADMINから「削除」ボタンを隠すが、APIは誰からのDELETEも受け付ける
- **部分的な強制**：ForwardAuthはApp Aのロールをチェックするが、誰かがApp Bの設定を忘れた
- **強制のギャップ**：CSRFチェックがPOSTでは走るがDELETEでは走らない

---

## どう動くのか？

### 強制ポイント

```
  ブラウザからのリクエスト
  │
  ├── [1] Traefik ForwardAuth ──► volta /auth/verify
  │   │
  │   ├── セッションは有効？      （強制：認証）
  │   ├── テナントはアクティブ？    （強制：テナント状態）
  │   ├── ロールがallowed_rolesに？ （強制：アプリアクセス）
  │   │
  │   └── パス？ → X-Volta-*ヘッダー付きでアプリに転送
  │       失敗？ → 401または403
  │
  ├── [2] volta内部API
  │   │
  │   ├── JWTは有効？              （強制：認証）
  │   ├── JWTのテナント == パスのテナント？（強制：テナント分離）
  │   ├── ロール >= required_role？  （強制：RBAC）
  │   ├── 制約は満たされている？    （強制：ビジネスルール）
  │   │   例：「最後のOWNERは削除できない」
  │   │
  │   └── パス？ → 操作を実行
  │       失敗？ → 403または400
  │
  └── [3] 状態マシン遷移
      │
      ├── ガード式が真？           （強制：遷移ルール）
      ├── CSRFトークンは有効？      （強制：CSRF対策）
      └── レート制限を超えていない？ （強制：乱用防止）
```

### 多層防御

voltaは複数のレイヤーでルールを強制します。1つのレイヤーが失敗しても、別のレイヤーが違反を捕捉します：

```
  レイヤー1：Traefik
  ┌──────────────────────────────────────────────┐
  │ /auth/*パスのみvoltaにルーティング            │
  │ 設定されたドメインのみアプリにルーティング    │
  └──────────────────────────────────────────────┘
            │
  レイヤー2：volta ForwardAuth
  ┌──────────────────────────────────────────────┐
  │ セッション検証                                │
  │ テナント状態チェック                          │
  │ ロールベースのアプリアクセスチェック          │
  └──────────────────────────────────────────────┘
            │
  レイヤー3：volta内部API
  ┌──────────────────────────────────────────────┐
  │ JWT検証                                      │
  │ テナント分離（パス == JWTテナント）            │
  │ ロール階層チェック                            │
  │ ビジネスルール制約                            │
  └──────────────────────────────────────────────┘
            │
  レイヤー4：データベース制約
  ┌──────────────────────────────────────────────┐
  │ 外部キー、NOT NULL、一意制約                  │
  │ 最後の防衛線                                  │
  └──────────────────────────────────────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### ForwardAuth強制

`AppRegistry.java`が受信リクエストを設定済みアプリにマッチングし、`allowed_roles`を強制します：

```yaml
# volta-config.yaml
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
  - id: app-admin
    url: https://admin.example.com
    allowed_roles: [ADMIN, OWNER]
```

MEMBERが`admin.example.com`にアクセスしようとすると、voltaは403 ROLE_INSUFFICIENTを返します。アプリはリクエストを見ることさえありません。

### テナント分離の強制

パスに`{tenantId}`を含むすべてのAPI呼び出しは、JWTの`volta_tid`クレームと照合されます：

```yaml
# dsl/protocol.yaml
tenant_check:
  rule: "パスパラメータ{tenantId}はJWTクレームvolta_tidと等しくなければならない"
  error: TENANT_ACCESS_DENIED
```

これにより、ユーザーA（tenant-1）がURLを操作してユーザーB（tenant-2）のデータにアクセスするのを防ぎます。

### 制約の強制

`dsl/policy.yaml`はJavaコードで強制される制約を定義します：

```yaml
# policy.yaml
constraints:
  - id: last_owner
    rule: "テナントには常に最低1人のOWNERが必要"
    enforcement: "OWNERが0人になるロール変更/削除をブロック"
    error: "LAST_OWNER_CANNOT_CHANGE"
```

### レート制限の強制

`RateLimiter.java`が`policy.yaml`で定義されたレート制限を強制します：

```yaml
rate_limits:
  - endpoint: "GET /login"
    limit: 10
    window: "1 minute"
    key: ip
  - endpoint: "/api/v1/*"
    limit: 200
    window: "1 minute"
    key: user_id
```

### CSRF強制

CSRFトークンはすべてのHTMLフォーム送信（POST、DELETE、PATCH）で強制されますが、JSON APIリクエストは免除されます：

```yaml
# policy.yaml
csrf:
  scope: "HTMLフォームのPOST/DELETE/PATCHのみ"
  exempt: "JSON APIリクエスト"
  validation: "ハンドラ前にform._csrfとsession.csrf_tokenを比較"
```

---

## よくある間違いと攻撃

### 間違い1：クライアントサイドのみの強制

UIでボタンを隠すのは強制ではありません。APIエンドポイントがリクエストを受け付けるなら、ルールは強制されていません。常にサーバーで強制すること。

### 間違い2：原子性なき検査後実行

「最後のOWNERか？」をチェックしてから別のトランザクションで削除すると、競合状態が生じます。2つの同時リクエストが両方とも「OWNERは2人」と見て各1人を削除し、0人になる可能性があります。voltaは同じデータベーストランザクション内でチェックと実行を行います。

### 間違い3：エンドポイント間で一貫しない強制

`/api/v1/members`はロールをチェックするが`/api/v1/members/{id}`はチェックしなければ、攻撃者がギャップを見つけます。voltaはすべての`/api/v1/*`ルートに均一に認可ミドルウェアを適用します。

### 攻撃：TOCTOU（チェック時と使用時の間）

攻撃者がチェック時とアクション実行時のギャップを悪用します。例：voltaがメンバーの存在をチェックし、その後攻撃者が次のコード行の前にメンバーを削除する。voltaはデータベーストランザクションと必要に応じてSELECT ... FOR UPDATEを使ってこれを防ぎます。

---

## さらに学ぶために

- [forwardauth.md](forwardauth.md) -- アプリアクセスの主要な強制ポイント。
- [rbac.md](rbac.md) -- 強制されるロールベースのルール。
- [guard.md](guard.md) -- 状態マシンレベルでの強制。
- [policy-engine.md](policy-engine.md) -- jCasbinによる将来の強制。
- [hierarchy.md](hierarchy.md) -- ロール階層の強制方法。
- [csrf.md](csrf.md) -- CSRF強制の詳細。
- [tenant.md](tenant.md) -- テナント分離の強制。
