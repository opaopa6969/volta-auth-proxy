# 内部API（Internal API）

[English version](internal-api.md)

---

## これは何？

内部APIは、自分のネットワーク内からのみアクセス可能なAPIです -- パブリックインターネットには公開されません。外部ユーザーは直接到達できません。同じネットワーク内で動作する自分のサービスだけが呼び出せます。

オフィスビルの内線電話のようなものです。ビル内の人は任意の内線番号に電話できます。ビル外の人は通りから内線に電話できません。内線は「内部」通信システムです -- 部署間の連携に便利ですが、外の世界からは見えません。

---

## なぜ重要なのか？

volta-auth-proxyは`/api/v1/*`に内部APIを公開しており、アプリケーションがユーザーとテナントの管理操作を行えるようにしています。このAPIはvolta（認証を処理）とアプリ（ビジネスロジックを処理）の橋渡しです。内部APIと外部APIの区別を理解することは、セキュリティとアーキテクチャにとって不可欠です。

---

## 内部API vs 外部API

```
  インターネット（パブリック）
  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  外部/パブリックAPI：                                   │
  │  - https://api.stripe.com/v1/charges                    │
  │  - https://maps.googleapis.com/maps/api/geocode         │
  │  - voltaの /auth/* エンドポイント（ログイン、コールバック、検証）│
  │                                                         │
  │  インターネット上の誰でも到達可能。                      │
  └─────────────────────────────────────────────────────────┘
          │
          │  ファイアウォール / ネットワーク境界
          │
  ┌───────▼─────────────────────────────────────────────────┐
  │  プライベートネットワーク（内部）                        │
  │                                                         │
  │  内部API：                                              │
  │  - voltaの /api/v1/*（ユーザー、テナント、メンバーCRUD） │
  │  - データベース（PostgreSQL ポート5432）                 │
  │  - サービス間通信                                       │
  │                                                         │
  │  自分のサービスだけが到達可能。                          │
  └─────────────────────────────────────────────────────────┘
```

| 観点 | 外部/パブリックAPI | 内部API |
|------|-------------------|---------|
| 誰がアクセスできるか | インターネット上の誰でも | ネットワーク内のサービスのみ |
| 認証 | ユーザー資格情報、OAuthトークン | サービストークン、ネットワークレベルの信頼 |
| レート制限 | 厳格（悪用から保護） | 緩い（信頼された呼び出し元） |
| ドキュメント | 公開ドキュメント、SDK | 内部ドキュメント、チームの知識 |
| バージョニング | 慎重（破壊的変更が顧客に影響） | 柔軟（全呼び出し元を自分がコントロール） |
| 例 | Auth0 Management API、Stripe API | volta `/api/v1/*`、データベース接続 |

---

## voltaの内部API: /api/v1/*

voltaの内部APIは、ダウンストリームアプリが認証操作をvoltaに委譲できるようにします。アプリが`users`、`tenants`、`memberships`テーブルを直接変更する代わりに、voltaのAPIを呼び出します：

### 主なエンドポイント

```
  voltaの内部APIによるアプリ委譲：

  ユーザー管理：
    GET    /api/v1/tenants/{tenantId}/members          メンバー一覧
    PATCH  /api/v1/tenants/{tenantId}/members/{userId}  ロール変更
    DELETE /api/v1/tenants/{tenantId}/members/{userId}  メンバー削除

  テナント管理：
    GET    /api/v1/tenants/{tenantId}                   テナント情報取得
    PATCH  /api/v1/tenants/{tenantId}                   テナント更新

  招待管理：
    POST   /api/v1/tenants/{tenantId}/invitations       招待作成
    GET    /api/v1/tenants/{tenantId}/invitations        招待一覧
    DELETE /api/v1/tenants/{tenantId}/invitations/{id}   招待キャンセル

  セッション管理：
    GET    /api/v1/users/{userId}/sessions              セッション一覧
    DELETE /api/v1/users/{userId}/sessions/{sessionId}   セッション無効化
    DELETE /api/v1/users/{userId}/sessions               全セッション無効化
```

### 内部APIの認証

voltaの内部APIは2つの認証方法を使います：

1. **サービストークン：** `VOLTA_SERVICE_TOKEN`環境変数で設定する静的トークン。バックエンドサービスが`Authorization`ヘッダーに含めます：

```
Authorization: Bearer <VOLTA_SERVICE_TOKEN>
```

2. **ユーザーJWT：** ForwardAuthからの`X-Volta-JWT`。ユーザーがプロキシ経由で既に認証されている場合、そのJWTで権限の範囲内のAPI呼び出しを認可できます。

### アプリがDBに直接書き込まずvoltaに委譲する理由

```
  選択肢A: アプリがvoltaのデータベースに直接書き込む（悪い）
  ┌─────────┐     ┌──────────────┐
  │ アプリ   │────►│ voltaのDB     │  ← 密結合。スキーマ変更でアプリが壊れる。
  └─────────┘     │ (users,       │     バリデーションなし。監査ログなし。
                  │  tenants,     │     アプリがvoltaのスキーマを知る必要あり。
                  │  memberships) │
                  └──────────────┘

  選択肢B: アプリがvoltaの内部APIを呼ぶ（良い）
  ┌─────────┐     ┌──────────────┐     ┌──────────────┐
  │ アプリ   │────►│ volta API     │────►│ voltaのDB     │
  └─────────┘     │ /api/v1/*     │     └──────────────┘
                  │               │
                  │ - バリデーション│  ← きれいな境界。バリデーション付き。
                  │ - 監査ログ    │     監査ログ自動。
                  │ - ルール      │     ビジネスルール強制。
                  │   の強制      │     スキーマ変更がアプリに影響しない。
                  └──────────────┘
```

内部APIはvoltaとアプリケーション間の安定した契約を提供します。voltaのデータベーススキーマが変更されても、API契約が安定しているため、アプリは壊れません。

---

## 内部APIのネットワークセキュリティ

内部APIはパブリックインターネットから到達可能であってはなりません。一般的なアプローチ：

1. **Dockerネットワーク分離：** voltaとアプリが同じDockerネットワークを共有。APIポートはホストに公開しない。
2. **ファイアウォールルール：** `/api/v1/*`へのトラフィックを既知の内部IPからのみ許可。
3. **リバースプロキシ設定：** Traefikが`/api/v1/*`を内部サービスからのみルーティングし、外部リクエストからはルーティングしない。

```yaml
# Traefik: /api/v1/* を内部ネットワークからのみルーティング
http:
  routers:
    volta-internal-api:
      rule: "PathPrefix(`/api/v1/`) && ClientIP(`10.0.0.0/8`)"
      service: volta-service
```

---

## さらに学ぶために

- [api.ja.md](api.ja.md) -- API概念全般。
- [forwardauth.ja.md](forwardauth.ja.md) -- 外部向けの認証メカニズム。
- [api-versioning.ja.md](api-versioning.ja.md) -- voltaが`/api/v1/`を使う理由。
- [private-vs-public.ja.md](private-vs-public.ja.md) -- 暗号化とネットワーキングにおけるパブリック vs プライベート。
