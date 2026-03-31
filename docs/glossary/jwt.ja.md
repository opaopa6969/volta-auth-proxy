# JWT (JSON Web Token)

[English version](jwt.md)

---

## これは何？

JSON Web Token（JWT、「ジョット」と読む）は、あるシステムが別のシステムに何かを証明するためのコンパクトで自己完結的なデータです。通常は「この人は認証済みで、こういう属性を持っている」ということを証明します。ランダムな文字列に見えますが、実際にはデジタル署名された構造化データです。

コンサートのリストバンドに似ています。入口の警備員がチケットを確認してリストバンドを渡します。その後、会場内のすべてのスタッフはリストバンドを見るだけで入場許可があることが分かります。入口の警備員にいちいち確認する必要はありません。JWTはそのリストバンドのデジタル版です。

---

## なぜ重要なのか？

JWTがなければ、アプリがリクエストを受け取るたびに認証サーバーに「この人は本当にログインしていますか？」と問い合わせる必要があります。これがボトルネックになります。すべてのリクエストが通らなければならないサーバーが1台できてしまいます。

JWTがあれば、認証サーバーは署名付きトークンを1回発行するだけです。その後、どのサービスも署名を確認するだけで自力でトークンを検証できます。コールバック不要です。複数のサービスがIDを検証する必要がある分散システムでは特に重要です。

JWTが存在しなかったら、すべてのマイクロサービスがセッションデータベースに直接アクセスする必要があり、密結合と単一障害点が生まれます。

---

## どう動くのか？

### 3つのパート

JWTはドットで区切られた3つのパートで構成されます：

```
eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImtleS0yMDI2LTAzLTMxVDA5LTAwIn0.eyJpc3MiOiJ2b2x0YS1hdXRoIiwiYXVkIjpbInZvbHRhLWFwcHMiXSwic3ViIjoiNTUwZTg0MDAtZTI5Yi00MWQ0LWE3MTYtNDQ2NjU1NDQwMDAwIiwiZXhwIjoxNzExOTAwMDAwLCJpYXQiOjE3MTE4OTk3MDAsImp0aSI6IjEyMzQ1Njc4LTEyMzQtMTIzNC0xMjM0LTEyMzQ1Njc4OTAxMiIsInZvbHRhX3YiOjEsInZvbHRhX3RpZCI6ImFiY2QxMjM0LTU2NzgtOTAxMi0zNDU2LTc4OTAxMjM0NTY3OCIsInZvbHRhX3JvbGVzIjpbIkFETUlOIl0sInZvbHRhX2Rpc3BsYXkiOiJUYXJvIFlhbWFkYSIsInZvbHRhX3RuYW1lIjoiQUNNRSBDb3JwIiwidm9sdGFfdHNsdWciOiJhY21lIn0.SIGNATURE_HERE
```

分解してみましょう：

```
ヘッダー.ペイロード.署名

  パート1: ヘッダー      パート2: ペイロード      パート3: 署名
  (メタデータ)           (実際のデータ)           (完全性の証明)
  ┌──────────────┐       ┌──────────────────┐     ┌──────────────────┐
  │ {            │       │ {                │     │                  │
  │  "alg":"RS256│       │  "sub":"user-id" │     │  (パート1+2が     │
  │  "typ":"JWT" │       │  "exp":171190..  │     │   改ざんされて    │
  │  "kid":"key-"│       │  "volta_tid":... │     │   いないことを    │
  │ }            │       │  ...             │     │   証明するバイナリ │
  └──────────────┘       │ }                │     │   データ)         │
                         └──────────────────┘     └──────────────────┘
```

各パートは**Base64URLエンコード**されています（バイナリデータをURLセーフなテキストに変換する方法）。暗号化はされていません。誰でもデコードできます。

### JWTのデコード方法

ターミナルでヘッダーとペイロードをデコードできます：

```bash
# 最初のパート（ヘッダー）を取り出してデコード
echo 'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImtleS0yMDI2LTAzLTMxVDA5LTAwIn0' | base64 -d

# 出力:
# {"alg":"RS256","typ":"JWT","kid":"key-2026-03-31T09-00"}
```

