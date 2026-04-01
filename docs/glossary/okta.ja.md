# Okta

[English version](okta.md)

---

## これは何？

Oktaは、シングルサインオン（SSO）、多要素認証（MFA）、ユーザーライフサイクル管理をクラウドサービスとして提供するエンタープライズIDプラットフォームです。世界最大級の専業IDプロバイダーの一つで、主要な企業、政府、大学を含む18,000以上の組織に利用されています。

外部のセキュリティ会社が運営する企業IDバッジシステムのようなものです。各オフィスビルが独自のバッジを管理する代わりに、単一の会社（Okta）がすべてのビルのバッジを発行・管理します。従業員が入社すると1枚のバッジを受け取ります。退社すると1枚のバッジが無効になります。その1枚のバッジで、認可されたすべてのドアを開けられます。

Oktaは[SAML](sso.md)と[OIDC](oidc.md)の両プロトコルをサポートしており、事実上すべてのモダンなアプリケーションと互換性があります。エンタープライズの世界で「200のアプリケーションにまたがる10,000人の従業員をどう管理するか」のデフォルトの答えがOktaです。

---

## なぜ重要なのか？

volta-auth-proxyの文脈でOktaが重要な理由は2つあります：

1. **上流IdPとして**：Phase 3では、voltaはIDプロバイダーとしてOktaと統合できます。すでにOktaを使っている企業顧客は、採用するすべてのSaaSツールにOktaを接続できることを期待しています。voltaがOktaと連携できなければ、そのような顧客は採用しません。

2. **競合の参照点として**：OktaはIDへの「作らずに買う」アプローチを代表しています。Oktaを理解することで、voltaがなぜ存在するかを説明できます -- Oktaの価格（$2-15/ユーザー/月）、クラウド依存、ブラックボックスの性質が受け入れられない組織向けです。

### エンタープライズIdPのランドスケープ

| プロバイダー | タイプ | 強み | 価格帯 |
|------------|-------|------|--------|
| Okta | クラウドSaaS | エンタープライズSSO、ライフサイクル管理 | $2-15/ユーザー/月 |
| [Auth0](auth0.md) | クラウドSaaS（Okta子会社） | 開発者フレンドリー、B2C/B2B | $23-240/月 + ユーザー単位 |
| [Active Directory](active-directory.md) | オンプレミス / Azure AD | Microsoftエコシステム | Microsoft 365に含む |
| [Google Workspace](google-workspace.md) | クラウドSaaS | Googleエコシステム | Workspaceに含む |
| [Keycloak](keycloak.md) | セルフホスト | 無料、オープンソース | 無料（インフラ費用のみ） |

---

## どう動くのか？

### SSOハブとしてのOkta

```
  従業員
     │
     │  1. 任意のアプリ（Slack、Salesforce、あなたのSaaS）にアクセス
     ▼
  ┌──────────────┐
  │  アプリケーション│
  │              │  2. Oktaにリダイレクト
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │    Okta      │
  │              │  3. ユーザーが認証（パスワード + MFA）
  │              │  4. OktaがSAMLアサーションまたはOIDCトークンを発行
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │  アプリケーション│
  │              │  5. IDを受信、アクセスを許可
  └──────────────┘
```

### 主要機能

| 機能 | 説明 |
|------|------|
| **Universal Directory** | AD、LDAP、HRシステムと同期する中央ユーザーストア |
| **SSO** | すべてのアプリに1回のログイン（SAML、OIDC、WS-Fed） |
| **Adaptive MFA** | リスクベースのMFA（デバイス、場所、行動パターン） |
| **Lifecycle Management** | アプリ間のユーザー自動プロビジョニング/デプロビジョニング（SCIM） |
| **API Access Management** | API認可のためのOAuth2サーバー |
| **Workflows** | IDイベントのノーコード自動化 |

### SCIMプロビジョニング

SCIM（System for Cross-domain Identity Management）は、Oktaがアプリケーションのユーザーアカウントを自動的に作成・削除する仕組みです：

```
  HRシステム：「Aliceが入社」
       │
       ▼
  Okta：ユーザーAliceを作成
       │
       ├──► SCIM POST /Users to Slack → AliceのSlackアカウント作成
       ├──► SCIM POST /Users to Salesforce → AliceのSalesforceアカウント作成
       └──► SCIM POST /Users to volta → Aliceのvoltaアカウント作成
```

