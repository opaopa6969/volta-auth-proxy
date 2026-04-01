# Google Cloud Console

[English version](google-cloud-console.md)

---

## これは何？

Google Cloud Consoleは、Google Cloud Platform（GCP）サービスのWebベースの管理ダッシュボードです。開発者がプロジェクトを作成し、APIを管理し、課金を設定し、そしてvolta-auth-proxyにとって最も重要な「Googleでログイン」に必要なOAuth 2.0認証情報を作成する場所です。

Google Cloud Consoleは、公式書類を取得するために行く役所のようなものです。他の国に旅行する（ユーザーを認証する）にはパスポート（OAuthクライアント認証情報）が必要です。役所（Google Cloud Console）がパスポートを発行し、ルール（認可されたリダイレクトURI）を設定し、ルールを破った場合は取り消すことができます。

Google Cloud Console自体は認証サービスではありません -- Googleの認証サービスを設定する管理パネルです。実際の認証はGoogleのOAuth 2.0 / [OIDC](oidc.md)エンドポイントを通じて行われます。

---

## なぜ重要なのか？

「Googleでログイン」を使用するすべてのアプリケーションは、Google Cloud Consoleで認証情報を作成する必要があります。これらの認証情報がなければ、[OIDC](oidc.md)フローを開始できません。これはvolta-auth-proxyの必須セットアップステップです。

ここで作成する認証情報には以下が含まれます：

- **Client ID**：アプリケーションの公開識別子（ブラウザのリダイレクトで公開しても安全）
- **Client Secret**：サーバーが認可コードをトークンに交換するために使用する秘密鍵（ブラウザに絶対に到達させてはならない）

この2つの値がvolta-auth-proxyに必要なGoogle固有の設定のすべてです。`.env`ファイルに入れ、voltaはこれらを使ってGoogleのOIDCエンドポイントと通信します。

---

## どう動くのか？

### volta用のOAuth認証情報の作成

Google Cloud Consoleでのステップバイステップのプロセス：

#### ステップ1：プロジェクトの作成または選択

```
Google Cloud Console → プロジェクトを選択 → 新しいプロジェクト
  プロジェクト名：volta-auth-proxy
  組織：（あなたの組織または「組織なし」）
```

GCPプロジェクトはリソースのコンテナです。すべてのOAuth認証情報はプロジェクトに属します。

#### ステップ2：必要なAPIの有効化

**APIとサービス > ライブラリ**に移動して以下を有効化：

| API | 理由 |
|-----|------|
| **Google People API** | プロフィール情報（名前、写真）をリクエストする場合に必要 |

注意：基本的なOIDCフロー（メール検証）は追加のAPIを有効にしなくても動作します。People APIは追加のプロフィールデータにのみ必要です。

#### ステップ3：OAuth同意画面の設定

**APIとサービス > OAuth同意画面**に移動：

| 設定 | 開発用の値 | 本番用の値 |
|------|-----------|-----------|
| ユーザータイプ | 外部 | 外部 |
| アプリ名 | volta-auth-proxy (dev) | あなたのSaaS名 |
| サポートメール | your@email.com | support@yourdomain.com |
| 承認済みドメイン | yourdomain.com | yourdomain.com |
| スコープ | openid, email, profile | openid, email, profile |

同意画面は、ユーザーが「Googleでログイン」をクリックした時に表示されるものです -- 「volta-auth-proxyがあなたのメールアドレスにアクセスしようとしています。許可しますか？」

#### ステップ4：OAuth 2.0認証情報の作成

**APIとサービス > 認証情報 > 認証情報を作成 > OAuthクライアントID**に移動：

| 設定 | 値 |
|------|-----|
| アプリケーションの種類 | ウェブアプリケーション |
| 名前 | volta-auth-proxy |
| 承認済みのリダイレクトURI | `http://localhost:7070/callback`（開発） |
| | `https://auth.yourdomain.com/callback`（本番） |

作成後、以下を取得：

```
Client ID:     xxxxxxxxxxxx.apps.googleusercontent.com
Client Secret: GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxx
```

#### ステップ5：volta .envに追加

```env
GOOGLE_CLIENT_ID=xxxxxxxxxxxx.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-xxxxxxxxxxxxxxxxxxxxxxxx
```

### 公開ステータス

| ステータス | 意味 |
|----------|------|
| **テスト** | 明示的にテストユーザーとして追加したユーザーのみログイン可能。100人まで。 |
| **本番** | 任意のGoogleアカウントでログイン可能。機密スコープにはGoogleの検証が必要。 |

開発には「テスト」で十分です。本番では、`openid`、`email`、`profile`を超えるスコープを使用する場合、検証を提出する必要があります。

