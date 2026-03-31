# ディスカバリドキュメント（.well-known/openid-configuration）

[English / 英語](discovery-document.md)

---

## これは何？

OIDC ディスカバリドキュメントは、well-known URL（常に `/.well-known/openid-configuration`）で公開される JSON ファイルで、ID プロバイダについてクライアントが知るべきすべてを記述しています。認可エンドポイント、トークンエンドポイント、JWKS URI、サポートされるスコープ、サポートされるアルゴリズムなどが一覧化されています。URL をハードコードする代わりに、クライアントはディスカバリドキュメントを取得して自動設定できます。

---

## なぜ重要？

ディスカバリがなければ、ID プロバイダと連携するすべてのアプリケーションがエンドポイント URL をハードコードする必要があります。プロバイダが URL を変更すると、すべてのアプリが壊れます。ディスカバリドキュメントはクライアントがプログラム的に読める唯一の情報源を提供します。また、どのプロバイダでも動作する汎用 OIDC ライブラリの構築も可能にします -- 発行者 URL を渡すだけで、残りは自動で解決されます。

Google のようなプロバイダにとって、ディスカバリドキュメントはドキュメントの役割も果たします。Google が何をサポートしているか正確に確認できます。

---

## 簡単な例

`https://accounts.google.com/.well-known/openid-configuration` にある Google のディスカバリドキュメント：

```json
{
  "issuer": "https://accounts.google.com",
  "authorization_endpoint": "https://accounts.google.com/o/oauth2/v2/auth",
  "token_endpoint": "https://oauth2.googleapis.com/token",
  "jwks_uri": "https://www.googleapis.com/oauth2/v3/certs",
  "scopes_supported": ["openid", "email", "profile"],
  "response_types_supported": ["code", "token", "id_token"],
  "id_token_signing_alg_values_supported": ["RS256"],
  ...
}
```

クライアントはこれを1回読んで把握します：「認証を開始するには `authorization_endpoint` にリダイレクト。コードを交換するには `token_endpoint` に POST。id_token の署名を検証するには `jwks_uri` から鍵を取得。」

---

## volta-auth-proxy での使い方

volta はディスカバリに対して実用的なアプローチを取っています：

**コンシューマーとして**（Google に接続）：volta は現在、ランタイムでディスカバリドキュメントを取得する代わりに Google の OIDC エンドポイントをハードコードしています：

```java
private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
private static final URI GOOGLE_JWKS = URI.create("https://www.googleapis.com/oauth2/v3/certs");
```

これは Phase 1 での意図的な選択です -- Google のこれらの URL は何年も安定しており、ハードコードすることで起動時の HTTP 呼び出しを省いています。volta が他のプロバイダのサポートを追加する場合（Phase 3）、ディスカバリドキュメントの動的読み込みに切り替えます。

**プロバイダとして**（下流アプリ向け）：volta は `/.well-known/jwks.json` で独自の JWKS エンドポイントを公開しており、下流サービスがこれを使って volta 発行の JWT を検証します。同じ原則に従っています -- 下流アプリは volta の公開鍵をハードコードする必要がなく、well-known URL から取得するだけです。

関連: [scopes.md](scopes.md), [authorization-code-flow.md](authorization-code-flow.md)