Aliceが退社した場合：
```
  HRシステム：「Aliceが退職」
       │
       ▼
  Okta：ユーザーAliceを無効化
       │
       ├──► SCIM PATCH to Slack → アカウント無効化
       ├──► SCIM PATCH to Salesforce → アカウント無効化
       └──► SCIM PATCH to volta → アカウント無効化
```

---

## volta-auth-proxy ではどう使われている？

volta-auth-proxyは**Phase 3**で上流IDプロバイダーとしてOktaとの統合を計画しています。これにより、Oktaを使っている企業顧客がOktaのIDを受け入れるようvoltaを設定できます。

### Phase 3の統合計画

```
  従業員 ──► volta-auth-proxy ──► Okta（OIDC/SAML IdPとして）
                  │                    │
                  │                    └── ユーザーを認証
                  │
                  ├── ID（メール、グループ、ロール）を受信
                  ├── voltaテナント + ユーザーにマッピング
                  ├── voltaセッションを作成
                  └── volta JWTを発行
```

### 企業顧客にとってなぜ重要か

企業顧客は通常「すべての認証はOktaを通す」と義務付けています。別途ログインが必要なSaaSツールは採用しません。Oktaとの統合により：

1. 従業員は既存のOkta認証情報を使用 -- 新しいパスワード不要
2. IT管理者はOktaダッシュボードからアクセスを管理 -- volta専用の管理不要
3. 従業員退社時、Oktaの無効化がSCIM経由でvoltaに伝播
4. MFAはOktaが処理（セキュリティチームがすでに信頼しているもの）

### なぜvoltaはOktaをすべてに使わないのか

Oktaはユーザーごとの課金がある外部依存です。voltaの哲学はセルフホストかつ無料です。voltaはOktaをIdPソース（Googleと同様）として使用しますが、セッション管理、テナント解決、認可ロジックはvolta内部に留まります。

---

## よくある間違いと攻撃

### 間違い1：Oktaトークンを適切に検証しない

OktaがSAMLアサーションやOIDCトークンを発行した場合、アプリケーションは署名、発行者、オーディエンス、有効期限を検証する必要があります。これらのチェックのいずれかをスキップすると、トークンの偽造が可能になります。

### 間違い2：Okta APIの権限を広く付与しすぎる

OktaのAPIトークンにはスコープがあります。`okta.users.read`だけが必要な時に`okta.users.manage`を付与すると、トークンが侵害された場合にユーザーアカウントを変更できてしまいます。

### 間違い3：SCIMデプロビジョニングを実装しない

Okta SSOを統合してもSCIMデプロビジョニングを実装しないと、退職した従業員がアクセスを保持します。Oktaログインは無効になりますが、キャッシュされたセッションやローカルパスワードがあれば、まだアクセスできる可能性があります。

### 間違い4：グループベースのアクセスを無視する

Oktaグループは、どのユーザーがどのアプリケーションにアクセスできるかを制御する標準的な方法です。特定のグループではなく「すべてのOktaユーザー」を許可すると、請負業者、インターン、その他意図しないユーザーにアクセスを付与する可能性があります。

### 攻撃：Oktaセッションハイジャック（2022年Lapsus$事件）

2022年、Lapsus$グループがOktaのサポートエンジニアのマシンを侵害し、そのアクセスを使って顧客のパスワードをリセットしました。これは、IDプロバイダーでさえ侵害される可能性があることを示しました。深層防御 -- セキュリティをOktaだけに頼らないこと -- が不可欠です。

---

## さらに学ぶ

- [Okta開発者ドキュメント](https://developer.okta.com/docs/) -- 公式リファレンス。
- [Okta OIDCガイド](https://developer.okta.com/docs/concepts/oauth-openid/) -- OktaのOIDC実装。
- [Okta SAMLガイド](https://developer.okta.com/docs/concepts/saml/) -- OktaのSAML実装。
- [oidc.md](oidc.md) -- voltaがOktaと使用するプロトコル。
- [sso.md](sso.md) -- シングルサインオンの概念。
- [idp.md](idp.md) -- IDプロバイダーとは何か。
- [active-directory.md](active-directory.md) -- MicrosoftのエンタープライズIdP。
- [auth0.md](auth0.md) -- Oktaの開発者向け子会社。
