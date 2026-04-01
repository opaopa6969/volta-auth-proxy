# クレーム

[English version](claim.md)

---

## これは何？

クレーム（Claim）とは、[JWT](jwt.md)の内部に記述された情報の断片です。サーバーがトークンを発行するとき、ユーザーについて、トークンの目的について、その制約について記述するクレームを含めます。「このトークンはユーザーがaliceで、acmeテナントに属し、ロールがADMINだと主張している」ということです。

社員証に印刷された情報を思い浮かべてください。社員証自体が[トークン](token.md)です。クレームはそこに書かれていること：あなたの名前、部署、社員番号、有効期限です。社員証の発行者を信頼する人は、そこに印刷されたクレームも信頼します。

クレームは暗号化されていません -- Base64エンコードされており、誰でも読めます。JWTの[デジタル署名](digital-signature.md)がクレームの改ざんされていないことを保証しますが、内容を隠すわけではありません。クレームに秘密情報（パスワード、クレジットカード番号）を入れてはいけません。

---

## なぜ重要なのか？

クレームによって、下流のアプリケーションは認証サーバーにコールバックせずにユーザーについて知ることができます。JWTを受け取ったマイクロサービスはクレームを読んで次の質問に答えられます：このユーザーは誰か？どのテナントに属するか？何を許可されているか？このトークンはいつ期限切れか？

クレームがなければ、すべてのサービスがリクエストごとに認証サーバーへのデータベースコールやAPIコールが必要になります。クレームはステートレスな検証を可能にします -- 情報がトークンと一緒に移動するのです。

クレームが欠けていたり間違っていたりすると、サービスは誤った認可判断をします。`exp`がなければトークンは期限切れになりません。`volta_roles`が間違っていれば、一般ユーザーが管理者アクセスを得てしまいます。

---

## どう動くのか？

### 標準（登録済み）クレーム