### OAuth同意画面 vs ログインUI

重要な区別：

```
  1. ユーザーがアプリの「ログイン」をクリック
  2. voltaがGoogleにリダイレクト
  3. Googleがアカウント選択画面を表示（GoogleのUI -- 常に）
  4. Googleが同意画面を表示（GoogleのUI -- 初回のみ）
  5. Googleが認可コード付きでvoltaにリダイレクト
  6. voltaがダッシュボードを表示（voltaのUI -- jteテンプレート）
```

同意画面はCloud Consoleで設定するGoogleのUIです。レイアウトは制御できません -- アプリ名、ロゴ、プライバシーポリシーURLのみ。voltaのjteテンプレートはGoogleとのやり取りの前後すべてを制御します。

---

## volta-auth-proxy ではどう使われている？

Google Cloud Consoleは、volta-auth-proxyに必要なOAuth認証情報を作成する場所です。一度きりのセットアップステップであり、継続的な依存関係ではありません。

### voltaがGoogle Cloud Consoleから必要とするもの

| アイテム | 格納場所 | 触れる頻度 |
|---------|---------|-----------|
| Client ID | `.env`ファイル | 1回（ローテーション時以外） |
| Client Secret | `.env`ファイル | 1回（ローテーション時以外） |
| リダイレクトURI | 認証情報設定 | 環境ごとに1回（dev、staging、prod） |
| 同意画面 | OAuth同意画面 | 1回（リブランディング時以外） |

### 認証情報のローテーション

ベストプラクティスとして、クライアントシークレットは定期的にローテーションします：

1. Google Cloud Consoleで新しい認証情報を作成
2. voltaの`.env`ファイルを新しいシークレットで更新
3. voltaを再起動
4. Google Cloud Consoleで古い認証情報を削除

アクティブなセッションは影響を受けません。voltaは初回ログイン後、Googleのトークンではなく独自のセッションCookieを使用するためです。

### 複数環境

環境ごとに個別のOAuth認証情報を作成してください：

| 環境 | リダイレクトURI | 認証情報 |
|------|---------------|---------|
| ローカル開発 | `http://localhost:7070/callback` | volta-dev |
| ステージング | `https://auth-staging.example.com/callback` | volta-staging |
| 本番 | `https://auth.example.com/callback` | volta-prod |

---

## よくある間違いと攻撃

### 間違い1：devとprodで同じ認証情報を使用する

開発用認証情報が漏洩した場合（gitコミット、ログ、請負業者への共有）、攻撃者がアプリケーションになりすますことができます。環境ごとに別の認証情報を使用してください。

### 間違い2：リダイレクトURIを広く設定しすぎる

GoogleはリダイレクトURIにワイルドカード的なパターンを許可しています。`https://*.example.com/callback`を追加するのは危険です -- 攻撃者が`https://evil.example.com/callback`を作成すると、認可コードを傍受できます。できるだけ具体的にしてください。

### 間違い3：同意画面を本番に設定しない

同意画面が「テスト」モードのままだと、ホワイトリストに登録されたテストユーザーしかログインできません。実際の顧客にはエラーが表示されます：「このアプリはまだ検証されていません。」リリース前に本番に提出してください。

### 間違い4：クライアントシークレットをgitにコミットする

クライアントシークレットはソース管理に入れてはいけません。環境変数、`.env`ファイル（gitignored）、またはシークレットマネージャーを使用してください。シークレットが漏洩した場合、Google Cloud Consoleですぐにローテーションしてください。

### 攻撃：OAuthアプリケーションのなりすまし

攻撃者が類似のアプリ名と同意画面で独自のGoogle Cloudプロジェクトを作成します。偽アプリを通じて「Googleでログイン」するようユーザーをフィッシングし、認可コードを捕捉します。ユーザーはアクセスを許可する前に、同意画面に正しいアプリ名が表示されていることを確認すべきです。

---

## さらに学ぶ

- [Google Cloud Console](https://console.cloud.google.com/) -- コンソール自体。
- [Google OAuth 2.0セットアップガイド](https://developers.google.com/identity/protocols/oauth2) -- 公式ドキュメント。
- [Google OIDCドキュメント](https://developers.google.com/identity/openid-connect/openid-connect) -- GoogleのOIDC実装。
- [oidc.md](oidc.md) -- これらの認証情報を使用するプロトコル。
- [oauth2.md](oauth2.md) -- OIDCの基盤となるフレームワーク。
- [google-workspace.md](google-workspace.md) -- IDプロバイダーとしてのGoogle。
- [redirect-uri.md](redirect-uri.md) -- リダイレクトURIがセキュリティに重要な理由。
