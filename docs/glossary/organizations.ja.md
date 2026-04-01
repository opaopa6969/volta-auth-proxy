# Organizations（Auth0/Clerkの機能）

[English version](organizations.md)

---

## これは何？

「Organizations」は、Auth0やClerkなどのクラウド認証プロバイダーが提供する機能で、SaaSアプリケーションがB2B顧客をモデル化できるようにします。SaaS製品が企業に販売する場合（個人消費者ではなく）、各企業が「organization」です -- 独自のメンバー、ロール、設定を持ちます。

コワーキングスペースのようなものです。ビル（Auth0/Clerk）がインフラを提供します：ドア、デスク、WiFi、会議室。スペースを借りる各企業は独自の「organization」を持ちます -- 独自のメンバーリストと、誰が何にアクセスできるかの独自のルールを持つフロアやセクション。ビルがすべてを管理し、利用人数に応じて賃料を支払います。

---

## なぜ重要なのか？

B2B SaaSは本質的にマルチテナントです。Slackにはワークスペースがあり、Notionにはチームスペースがあり、GitHubにはorganizationsがあります。すべてのB2B SaaSは、ユーザーを顧客エンティティにグループ化し、別々のデータ、ロール、権限を持たせる方法が必要です。

Auth0とClerkはこのパターンを認識し、「Organizations」をファーストクラスの機能として構築しました。それらがどう機能するか -- そしてどこで不足するか -- を理解すると、volta-auth-proxyが外部プロバイダーの機能に頼らず、データモデルに直接マルチテナンシーを組み込んだ理由が分かります。

---

## Auth0 Organizationsの仕組み

Auth0は2021年にB2Bプラットフォームの一部としてOrganizationsを導入しました：

```
  Auth0 Organizationsモデル：
  ┌──────────────────────────────────────────────────┐
  │ Auth0テナント（あなたのAuth0アカウント）           │
  │                                                    │
  │  ┌─────────────┐  ┌─────────────┐                │
  │  │ Org: Acme    │  │ Org: Beta    │               │
  │  │              │  │              │                │
  │  │ メンバー:    │  │ メンバー:    │                │
  │  │  alice (admin)│  │  alice (viewer)│              │
  │  │  bob (member) │  │  charlie (admin)│             │
  │  │              │  │              │                │
  │  │ 接続:        │  │ 接続:        │                │
  │  │  Google SSO  │  │  Okta SAML  │                │
  │  │              │  │              │                │
  │  │ ブランド:    │  │ ブランド:    │                │
  │  │  Acmeロゴ    │  │  Betaロゴ    │                │
  │  └─────────────┘  └─────────────┘                │
  └──────────────────────────────────────────────────┘
```

主な機能：
- ユーザーは複数のorganizationのメンバーになれる
- 各organizationが独自のIDプロバイダーを持てる（例：AcmeはGoogle、BetaはOkta）
- ログインページにorganization固有のブランディング
- ロール割り当てはorganizationごと
- JWTトークンにOrganization IDが含まれる

---

## Clerk Organizationsの仕組み

Clerkは、よりデベロッパーフレンドリーなAPIで同様のアプローチを取ります：

```javascript
// Clerk: organizationの作成
const org = await clerkClient.organizations.createOrganization({
  name: "Acme Corp",
  createdBy: userId
});

// Clerk: メンバーの招待
await clerkClient.organizations.createOrganizationInvitation({
  organizationId: org.id,
  emailAddress: "bob@acme.com",
  role: "admin"
});
```

Clerkは`<OrganizationSwitcher />`のようなReactコンポーネントを提供し、organization間の切り替えUIを処理します。

---

## プロバイダー管理のorganizationsの問題点

### 1. コスト

Auth0 Organizationsは有料機能で、B2Bプランで利用可能です：

```
  Auth0料金（概算）：
  無料枠:      7,500 MAU、Organizations機能なし
  Essentials:  Organizations利用可、月額~$35から
  Professional: フルOrganizations、月額~$240から
  Enterprise:  カスタム料金

  100k MAU時: 月額~$2,400
  Organizations機能単体でも大幅なコスト増。
```

Clerkも同様の従量課金でユーザー数に応じてスケールします。

### 2. ベンダーロックイン

マルチテナンシーモデルがプロバイダーのAPIに紐づきます：

```javascript
// アプリ全体に織り込まれたAuth0固有のコード：
import { useOrganization } from '@auth0/auth0-react';

function Dashboard() {
  const { organization } = useOrganization();
  // テナントコンテキストが必要なすべてのコンポーネントが
  // Auth0のSDKをインポート
}
```