[JWT仕様（RFC 7519）](https://datatracker.ietf.org/doc/html/rfc7519)は標準クレームのセットを定義しています：

| クレーム | 名前 | 目的 | 例 |
|---------|------|------|-----|
| `iss` | Issuer（発行者） | トークンを誰が作ったか | `"https://volta.example.com"` |
| `sub` | Subject（主体） | トークンが誰についてか | `"user-uuid-1234"` |
| `aud` | Audience（対象者） | 誰がこのトークンを受け入れるべきか | `"https://api.example.com"` |
| `exp` | Expiration（有効期限） | トークンの死 | `1743530700`（Unixタイムスタンプ） |
| `iat` | Issued At（発行時刻） | トークンがいつ作られたか | `1743530400` |
| `jti` | JWT ID | このトークンの一意なID | `"550e8400-e29b-..."` |

### カスタム（プライベート）クレーム

アプリケーションは独自のクレームを追加できます。voltaは衝突を避けるためカスタムクレームに`volta_`プレフィックスを付けます：

```json
{
  "iss": "https://volta.example.com",
  "sub": "user-uuid-1234",
  "aud": "https://api.example.com",
  "exp": 1743530700,
  "iat": 1743530400,
  "jti": "550e8400-e29b-41d4-a716-446655440000",
  "volta_v": 1,
  "volta_tid": "tenant-uuid-5678",
  "volta_roles": ["admin", "editor"],
  "volta_display": "Alice Smith",
  "volta_tname": "Acme Corp",
  "volta_tslug": "acme"
}
```

### クレームは読める、秘密ではない

```
  JWTペイロード（Base64デコード後）:
  ┌──────────────────────────────────────────────────┐
  │  {                                               │
  │    "sub": "alice-uuid",        ← 誰か            │
  │    "volta_tid": "acme-uuid",   ← どのテナントか   │
  │    "volta_roles": ["admin"],   ← 何の権限か       │
  │    "exp": 1743530700,          ← いつ無効になるか  │
  │    "iss": "volta.example.com"  ← 誰が発行したか   │
  │  }                                               │
  └──────────────────────────────────────────────────┘
        │
        │  誰でもこれをデコードできる:
        │  echo <payload> | base64 -d
        │
        │  しかし署名を無効にせずに
        │  変更することは不可能
        ▼
  ┌──────────────────────────────────────────────────┐
  │  署名 (RS256)                                    │
  │  秘密鍵で作成 → 公開鍵で検証                       │
  │  クレームが1つでも変更されると署名が失敗            │
  └──────────────────────────────────────────────────┘
```

### クレーム検証フロー

```
  下流アプリがJWTを受信:

  1. ヘッダーをデコード → algがRS256か確認     ✓
  2. 公開鍵で署名を検証                        ✓
  3. クレームを確認:
     ├── exp > 現在時刻?                       ✓ 期限切れではない
     ├── iss == "volta.example.com"?           ✓ 正しい発行者
     ├── aud に自分のサービスが含まれる?         ✓ 自分宛て
     ├── volta_tid がリクエストのテナントと一致? ✓ 正しいテナント
     └── volta_roles に必要なロールが含まれる?   ✓ 認可済み
  4. リクエストを許可                           ✓
```

---

## volta-auth-proxy ではどう使われている？

voltaの`JwtService.issueToken()`はすべてのJWTにクレームを組み立てます：

```java
JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(config.jwtIssuer())               // iss
        .audience(audience)                        // aud
        .subject(principal.userId().toString())     // sub
        .expirationTime(Date.from(now.plusSeconds(  // exp (5分)
            config.jwtTtlSeconds())))
        .issueTime(Date.from(now))                 // iat
        .jwtID(UUID.randomUUID().toString())        // jti
        .claim("volta_v", 1)                       // スキーマバージョン
        .claim("volta_tid", principal.tenantId())   // テナントID
        .claim("volta_roles", principal.roles())    // ユーザーロール
        .claim("volta_display", principal.displayName())
        .claim("volta_tname", principal.tenantName())
        .claim("volta_tslug", principal.tenantSlug())
        .build();
```

### voltaのカスタムクレーム

| クレーム | 型 | 目的 |
|---------|-----|------|
| `volta_v` | Integer | 前方互換性のためのスキーマバージョン |
| `volta_tid` | UUID文字列 | [マルチテナント](multi-tenant.md)分離のためのテナントID |
| `volta_roles` | 文字列配列 | 認可のためのユーザーロール（`admin`、`member`等） |
| `volta_display` | 文字列 | ユーザー表示名 |
| `volta_tname` | 文字列 | テナント名 |
| `volta_tslug` | 文字列 | テナントスラッグ（URL安全な識別子） |
| `volta_client` | Boolean | M2M（マシン間）トークンの場合`true` |
| `volta_client_id` | 文字列 | M2MトークンのOAuthクライアントID |

### voltaでの検証

`JwtService.verify()`はすべての必須クレームを確認します：

1. アルゴリズムがRS256であること（[アルゴリズム混同攻撃](rs256.md)を防止）
2. 現在の[署名鍵](signing-key.md)に対して署名が有効であること
3. `iss`が設定された発行者と一致すること
4. `aud`が設定された対象者を含むこと
5. `exp`が未来であること

---

## よくある間違いと攻撃

### 間違い1: すべてのクレームを検証しない

署名を確認しても`iss`や`aud`を無視すると、他のシステムが発行したトークンや他のサービス向けのトークンを受け入れてしまいます。すべての登録済みクレームを検証しましょう。

### 間違い2: クレームに秘密情報を入れる

クレームはBase64エンコードされているだけで、暗号化されていません。トークンを持つ誰もが読めます。パスワード、APIキー、必要以上の個人情報を含めてはいけません。

### 間違い3: 署名検証なしにクレームを信頼する

署名が検証されていないJWTはただのJSONです。攻撃者は任意のクレームを作れます。必ず先に[暗号署名](cryptographic-signature.md)を検証しましょう。

### 攻撃: アルゴリズム混同によるクレーム注入

検証者が`alg: none`を受け入れたり、公開鍵を秘密として使ってRS256から[HS256](hs256.md)に切り替えると、攻撃者は任意のクレームを持つトークンを偽造できます。voltaは検証時にRS256をハードコードしてこれを防いでいます。

### 攻撃: クレーム改ざんによる権限昇格

攻撃者がトークン内の`volta_roles: ["admin"]`を書き換えます。[デジタル署名](digital-signature.md)が無効になるので失敗しますが、検証者が実際に署名を確認している場合に限ります。

---

## さらに学ぶ

- [jwt.md](jwt.md) -- クレームを含むトークン形式
- [jwt-payload.md](jwt-payload.md) -- クレームが存在するJWTのセクション
- [token.md](token.md) -- トークンの広い概念
- [digital-signature.md](digital-signature.md) -- クレームが改ざんから保護される仕組み
- [rs256.md](rs256.md) -- voltaがクレームの署名に使う具体的なアルゴリズム
