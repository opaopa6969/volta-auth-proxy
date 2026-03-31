# Keycloak

[English version](keycloak.md)

---

## これは何？

Keycloakは、Red Hatが開発したオープンソースのID・アクセス管理（IAM）システムで、アプリケーションのログイン、ユーザー管理、アクセス制御を処理します。

認証のスイスアーミーナイフのようなものです。ナイフ、ドライバー、缶切り、コルク抜き、ハサミ、そして頼んでもいない15個の他のツールが入っています。認証に関することはほぼ何でもできます。でも重いし、必要なツールが見つけにくいし、シンプルなナイフが欲しかっただけの場合もあります。

---

## なぜ重要なのか？

Keycloakは[Auth0](auth0.ja.md)のようなクラウドサービスに対する最も有名なオープンソースの代替品です。[MAU](mau.ja.md)課金の代わりに認証を[セルフホスト](self-hosting.ja.md)したいチームは、まずKeycloakを検討します。Keycloakの長所と短所を理解すると、なぜvolta-auth-proxyが異なるアプローチで構築されたかが分かります。

---

## Keycloakがうまくやっていること

| 強み | 詳細 |
|------|------|
| **機能が完全** | OIDC、SAML、LDAP、ソーシャルログイン、MFA、ユーザーフェデレーション、管理コンソール、アカウント管理 -- すべてある。 |
| **エンタープライズで実証済み** | 大規模組織で使用。何年もの本番運用で実証済み。 |
| **オープンソース** | 無料で使え、無料で改変可。Apache 2.0ライセンス。 |
| **標準準拠** | 完全なOIDCとSAML 2.0サポート。標準準拠のあらゆるアプリのIdPとして機能。 |
| **活発なコミュニティ** | 大きなコミュニティ、定期リリース、充実したドキュメント。 |
| **Red Hatの支援** | Red Hat SSO（商用版）を通じたプロフェッショナルサポートが利用可能。 |

「すべての標準を実装した完全な機能のIDサーバーが必要」という要件なら、Keycloakに勝るものはなかなかありません。

---

## 問題点

### 1. リソースが重い

KeycloakはQuarkusフレームワーク（以前はWildFly）上に構築されており、かなりのリソースを必要とします：

```
  Keycloak:                          volta-auth-proxy:
  ┌────────────────────┐             ┌────────────────────┐
  │  RAM: ~512MB+      │             │  RAM: ~30MB        │
  │  起動: ~30秒       │             │  起動: ~200ms      │
  │  Dockerイメージ: 大 │             │  Dockerイメージ: 小 │
  └────────────────────┘             └────────────────────┘
```

「Googleログイン + マルチテナンシー」だけが必要な小さなSaaSにとって、Keycloakは花を植えるのにブルドーザーを使うようなものです。

### 2. 設定地獄

Keycloakには「レルム」「クライアント」「ロール」「フロー」「オーセンティケーター」「マッパー」などに分類された何百もの設定オプションがあります。典型的なレルムエクスポート（`realm.json`）は500行以上になることがあります：

```json
{
  "realm": "my-saas",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "loginWithEmailAllowed": true,
  "duplicateEmailsAllowed": false,
  "resetPasswordAllowed": false,
  "editUsernameAllowed": false,
  "bruteForceProtected": true,
  "permanentLockout": false,
  "maxFailureWaitSeconds": 900,
  "minimumQuickLoginWaitSeconds": 60,
  "waitIncrementSeconds": 60,
  "quickLoginCheckMilliSeconds": 1000,
  "maxDeltaTimeSeconds": 43200,
  "failureFactor": 30,
  "defaultRoles": ["offline_access", "uma_authorization"],
  "requiredCredentials": ["password"],
  "otpPolicyType": "totp",
  "otpPolicyAlgorithm": "HmacSHA1",
  // ... さらに何百行も... ...
}
```

voltaの全設定と比較してください：

```yaml
# volta-config.yaml -- これだけ
apps:
  - id: app-wiki
    url: https://wiki.example.com
    allowed_roles: [MEMBER, ADMIN, OWNER]
```