Auth0から別のプロバイダーに切り替えるということは、organizationsを参照するすべてのコンポーネントを書き直すことを意味します。[vendor-lock-in.ja.md](vendor-lock-in.ja.md)を参照。

### 3. 限定的なコントロール

organizationsの内部動作を変更できません。Auth0のorganizationモデルがビジネスモデルと正確に一致しない場合、選択肢は2つ：回避策を講じるか、我慢するか。よくある制限：

- organization階層（親子組織）がサポートされない場合がある
- organizationごとのカスタムメタデータが限定的
- organizationレベルのフィーチャーフラグに外部ツールが必要
- organizationごとの課金にカスタム統合が必要

### 4. データが他所にある

テナントデータ -- 誰がどのorganizationに属し、どのロールで、いつ参加したか -- はAuth0のクラウドにあります。SQLでクエリできません。API呼び出しなしにビジネスデータとJOINできません。`SELECT * FROM`できる`tenants`テーブルがありません。

---

## voltaとの比較

voltaの[テナント](tenant.ja.md)概念は、Auth0/Clerk Organizationsに相当するものですが、データベースに組み込まれています：

| 観点 | Auth0 Organizations | Clerk Organizations | volta Tenants |
|------|-------------------|-------------------|--------------|
| データの場所 | Auth0クラウド | Clerkクラウド | あなたのPostgreSQL |
| コスト | MAU課金 | MAU課金 | $0 |
| ユーザー複数所属 | はい | はい | はい |
| 組織ごとのロール | はい | はい | はい（OWNER/ADMIN/MEMBER/VIEWER） |
| 組織ごとのIdP | はい | はい | 共有（Phase 1）、テナントごと（Phase 3） |
| セルフサービス作成 | API経由 | API経由 | API + セルフサービスサインアップ |
| 組織ごとのブランド | はい（限定的） | はい（限定的） | テンプレートカスタマイズで対応 |
| SQLクエリ可能 | いいえ | いいえ | はい（`SELECT * FROM tenants`） |
| 招待システム | はい | はい | はい（組み込み） |
| 組織切り替え | SDKコンポーネント | `<OrgSwitcher />` | API呼び出し + セッション更新 |

### voltaの実装

```sql
-- voltaのテナントモデルは単なるデータベーステーブル：

-- テナント
SELECT id, name, slug FROM tenants;
-- acme-uuid | Acme Corp | acme
-- beta-uuid | Beta Inc  | beta

-- メンバーシップ（ユーザー + テナント + ロール）
SELECT user_id, tenant_id, role FROM memberships;
-- alice-uuid | acme-uuid | ADMIN
-- alice-uuid | beta-uuid | VIEWER
-- bob-uuid   | acme-uuid | MEMBER
```

```java
// volta: テナントコンテキストはSDKではなくヘッダーから
app.get("/api/data", ctx -> {
    String tenantId = ctx.header("X-Volta-Tenant-Id");
    String role = ctx.header("X-Volta-Roles");
    // SDKインポートなし。ベンダー依存なし。HTTPヘッダーだけ。
});
```

違いは哲学的です：Auth0/ClerkはorganizationsをA**マネージドサービス機能**として提供します。voltaはテナントを**あなたが所有するデータベースの行**として提供します。

---

## Auth0/Clerk Organizationsが適している場合

- マルチテナンシーのインフラ管理をゼロにしたい
- MAU課金の予算がある
- organizationモデルがシンプル（フラット、階層なし）
- 組織切り替えの既製UIコンポーネントが欲しい
- ベンダーロックインのトレードオフを受け入れられる

---

## voltaがより適している場合

- テナントデータを自分のデータベースに置きたい
- テナントデータとビジネスデータをSQLでJOINしたい
- 予測可能なコスト（認証レイヤー$0）が欲しい
- テナントライフサイクルの完全なコントロールが必要
- すべてのコンポーネントでSDK依存を避けたい

---

## さらに学ぶために

- [tenant.ja.md](tenant.ja.md) -- voltaのテナント概念。
- [multi-tenant.ja.md](multi-tenant.ja.md) -- voltaのマルチテナンシーアーキテクチャ。
- [auth0.ja.md](auth0.ja.md) -- Auth0の概要とトレードオフ。
- [vendor-lock-in.ja.md](vendor-lock-in.ja.md) -- マネージド機能がロックインを生む理由。
- [invitation-flow.ja.md](invitation-flow.ja.md) -- voltaの組織招待システム。
