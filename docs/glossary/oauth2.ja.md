# OAuth 2.0

[English version](oauth2.md)

---

## これは何？

OAuth 2.0は認可のための標準プロトコルです。ユーザーがパスワードを共有することなく、あるアプリケーションがユーザーの代わりにリソースにアクセスできるようにします。ウェブサイトが「このアプリにGoogleドライブへのアクセスを許可しますか？」と聞くとき、それがOAuth 2.0です。

車のバレーキーのようなものです。バレーキーでは駐車係が車を運転できますが（限定的なアクセス）、トランクやグローブボックスは開けられません（フルアクセス）。マスターキー（パスワード）を渡すことなく、特定の限定的な許可を与えているのです。

重要な区別：OAuth 2.0は**認可**（「何を許可されているか？」）を扱い、**認証**（「あなたは誰か？」）は扱いません。認証はOAuth 2.0の上に構築されたOpenID Connect（OIDC）が扱います。[oidc.md](oidc.md)を参照。

---

## なぜ重要なのか？

OAuth以前は、アプリが別のサービス上のあなたのデータにアクセスしたい場合、そのサービスのユーザー名とパスワードをアプリに渡す必要がありました。これは最悪でした：

- アプリはあなたの認証情報で何でもできた（承認したことだけでなく）
- パスワードを変更しない限りアプリのアクセスを取り消せなかった
- アプリがハッキングされたらパスワードが漏洩した
- すべてのサードパーティアプリに最も機密性の高い認証情報を信頼しなければならなかった

OAuthはトークンベースの委任認可を導入してこれを解決しました。ユーザーが特定の権限を承認し、アプリは限定的なトークンを受け取り、ユーザーはいつでもそれを取り消せます。

---

## どう動くのか？

### 4つのグラントタイプ

OAuth 2.0は複数の「グラントタイプ」を定義しています。異なる状況に対応する異なるフローです：

| グラントタイプ | 使用者 | 仕組み |
|-------------|--------|--------|
| **Authorization Code** | Webアプリ、SPA | ユーザーがブラウザで承認、アプリがコードを取得、トークンに交換。ユーザー向けアプリで最も安全。 |
| **Client Credentials** | サーバー間 | ユーザーが関与しない。アプリが自身の認証情報で認証。[client-credentials.md](client-credentials.md)参照。 |
| **Device Code** | テレビ、CLIツール | デバイスがコードを表示、ユーザーが別のデバイスで入力。 |
| **Refresh Token** | リフレッシュトークンを持つクライアント | リフレッシュトークンを新しいアクセストークンに交換。 |

（注：「Implicit」と「Resource Owner Password Credentials」グラントタイプは非推奨であり、使用すべきではありません。）

### Authorization Code Flow（ブラウザ用）

最も一般的なフローで、voltaが（OIDC経由で）使用するものです。完全なステップバイステップ図は[oidc.md](oidc.md)を参照。

```
  ブラウザ ──► 「Googleでログイン」 ──► Google ──► 「許可しますか？」 ──► はい
                                                                         │
    GoogleがブラウザにCODE（URL内）を返す                                  │
    ブラウザがCODEをサーバーに送信                                         │
    サーバーがCODEをトークンに交換（サーバー間通信）                         │
    サーバーが取得：access_token + id_token                               │
                                                                         │
  ◄────────────────────── ログイン完了！ ────────────────────────────────┘
```

重要なポイント：
- **認可コード**は短命で1回限り使用
- コード交換は**サーバー間**で行われる（ブラウザはURLでアクセストークンを見ない）
- コードの横取りを防ぐために**PKCE**を使うべき（[pkce.md](pkce.md)参照）

### Client Credentials Flow（サーバー用）

このフローはマシン間（M2M）通信用です。ユーザーは関与しません。完全な説明は[client-credentials.md](client-credentials.md)を参照。

```
  サービスA ──► 「これが私のclient_idとclient_secretです」
            ──► 認証サーバー
            ◄── 「これがあなたのaccess_tokenです」

  サービスA ──► 「Bearer <access_token>」
            ──► サービスB
            ◄── レスポンス
```

### OAuth 2.0のトークン

| トークン | 目的 | 有効期間 | volta相当 |
|---------|------|---------|----------|
| **Access Token** | API呼び出しの認可 | 短い（分〜時間） | volta JWT（5分） |
| **Refresh Token** | 新しいaccess tokenの取得 | 長い（時間〜日） | voltaセッションCookie（8時間） |
| **ID Token** | 身元の証明（OIDCのみ） | 短い | Googleのid_token、voltaで検証 |