または[jwt.io](https://jwt.io)のようなウェブサイトにJWTを貼り付ければ、3つのパートすべてがデコードされた状態で見られます。

### 重要：JWTは暗号化されていない

これが最もよくある誤解です。JWTは**署名**されているのであって、暗号化されているのではありません。署名とは「このデータが改変されていないことを証明できる」という意味です。暗号化とは「誰もこのデータを読めない」という意味です。これらは別物です。

```
  署名されている (JWT):    誰でもデータを読める。
                           署名が壊れるため、誰も改変できない。

  暗号化されている:         誰もデータを読めない。
                           鍵の持ち主だけが復号できる。
```

だからJWTの中に**秘密情報**（パスワード、クレジットカード番号など）を入れてはいけません。傍受すれば誰でもデコードできます。できないのは改変です。ペイロードの1文字でも変えると、署名が一致しなくなります。

### Claims（クレーム）とは？

ペイロードの中のデータは「Claims（クレーム）」と呼ばれます。クレームはトークンが「主張する」情報です。3種類あります：

**登録済みクレーム**（標準、JWT仕様で定義）：

| クレーム | 名前 | 例 | 目的 |
|---------|------|---|------|
| `iss` | Issuer（発行者） | `"volta-auth"` | このトークンを誰が作ったか |
| `aud` | Audience（対象者） | `["volta-apps"]` | このトークンは誰向けか |
| `sub` | Subject（主体） | `"550e8400-..."` | このトークンは誰のものか（通常ユーザーID） |
| `exp` | Expiration（有効期限） | `1711900000` | このトークンの有効期限（Unixタイムスタンプ） |
| `iat` | Issued At（発行時刻） | `1711899700` | このトークンが作られた時刻 |
| `jti` | JWT ID | `"12345678-..."` | このトークン固有の識別子 |

**パブリッククレーム**（IANA JWTレジストリまたはURIで定義、衝突を避けるため）：

`email`や`name`のように、よく知られた意味を持つクレームです。

**プライベートクレーム**（カスタム、当事者間で合意）：

独自のクレームです。voltaは衝突を避けるために`volta_`接頭辞を使います：

| クレーム | 例 | 目的 |
|---------|---|------|
| `volta_v` | `1` | スキーマバージョン（将来の互換性のため） |
| `volta_tid` | `"abcd1234-..."` | テナントID |
| `volta_tname` | `"ACME Corp"` | テナント表示名 |
| `volta_tslug` | `"acme"` | テナントURLスラッグ |
| `volta_roles` | `["ADMIN"]` | このテナントでのユーザーのロール |
| `volta_display` | `"Taro Yamada"` | ユーザーの表示名 |

### volta JWTの実例

デコードしたvolta JWTは以下のようになります：

**ヘッダー：**
```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "key-2026-03-31T09-00"
}
```

**ペイロード：**
```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "exp": 1711900000,
  "iat": 1711899700,
  "jti": "12345678-1234-1234-1234-123456789012",
  "volta_v": 1,
  "volta_tid": "abcd1234-5678-9012-3456-789012345678",
  "volta_tname": "ACME Corp",
  "volta_tslug": "acme",
  "volta_roles": ["ADMIN"],
  "volta_display": "Taro Yamada"
}
```

ペイロードに**含まれていない**もの：ユーザーのメールアドレス。voltaは意図的に除外しています。メールが必要なら`/api/v1/users/me`を呼びます。JWTが漏洩した場合の被害を限定するためです。

**署名：**

署名はヘッダー + "." + ペイロードをRSA秘密鍵で署名して作られます。秘密鍵を持っているのはvolta-auth-proxyだけです。公開鍵（`/.well-known/jwks.json`で公開）を持っていれば誰でも署名を検証できます。

---

## volta-auth-proxyではどう使われているか？

### voltaでのJWTライフサイクル

```
  1. ユーザーがGoogle OIDCでログイン
                │
                ▼
  2. voltaがセッションを作成（Cookieベース、8時間）
                │
                ▼
  3. voltaがJWTを発行（RS256、5分で期限切れ）
     ┌────────────────────────────────────────┐
     │  JWTに含まれるもの：ユーザーID、テナントID、│
     │  ロール、表示名、テナントスラッグ          │
     └────────────────────────────────────────┘
                │
                ▼
  4. JWTはアプリに以下の方法で送られる：
     - X-Volta-JWTヘッダー（ForwardAuth経由）
     - Authorization: Bearer <jwt>（Internal API）
                │
                ▼
  5. アプリがJWKS公開鍵を使ってJWTをローカルで検証
     （voltaへのコールバック不要）
                │
                ▼
  6. JWTは5分後に期限切れ
                │
                ▼
  7. volta-sdk-jsが401を検知 -> /auth/refreshを呼ぶ
     -> 有効なセッションから新しいJWTを取得 -> リトライ
```

### なぜ5分の有効期限なのか？

短い有効期限はセキュリティのトレードオフです：

- **短い有効期限（voltaのアプローチ）：** JWTが盗まれても、攻撃者が使えるのは最大5分間。ユーザーのロール変更や削除は最大5分で反映されます。
- **長い有効期限（例：24時間）：** リフレッシュのオーバーヘッドは少ないですが、盗まれたトークンが長時間有効になり、権限変更の反映が遅れます。

voltaはバランスポイントとして5分を選びました。volta-sdk-jsがリフレッシュを自動処理するので、ユーザーが気づくことはありません。

### JWT発行コード

`JwtService.java`クラスがJWTの作成と検証を処理します：

- `issueToken()` -- すべてのvoltaクレームを含む新しいJWTを作成し、アクティブなRSA秘密鍵で署名します。
- `verify()` -- JWTをパースし、アルゴリズムがRS256であることを確認（HS256とnoneを拒否）、署名を検証、issuer、audience、有効期限をチェックします。
- `jwksJson()` -- `/.well-known/jwks.json`用のJWKS形式で公開鍵を返します。

---

## よくある間違いと攻撃

### 攻撃1：`alg:none`攻撃

JWT仕様では`"none"`というアルゴリズム値が許可されており、「このトークンは署名されていない」を意味します。サーバーが`alg`ヘッダーを盲目的に信頼すると、攻撃者は：

1. 有効なJWTを取得する
2. ペイロードを変更する（ロールをOWNERに変更、ユーザーIDを変更）
3. ヘッダーを`{"alg":"none"}`に設定する
4. 署名を削除する
5. サーバーに送る

サーバーが`alg:none`を受け入れると、署名検証をスキップして改変されたトークンを受け入れてしまいます。

**voltaの防御：** `JwtService.verify()`がアルゴリズムがRS256であることを明示的にチェックします。他のアルゴリズムは拒否されます。

```java
if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
    throw new IllegalArgumentException("Unsupported JWT algorithm");
}
```

### 攻撃2：アルゴリズム混乱（RS256 -> HS256）

これは巧妙な攻撃です。RS256は公開鍵/秘密鍵のペアを使います。HS256は共有秘密鍵を使います。公開鍵は...公開されています。攻撃者が：

1. 有効なJWTを取得する
2. ペイロードを変更する
3. `alg`をRS256からHS256に変更する
4. **公開鍵**をHS256の秘密として使って署名する

一部のJWTライブラリは`alg:HS256`を見ると、「検証鍵」（RS256検証用の公開鍵）をHMACの秘密として使い、署名が一致してしまいます。

**voltaの防御：** 上記と同じチェック -- RS256のみ受け入れます。詳細は[rs256.md](rs256.md)と[hs256.md](hs256.md)を参照。

### 攻撃3：署名の剥ぎ取り

認証済みと未認証の両方のエンドポイントを持つシステムでは、攻撃者が：

1. JWTを取得する
2. 署名を削除する（2番目のドットの後のすべて）
3. サーバーが未署名トークンとして扱い、クレームを読み取ることを期待する

**voltaの防御：** nimbus-jose-jwtライブラリは`SignedJWT`に有効な署名を要求します。空または欠落した署名は例外を引き起こします。

### 間違い1：JWTに機密データを保存する

JWTは暗号化されていないため、ペイロードの中身は誰でも読めます。パスワード、APIキー、個人データ（認可に必要な範囲を超えるもの）をJWTに入れないでください。

voltaはJWTペイロードを最小限に保ちます。メールアドレスも電話番号もありません。アプリが認可の判断に必要なものだけです。

### 間違い2：有効期限をチェックしない

JWTをデコードして`exp`チェックを省略すると、期限切れのトークンも動き続けます。必ず有効期限を検証してください。

### 間違い3：JWTをlocalStorageに保存する

ブラウザのlocalStorageに保存されたJWTは、ページ上のどのJavaScriptからもアクセス可能です。XSS脆弱性があれば、攻撃者がJWTを盗めます。voltaはJWTをlocalStorageに保存しません。セッションCookie経由で`/auth/refresh`から都度取得し、メモリ内でのみ使用します。

---

## さらに学ぶために

- [RFC 7519 - JSON Web Token](https://tools.ietf.org/html/rfc7519) -- 公式JWT仕様。
- [jwt.io](https://jwt.io/) -- インタラクティブなJWTデコーダーとライブラリ一覧。
- [Critical vulnerabilities in JSON Web Token libraries](https://auth0.com/blog/critical-vulnerabilities-in-json-web-token-libraries/) -- `alg:none`とRS256/HS256混乱攻撃の解説。
- [rs256.md](rs256.md) -- voltaがRS256を使う理由。
- [hs256.md](hs256.md) -- HS256が分散システムで危険な理由。
- [oidc.md](oidc.md) -- OIDCがJWTをid_tokenとしてどう使うか。
