# Service Token（サービストークン）

[English version](service-token.md)

---

## これは何？

サービストークンとは、マシン間認証に使われる静的な事前共有シークレットです。ユーザーの[セッション](session.ja.md)（ブラウザに紐付く）や[JWT](jwt.ja.md)（短命、署名付き）とは異なり、サービストークンはバックグラウンドジョブ、cronタスク、内部サービスがvolta-auth-proxyのAPIに対して認証するために使う長寿命の文字列です。

ビル管理のマスターキーのようなものです。従業員は個別のキーカード（セッション）を持ち、期限切れや無効化が可能です。しかし清掃員はすべてのドアを開けるマスターキーを持ち、それ自体は期限切れにならず、チーム内で共有されます。個別のキーより安全性は低いですが、人間がカードをスワイプしていないときにビルが機能するために必要です。

volta-auth-proxyでは、サービストークンは`VOLTA_SERVICE_TOKEN`環境変数で設定されます。リクエストは`Authorization: Bearer volta-service:{token}`を送信して認証します。サービストークンリクエストは通常のユーザー認証をバイパスしますが、`X-Volta-Tenant-Id`ヘッダーによるテナントコンテキストは依然として必要です。

---

## なぜ重要なのか？

すべてのAPI呼び出しがブラウザに座っているログインユーザーから来るわけではありません。以下からも来ます：

- **バックグラウンドジョブ**：期限切れセッションの夜間クリーンアップ
- **cronタスク**：招待リマインダーメールの送信
- **内部サービス**：メンバーシップ状態を確認する請求サービス
- **CI/CDパイプライン**：テナントの自動プロビジョニング

サービストークンがなければ、これらのジョブは：
- ユーザーログインを偽装する必要がある（不安全、脆弱）
- 認証を完全にバイパスする必要がある（危険）
- ユーザーの認証情報を平文で保存する必要がある（最悪）

サービストークンはマシンがAPIを呼び出すためのクリーンで、監査可能で、無効化可能な方法を提供します。

---

## どう動くのか？

### 認証フロー

```
  バックグラウンドジョブ                    volta-auth-proxy
  ┌──────────────┐                       ┌──────────────────┐
  │              │  Authorization:        │                  │
  │ GET /api/v1/ │  Bearer volta-service: │  1. トークン抽出 │
  │ tenants/{id}/│  {VOLTA_SERVICE_TOKEN} │  2. 環境変数と   │
  │ members      │  ─────────────────────►│     比較         │
  │              │  X-Volta-Tenant-Id:    │  3. 一致？ →     │
  │              │  {tenant-uuid}         │     AuthPrincipal│
  │              │                        │     (service)    │
  │              │◄─── 200 [{members}] ───│                  │
  └──────────────┘                       └──────────────────┘
```

### サービスAuthPrincipal

サービストークンが正常に認証されると、voltaは特別な`AuthPrincipal`を作成します：

```java
// AuthService.java
return Optional.of(new AuthPrincipal(
    new UUID(0L, 0L),           // userId: オールゼロ（実ユーザーではない）
    "service@volta.local",       // email: 合成
    "service-token",             // displayName
    new UUID(0L, 0L),           // tenantId: X-Volta-Tenant-Idヘッダーから
    "service",                   // tenantName
    "service",                   // tenantSlug
    List.of("SERVICE"),          // roles: SERVICE（特別なロール）
    true                         // serviceToken: true
));
```

`serviceToken: true`フラグにより、下流のコードがユーザーリクエストとサービスリクエストを区別できます。

### トークン形式

```
  Authorization: Bearer volta-service:abc123xyz789...
                        ├──────────────┤├────────────┤
                        プレフィックス    実際のトークン
                        （トークンの      （VOLTA_SERVICE_TOKEN
                         種類を識別）     環境変数に一致）
```

`volta-service:`プレフィックスがサービストークンとJWT（プレフィックスなし）を区別します。

### セキュリティ制約

