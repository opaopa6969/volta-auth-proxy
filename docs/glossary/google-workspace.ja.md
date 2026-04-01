# Google Workspace

[English version](google-workspace.md)

---

## これは何？

Google Workspace（旧G Suite、旧Google Apps for Business）は、Googleのクラウドベースの生産性・コラボレーションツール群です：Gmail、Google Drive、Googleドキュメント、Googleカレンダー、Google Meetなど。しかし生産性ツール群であるだけでなく、Google Workspaceは**IDプロバイダー**でもあります -- すべてのGoogle Workspaceユーザーは、インターネット上で「Googleでログイン」に使用できるGoogleアカウントを持っています。

Google Workspaceは、オフィス用品店が付いた企業キャンパスのバッジシステムのようなものです。バッジ（Googleアカウント）でキャンパスに入れ（Gmail、Drive、Calendar）、街中の店舗でも使えます（「Googleでログイン」をサポートする任意のウェブサイト）。キャンパスのセキュリティチーム（Google Workspace管理者）はバッジを取り消したり、訪問できる店舗を制限したり、「バッジには常にPINを使う」（MFA）などのルールを適用できます。

volta-auth-proxyにとって、Google Workspaceが重要なのは、Googleがvoltaの**Phase 1 OIDCプロバイダー**だからです。ユーザーが「Googleでログイン」をクリックすると、Googleアカウント（ビジネスユーザーの場合はGoogle Workspaceアカウント）を通じて認証しています。

---

## なぜ重要なのか？

Google Workspaceには30億以上のユーザーがいます（同じ認証システムを使用する個人のGmailアカウントを含む）。SaaS製品にとって、「Googleでログイン」は最もリクエストの多い認証方法であることが多いです：

1. **ほとんどのビジネスユーザーがすでにGoogleアカウントを持っている**（個人またはWorkspace）
2. **Googleが難しい部分を処理**：MFA、アカウント回復、デバイス管理、ブルートフォース保護
3. **ユーザーはGoogleのログインページを信頼**（毎日見ている）
4. **覚えるパスワードが1つ減る**

volta-auth-proxy向けには、Google Workspaceが提供するもの：

- **検証済みメールアドレス**：Workspace管理者がメールドメインを管理。`alice@acme.com`がGoogle Workspaceで認証された場合、acme.comがAliceのIDを確認したと信頼できる。
- **組織スコープのID**：個人のGmailアカウントと異なり、Workspaceアカウントは組織に属し、テナント解決が簡単。
- **管理者コントロール**：Workspace管理者はユーザーがサインインできるサードパーティアプリを制限でき、組織的なセキュリティの層を追加。

---

## どう動くのか？

### IDプロバイダーとしてのGoogle Workspace

```
  ユーザー（alice@acme.com、Google Workspaceアカウント）
       │
       │  「Googleでログイン」
       ▼
  volta-auth-proxy
       │
       │  OIDCリダイレクト
       ▼
  GoogleのOAuth/OIDCエンドポイント
       │
       │  ユーザーを認証（パスワード + MFA）
       │  id_tokenを発行（クレーム付き）：
       │    email: alice@acme.com
       │    email_verified: true
       │    hd: acme.com  ← ホストドメイン（Workspaceインジケーター）
       │    name: Alice Smith
       │    sub: 1234567890
       │
       ▼
  volta-auth-proxy
       │
       │  id_tokenを検証
       │  hdクレームをチェック → テナント「acme」を解決
       │  ユーザーを作成/更新
       │  voltaセッションを発行
       ▼
  ユーザーがログイン完了
```

### Google id_tokenの主要クレーム

| クレーム | 説明 | voltaでの使用 |
|---------|------|-------------|
| `email` | ユーザーのメールアドレス | ユーザー識別 |
| `email_verified` | Googleがこのメールを検証したか | `true`必須（voltaは未検証を拒否） |
| `hd` | ホストドメイン（Workspaceアカウントのみ） | テナント解決 |
| `sub` | Googleの一意のユーザー識別子 | 安定したユーザーID（メールは変更可能） |
| `name` | 表示名 | ユーザープロフィール |
| `picture` | プロフィール画像URL | ユーザープロフィール |

### `hd`（ホストドメイン）クレーム

`hd`クレームはマルチテナントSaaSにとって極めて重要です：

| アカウントタイプ | `hd`クレーム | 意味 |
|---------------|-----------|------|
| Google Workspace（`alice@acme.com`） | `"acme.com"` | ユーザーはacme.com組織に属する |
| 個人Gmail（`alice@gmail.com`） | 存在しない | 個人アカウント、組織なし |

voltaは`hd`クレームを使用して、ユーザーがどのテナントに属するかを自動的に解決します。`hd`が`acme.com`の場合、voltaは`acme.com`に関連付けられたテナントを検索します。

