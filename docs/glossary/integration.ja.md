# インテグレーション（統合）

[English version](integration.md)

---

## これは何？

インテグレーションとは、別々のソフトウェアシステムを接続して一つとして動かすプロセスです。各システムはそれぞれの仕事をしますが、明確に定義されたインターフェース -- API、ヘッダー、共有データベース、メッセージキューを通じて通信します。

レストランのキッチンで考えてみましょう。シェフ（あなたのアプリ）は料理を作ります。ホスト（Traefik）はお客様を案内します。用心棒（volta-auth-proxy）はドアでIDを確認します。誰も他の人の仕事はしませんが、シンプルな合図で統合しています：用心棒はリストバンド（X-Volta-*ヘッダー）を渡し、ホストはリストバンドを読んで正しいセクションに案内し、シェフはテーブル番号を見て料理を出します。インテグレーションとは、リストバンドとテーブル番号 -- 独立したシステム間の合意された信号のことです。

voltaの世界では、インテグレーションとは：volta-auth-proxyがTraefik（リバースプロキシ）、ダウンストリームアプリケーション、Google（OIDCプロバイダー）、PostgreSQL（データベース）とどう接続するかを意味します。

---

## なぜ重要なのか？

- **どのアプリも孤島ではない。** 現実のソフトウェアは常に他のシステムに依存する -- データベース、IDプロバイダー、リバースプロキシ、決済ゲートウェイ。
- **インテグレーションの失敗は本番障害の最大原因。** Google OIDCからのタイムアウト、Traefikの設定ミス、データベース接続の失敗 -- これらはインテグレーション問題。
- **インテグレーションがセキュリティ境界を定義する。** voltaとアプリ間の契約（ForwardAuth経由のX-Volta-*ヘッダー）が信頼境界。これが設定ミスだと認証が壊れる。
- **インテグレーションのテストはコードのテストより難しい。** ユニットテストはロジックを検証する。インテグレーションテストはシステムが実際に正しく通信するかを検証する。

---

## どう動くのか？

### インテグレーションパターン

| パターン | 仕組み | voltaでの例 |
|----------|--------|-------------|
| **リクエスト/レスポンス** | システムAがBを呼び、応答を待つ | voltaがGoogleのOIDCトークンエンドポイントを呼ぶ |
| **プロキシ/ミドルウェア** | システムAがクライアントとBの間に位置 | TraefikのForwardAuthがルーティング前にvoltaを呼ぶ |
| **共有データベース** | システムが同じDBを読み書き | voltaがセッションを書き、Internal APIが読む |
| **ヘッダーインジェクション** | プロキシがダウンストリームにメタデータヘッダーを追加 | voltaがアプリにX-Volta-User-IDを設定 |
| **Webhook/コールバック** | システムAがシステムBにリダイレクト | GoogleがvoltaのコールバックURLにリダイレクト |

### voltaのインテグレーションフロー

```
  ┌──────┐     ┌──────────┐     ┌───────────────┐     ┌──────────┐
  │ブラウザ│────▶│  Traefik  │────▶│ volta-auth-   │────▶│  Google   │
  │      │     │          │     │  proxy         │     │  OIDC     │
  │      │◀────│          │◀────│               │◀────│          │
  └──────┘     └─────┬────┘     └───────┬───────┘     └──────────┘
                     │                  │
                     │                  ▼
                     │          ┌───────────────┐
                     │          │  PostgreSQL    │
                     ▼          └───────────────┘
               ┌──────────┐
               │ あなたの  │
               │ アプリ    │
               │(X-Volta-*│
               │ を読む)   │
               └──────────┘
```

### インテグレーションポイント1：Traefik ForwardAuth

Traefikはアプリへのすべてのリクエストをインターセプトし、voltaに「このユーザーは許可されているか？」と尋ねます。

```
  ブラウザ → Traefik → volta (ForwardAuthチェック)
                         │
                    ┌────┴────┐
                    │         │
                 200 OK    401/403
                    │         │
                    ▼         ▼
            Traefikがアプリに   Traefikがブラウザに
            ルーティング       エラーを返す
            X-Volta-*ヘッダー
            付きで
```

Traefik設定（簡略化）：

```yaml
# Traefik ForwardAuthミドルウェア
http:
  middlewares:
    volta-auth:
      forwardAuth:
        address: "http://volta:8080/auth/verify"
        authResponseHeaders:
          - "X-Volta-User-ID"
          - "X-Volta-Tenant-ID"
          - "X-Volta-Role"
          - "X-Volta-Email"
```

### インテグレーションポイント2：Google OIDC

voltaはログイン用に[OIDCプロバイダー](oidc-provider.ja.md)としてGoogleと統合します：