```
  ┌─────────────────────────────────────────────────────┐
  │ サービストークンの能力：                             │
  │                                                     │
  │ ✓ すべての/api/v1/*エンドポイントを呼べる           │
  │ ✓ 任意のテナントを代理して操作可能（ヘッダー経由） │
  │ ✓ セッション/Cookie要件をバイパス                   │
  │                                                     │
  │ サービストークンの制限：                             │
  │                                                     │
  │ ✗ ブラウザ専用エンドポイントにはアクセス不可         │
  │ ✗ Docker内部ネットワークに限定                      │
  │ ✗ パブリックインターネットからは使用不可             │
  │ ✗ ロール階層なし — SERVICEロールはフラット           │
  └─────────────────────────────────────────────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### 設定

```bash
# 環境変数
VOLTA_SERVICE_TOKEN=your-very-long-random-secret-here
```

トークンの要件：
- 32文字以上
- 暗号学的にランダム
- バージョン管理にコミットしない
- 定期的にローテート

### AuthService.javaでの実装

```java
// AuthService.java — authenticate()
String auth = ctx.header("Authorization");
if (auth != null && auth.startsWith("Bearer ")) {
    String token = auth.substring("Bearer ".length()).trim();
    if (token.startsWith("volta-service:")) {
        String provided = token.substring("volta-service:".length());
        if (!config.serviceToken().isBlank()
            && config.serviceToken().equals(provided)) {
            return Optional.of(/* service AuthPrincipal */);
        }
        return Optional.empty();  // 無効なサービストークン → 401
    }
    // それ以外はJWT検証を試行...
}
```

### テナントコンテキスト要件

サービストークンはテナントスコープではないため、`X-Volta-Tenant-Id`ヘッダーを含める必要があります：

```yaml
# dsl/protocol.yaml
service_context:
  header: "Authorization: Bearer volta-service:{VOLTA_SERVICE_TOKEN}"
  requires_tenant_header: "X-Volta-Tenant-Id"
```

### 監査ログ

サービストークンリクエストは合成の`service@volta.local`アクターでログされます：

```
  ┌────────────────────────────────────────────────┐
  │ event_type:  MEMBER_ROLE_CHANGED               │
  │ actor_id:    00000000-0000-0000-0000-0000...   │
  │ actor_ip:    172.18.0.5 (Docker内部)           │
  │ detail:      { "via": "service-token" }        │
  └────────────────────────────────────────────────┘
```

---

## よくある間違いと攻撃

### 間違い1：短いまたは予測可能なトークン

`service-token-123`をサービストークンに使うのはブルートフォースを求めるようなもの。`openssl rand -base64 48`で適切なトークンを生成すること。

### 間違い2：トークンをバージョン管理にコミット

`docker-compose.yaml`やGitにコミットされた`.env`ファイル内のサービストークンは即座に漏洩します。シークレット管理（Docker secrets、Vault、クラウドプロバイダーのシークレットストア）を使用すること。

### 間違い3：トークンをローテートしない

3年間使い続けたサービストークンは、環境にアクセスしたことのあるすべての開発者に見られています。定期的にトークンをローテートし、古いものを無効化すること。

### 間違い4：サービストークンエンドポイントをインターネットに公開

サービストークンはDocker内部ネットワークからのみ機能すべきです。インターネット上の攻撃者がサービストークンを使えれば、すべてのテナントへの完全なAPIアクセスを得ます。

### 攻撃：ログ経由のトークン窃盗

サービストークンがアプリケーションログに現れれば（ログされたリクエストヘッダー等）、ログにアクセスした攻撃者がサービストークンアクセスを得ます。voltaはAuthorizationヘッダーの値を決してログしません。

---

## さらに学ぶために

- [jwt.md](jwt.md) -- ユーザーJWTとサービストークンの比較。
- [internal-api.md](internal-api.md) -- サービストークンが認証するAPI。
- [delegation.md](delegation.md) -- サービストークンによるマシン間委譲。
- [audit-log.md](audit-log.md) -- サービストークンアクションの監査方法。
- [header.md](header.md) -- Authorizationヘッダーの形式。
- [tenant.md](tenant.md) -- サービストークンに必要なテナントコンテキスト。