---

## volta-auth-proxy ではどう使われている？

Google Workspaceはvolta-auth-proxyの**Phase 1の主要IDプロバイダー**です。voltaはGoogleの[OIDC](oidc.md)エンドポイントを使用してユーザーを認証します。

### 認証フロー

1. ユーザーがvolta保護アプリケーションで「ログイン」をクリック
2. voltaがGoogleの認可エンドポイントにリダイレクト（[Google Cloud Console](google-cloud-console.md)で設定）
3. Googleがユーザーを認証（Workspaceログインページ、必要に応じてMFA）
4. Googleが認可コード付きでvoltaにリダイレクト
5. voltaがコードをid_tokenに交換（サーバー間）
6. voltaがid_tokenを検証しIDクレームを抽出
7. voltaが`hd`クレームまたはメールドメインからテナントを解決
8. voltaがユーザーを作成/更新しセッションを発行

### Google Workspaceによるテナント解決

```
  id_token.hd = "acme.com"
       │
       ▼
  SELECT * FROM tenants WHERE domain = 'acme.com'
       │
       ├── 見つかった → ユーザーはテナント「Acme Corp」に属する
       └── 見つからない → テナント自動プロビジョニングまたは拒否
```

### フリーメールドメインの処理

`alice@gmail.com`（`hd`クレームなし）でサインインした場合、voltaは[free-email-domains](free-email-domains.md)リストをチェックします。フリーメールドメインは自動テナント解決に使用できません -- ユーザーは特定のテナントに招待されるか、セルフ登録する必要があります。

### なぜGoogleが最初か？

voltaがPhase 1プロバイダーにGoogleを選んだ理由：

1. **最大のユーザーベース**：ほとんどのSaaSユーザーがGoogleアカウントを持っている
2. **優秀なOIDC実装**：GoogleのOIDCは十分にドキュメント化され標準準拠
3. **`hd`クレーム**：マルチテナント解決をエレガントにする
4. **無料**：IdPごとのユーザー単位のコストがない（[Okta](okta.md)と異なり）

Phase 2+では[Okta](okta.md)、[Active Directory](active-directory.md)、その他のプロバイダーを追加。

---

## よくある間違いと攻撃

### 間違い1：テナント解決にメールだけを信頼する

ユーザーが個人のGoogleアカウント（Workspaceアカウントではない）として`alice@acme.com`を持っている場合があります。`hd`クレームを確認しないと、acme.comテナントに誤って割り当てる可能性があります。Workspaceアカウントには常に`hd`を確認し、個人アカウントには招待ベースのアクセスにフォールバックしてください。

### 間違い2：email_verifiedをチェックしない

Googleアカウントは未検証のメールを持つことができます。`email_verified`チェックをスキップすると、誰かがあなたのメールでGoogleアカウントを作成し、テナントにアクセスできます。voltaは常に`email_verified = true`を要求します。

### 間違い3：安定した識別子としてメールを使用する

Workspace管理者はユーザーのメールアドレスを変更できます（例：結婚後の氏名変更）。voltaがメールのみでユーザーを識別すると、ユーザーはアカウントへのアクセスを失います。Googleの`sub`クレームを安定した識別子として使用し、メールは人間が読めるラベルとして使ってください。

### 間違い4：Workspace管理者のアプリ制限を処理しない

Workspace管理者がアプリをブロックすると、OIDCフロー中にGoogleがエラーを返します。voltaは明確なエラーメッセージを表示すべきです：「あなたの組織の管理者がこのアプリケーションを承認していません。IT部門に連絡してください。」

### 攻撃：Workspaceドメインの乗っ取り

acme.comがGoogle Workspaceサブスクリプションを失効させ、他の誰かがacme.comを新しいWorkspaceとして登録した場合、`alice@acme.com`を作成してacmeテナントにアクセスできる可能性があります。メールに加えて`sub`（安定、再利用不可）をユーザーマッチングに使用して軽減してください。

---

## さらに学ぶ

- [Google Workspace管理者ドキュメント](https://support.google.com/a/) -- Workspace設定の管理。
- [Google IDドキュメント](https://developers.google.com/identity/) -- OAuth/OIDC統合。
- [google-cloud-console.md](google-cloud-console.md) -- OAuth認証情報を作成する場所。
- [oidc.md](oidc.md) -- voltaがGoogleと使用するプロトコル。
- [free-email-domains.md](free-email-domains.md) -- 個人メールサインアップの処理。
- [idp.md](idp.md) -- IDプロバイダーとは何か。
- [okta.md](okta.md) -- エンタープライズIdPの代替手段（Phase 3）。
- [active-directory.md](active-directory.md) -- MicrosoftのエンタープライズIdP（Phase 3）。