この問題の詳細は[config-hell.ja.md](config-hell.ja.md)を参照。

### 3. テーマカスタマイズの苦痛（FreeMarker）

Keycloakのログインページには FreeMarkerテンプレートが使われています。FreeMarkerは2002年のJavaテンプレートエンジンです。Keycloakのログインページをカスタマイズするには：

1. Keycloakのディレクトリ構造から正しいテーマフォルダを見つける
2. FreeMarkerの構文を学ぶ（直感的ではない）
3. ベーステーマを継承しつつ特定のテンプレートファイルをオーバーライド
4. KeycloakのCSSクラス構造に対応する
5. 変更のたびにリビルド/再起動

多くの開発者がこのプロセスを「苦痛」「悪夢」と表現しています。Keycloakifyというオープンソースプロジェクトが存在する目的は、Keycloakのテーマをもう少しマシにすることです。

voltaは完全にコントロール可能な[jte](jte.ja.md)テンプレートを使います。HTMLファイルを編集してリフレッシュすれば変更が見えます。

### 4. マルチテナンシーの制限

Keycloakの「レルム」の概念がマルチテナンシーモデルです。各テナントが独立したレルムを持ちます。しかし：

- 新しいレルムの作成は完全に分離された環境（ユーザー、クライアント、ロール、すべて）の作成を意味する
- レルム間の操作は限定的
- レルム管理は管理者操作であり、セルフサービスではない
- 100以上のレルムでKeycloakの管理コンソールが遅くなる

voltaのマルチテナンシーはコアデータモデルに組み込まれています。ユーザー、テナント、ロールはすべて同じPostgreSQLデータベースに適切な外部キーで格納されます。テナント作成は管理操作ではなくAPI呼び出しです。

---

## voltaがKeycloakを使わないことを選んだ理由

| 懸念事項 | Keycloak | volta |
|---------|----------|-------|
| メモリ | ~512MB+ | ~30MB |
| 起動 | ~30秒 | ~200ms |
| 設定 | 何百もの設定 | .env + YAMLファイル1つ |
| ログインUI | FreeMarkerテンプレート | [jte](jte.ja.md)テンプレート（型安全、モダン） |
| マルチテナンシー | レルムベース（重い） | ネイティブ（軽量） |
| 依存関係 | Keycloakサーバー + Postgres | Postgresだけ |
| 学習コスト | 急（生産的になるまで数週間） | 緩やか（理解するまで数時間） |

voltaは「Keycloak lite」ではありません。voltaはまったく異なるアプローチです。汎用IDサーバーではなく、一つのユースケースに特化して構築されています：ForwardAuthを使ったマルチテナントSaaS認証。できることは少ないですが、やることはシンプルかつ効率的にやります。

---

## Keycloakが適している場合

- 完全なSAML 2.0 IdPサポートがエンタープライズSSOに必要
- LDAP/Active Directoryフェデレーションが必要
- Red Hat / JBossエコシステムにいてベンダーサポートが欲しい
- サードパーティアプリケーション向けの完全なIDプロバイダである必要がある
- Keycloakを管理する専任の運用スタッフがいる

---

## voltaがより適している場合

- マルチテナントSaaSを構築しており、軽量な認証が欲しい
- 実際に簡単にカスタマイズできるログインページが欲しい
- 500項目の設定システムを学びたくない
- 30秒ではなく200msで起動するものが欲しい
- 小さなフットプリント（~512MBではなく~30MB）が必要

---

## さらに学ぶために

- [config-hell.ja.md](config-hell.ja.md) -- voltaが設定の複雑さを避ける理由。
- [iam.ja.md](iam.ja.md) -- Keycloakが位置するIAMの広い世界。
- [auth0.ja.md](auth0.ja.md) -- Keycloakのクラウドホスト型代替。
- [jte.ja.md](jte.ja.md) -- voltaがFreeMarkerの代わりに使うテンプレートエンジン。
- [self-hosting.ja.md](self-hosting.ja.md) -- セルフホスティングのトレードオフ（KeycloakもvoltaもセルフホストOK）。