```
  1. ユーザーが「Googleでログイン」をクリック
  2. voltaがGoogleの認可エンドポイントにリダイレクト
  3. ユーザーがGoogleで認証
  4. GoogleがvoltaのコールバックURLにリダイレクト
  5. voltaが認可コードをトークンと交換
  6. voltaがローカルセッションを作成
```

### インテグレーションポイント3：ダウンストリームアプリ

アプリは、voltaが承認した後にTraefikが注入するX-Volta-*[ヘッダー](header.ja.md)を読みます：

```java
// あなたのアプリ内（voltaではなく、ダウンストリームアプリ）
String userId   = request.getHeader("X-Volta-User-ID");
String tenantId = request.getHeader("X-Volta-Tenant-ID");
String role     = request.getHeader("X-Volta-Role");

// Traefikがvoltaのセッション検証後にのみヘッダーを設定するため、
// アプリはこれらのヘッダーを信頼する
```

### インテグレーションポイント4：Internal API

サーバー間統合のために、voltaは[Internal API](internal-api.ja.md)を公開します：

```
  あなたのバックエンド ──HTTP──▶ volta Internal API
                                  │
                                  ├─ GET /internal/users/{id}
                                  ├─ GET /internal/tenants/{id}/members
                                  └─ POST /internal/invitations
```

---

## volta-auth-proxy ではどう使われている？

### インテグレーションマップ

```
  ┌─────────────────────────────────────────────────┐
  │              volta-auth-proxy                    │
  │                                                  │
  │  統合先:                                         │
  │                                                  │
  │  ┌─────────────┐  ┌─────────────┐              │
  │  │ Google OIDC  │  │ PostgreSQL  │              │
  │  │ (ログイン)   │  │ (ストレージ) │              │
  │  └─────────────┘  └─────────────┘              │
  │                                                  │
  │  ┌─────────────┐  ┌─────────────┐              │
  │  │ Traefik      │  │ あなたのアプリ│              │
  │  │ (ForwardAuth)│  │ (ヘッダー/   │              │
  │  │              │  │  Int. API)  │              │
  │  └─────────────┘  └─────────────┘              │
  │                                                  │
  │  Phase 2:                                       │
  │  ┌─────────────┐                                │
  │  │ Redis        │                                │
  │  │ (セッション)  │                                │
  │  └─────────────┘                                │
  └─────────────────────────────────────────────────┘
```

### voltaと統合するためにアプリが必要なもの

1. volta ForwardAuthミドルウェアを持つTraefikの後ろにいること
2. `X-Volta-User-ID`、`X-Volta-Tenant-ID`、`X-Volta-Role`、`X-Volta-Email`ヘッダーを読むこと
3. これらのヘッダーを信頼すること（Traefikが外部リクエストからそれらを除去する）
4. 必要に応じてInternal APIを呼び出してユーザー/テナント管理を行うこと

---

## よくある間違いと攻撃

### 間違い1：Traefikなしで X-Volta-*ヘッダーを信頼

Traefikを経由せずにアプリにアクセスできる場合、誰でも偽の`X-Volta-User-ID`ヘッダーを設定できます。アプリはTraefik経由でのみ到達可能でなければなりません。Traefikが偽造ヘッダーを除去します。

### 間違い2：Google OIDCのコールバックURLが間違い

Google Cloud Consoleに登録したリダイレクトURIがvoltaのコールバックエンドポイントと正確に一致しないと、Googleがログインを拒否します。プロトコル（https vs http）、ドメイン、ポート、パスすべてが含まれます。

### 間違い3：ファイアウォールの設定ミス

Internal APIはインターネットに公開してはいけません。内部ネットワーク上のバックエンドサービスからのみ到達可能であるべきです。

### 間違い4：インテグレーションのタイムアウトを無視

Google OIDCが遅い場合やデータベースのコネクションプールが枯渇した場合、voltaはタイムアウトを適切に処理する必要があります -- 永遠にハングしてはいけません。すべての外部呼び出しに接続とリードのタイムアウトを設定しましょう。

### 間違い5：モックだけでインテグレーションをテスト

テストでGoogle OIDCをモックするのは有用ですが、実際にGoogleのエンドポイントを呼ぶ（テストプロジェクトで）リアルなインテグレーションテストも必要です。モックはAPI変更を検出できません。

---

## さらに学ぶ

- [forwardauth.ja.md](forwardauth.ja.md) -- TraefikとVolta間のインテグレーションポイント。
- [internal-api.ja.md](internal-api.ja.md) -- voltaとのサーバー間統合。
- [oidc.ja.md](oidc.ja.md) -- voltaがGoogleと統合するために使うプロトコル。
- [header.ja.md](header.ja.md) -- インテグレーションデータがHTTPヘッダーで伝わる方法。
- [app-integration.ja.md](app-integration.ja.md) -- ダウンストリームアプリ向け詳細ガイド。