voltaはOAuth 2.0のアクセストークンを直接使いません。代わりに、Googleのid_token（OIDC）を検証し、独自のJWTを発行します。volta JWTはアクセストークンと似た目的を果たしますが、自己完結的でプロジェクト固有です。

---

## volta-auth-proxyではどう使われているか？

### OAuthクライアントとして（Phase 1）

voltaはGoogleに対するOAuth 2.0 / OIDC**クライアント**として動作します：

```
  volta-auth-proxy                          Google
  (OAuthクライアント)                        (OAuthプロバイダー / IdP)

  1. ユーザーをGoogleの認可エンドポイントにリダイレクト
  2. ユーザーがGoogleで認証
  3. Googleが認可コードと一緒にリダイレクトバック
  4. voltaがコードをid_tokenに交換（サーバー間通信）
  5. voltaがid_tokenを検証
  6. voltaが独自のセッションとJWTを作成
```

voltaはPhase 1ではOAuthプロバイダーとしては動作しません。アプリはvoltaに対してOAuthフローを実行しません。代わりに、ForwardAuthヘッダーやInternal APIを使います。

### OAuthプロバイダーとして（Phase 2 - 計画）

Phase 2では、voltaはM2M認証用のClient Credentialsグラントタイプの実装を計画しています。これによりバックエンドサービスがvoltaと直接認証できます：

```
  バックエンドサービス                     volta-auth-proxy
  (OAuthクライアント)                      (OAuthプロバイダー)

  1. POST /oauth/token
     grant_type=client_credentials
     &client_id=my-service
     &client_secret=secret123
     &scope=read:members

  2. voltaがクライアント認証情報を検証
  3. voltaがM2Mクレーム付きJWTを発行

  4. サービスがJWTを使って他のサービスやvoltaのAPIを呼ぶ
```

### Phase 1：静的サービストークン（ブリッジソリューション）

Client Credentialsグラントが実装されるまで、voltaはよりシンプルなM2Mメカニズムを提供します：

```
  バックエンドサービス                     volta-auth-proxy

  1. リクエストを送信：
     Authorization: Bearer volta-service:<VOLTA_SERVICE_TOKEN>

  2. voltaが静的トークンを検証
  3. サービスプリンシパルを返す（ユーザープリンシパルではない）
```

これは`VOLTA_SERVICE_TOKEN`環境変数で設定されます。シンプルですが制限があります：
- すべてのサービスで1つのトークン（サービスごとのスコープなし）
- トークンの有効期限なし（無効化にはリデプロイが必要）
- サービスごとの監査証跡なし

Client Credentialsグラント（Phase 2）がこれらすべてを解決します。

---

## よくある間違いと攻撃

### 間違い1：Implicitグラントを使う

Implicitグラント（アクセストークンがURLフラグメントで直接返される）は非推奨です。ブラウザ履歴、リファラヘッダー、ログにトークンが露出します。代わりにAuthorization Code + PKCEを使ってください。

### 間違い2：redirect_uriを検証しない

OAuthプロバイダーが`redirect_uri`を厳密に検証しなければ、攻撃者が認可コードを自分のサーバーにリダイレクトできます。正確なリダイレクトURIを登録し、厳密に一致させてください。

### 間違い3：アクセストークンをID証明に使う

OAuthアクセストークンは「このトークンにはXをする権限がある」と言います。「このトークンの持ち主はユーザーYである」とは**言いません**。IDにはOIDCのid_tokenを使ってください。アクセストークンをIDに信頼すると混乱した代理人攻撃につながります。

### 間違い4：長寿命のアクセストークン

アクセストークンの有効期限が24時間で盗まれたら、攻撃者は24時間アクセスできます。リフレッシュ機能付きの短命トークン（volta: 5分）を使ってください。

### 攻撃：認可コードの傍受

PKCEなしでは、攻撃者が認可コードを傍受してトークンに交換できます。[pkce.md](pkce.md)を参照。

### 攻撃：redirect_uri経由のオープンリダイレクト

OAuthプロバイダーがリダイレクトURIにワイルドカードや部分一致を許可すると、攻撃者が認可コードを自分のサーバーにリダイレクトするURLを作れます。voltaは正確なリダイレクトURIマッチングを使用します。

---

## さらに学ぶために

- [RFC 6749 - The OAuth 2.0 Authorization Framework](https://tools.ietf.org/html/rfc6749) -- 公式OAuth 2.0仕様。
- [OAuth 2.0 Simplified](https://www.oauth.com/) by Aaron Parecki -- 最高の実践的ガイド。
- [oidc.md](oidc.md) -- OIDCがOAuth 2.0に認証をどう追加するか。
- [client-credentials.md](client-credentials.md) -- M2Mグラントタイプ。
- [pkce.md](pkce.md) -- 認可コードフローの保護。
