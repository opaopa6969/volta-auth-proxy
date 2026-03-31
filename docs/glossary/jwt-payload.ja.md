# JWT ペイロード

[English version](jwt-payload.md)

---

## これは何？

ペイロードは JWT の2番目の部分です（`ヘッダー.ペイロード.署名`）。**クレーム（claims）** -- ユーザーとトークン自体に関する情報のキー・バリューペアが含まれています。手紙の本文のようなものです。

クレームには2種類あります：
- **登録済みクレーム：** JWT 仕様で定義された標準的な名前（例：`iss`、`sub`、`exp`）。
- **カスタムクレーム：** アプリケーション固有のデータ（例：`volta_tid`、`volta_roles`）。

---

## なぜ重要？

ペイロードが JWT を有用にしています。「このユーザーは認証済み」とだけ言う代わりに、「これはユーザー X で、テナント Y に所属し、ロール Z を持ち、このトークンは時刻 T に期限切れになる」と伝えられます。受信サービスはデータベースを呼び出さずに、単一のトークンから必要なコンテキストをすべて取得できます。

重要：**ペイロードは暗号化されていません。** base64url エンコードされているだけなので、誰でもデコードして読めます。パスワード、クレジットカード番号などの秘密情報を JWT ペイロードに入れてはいけません。

---

## 簡単な例

デコードされた volta の JWT ペイロード：

```json
{
  "iss": "volta-auth",
  "aud": ["volta-apps"],
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "exp": 1711875900,
  "iat": 1711875600,
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "volta_v": 1,
  "volta_tid": "660e8400-e29b-41d4-a716-446655440000",
  "volta_roles": ["ADMIN"],
  "volta_display": "田中太郎",
  "volta_tname": "Acme Corp",
  "volta_tslug": "acme-corp"
}
```

### 登録済みクレーム

| クレーム | 正式名 | 意味 |
|---------|--------|------|
| `iss` | Issuer | トークンを作成した者 |
| `aud` | Audience | トークンの対象 |
| `sub` | Subject | ユーザーの一意 ID |
| `exp` | Expiration | トークンの有効期限（Unix タイムスタンプ） |
| `iat` | Issued At | トークンの発行時刻 |
| `jti` | JWT ID | このトークン固有の ID |

### volta カスタムクレーム

| クレーム | 意味 |
|---------|------|
| `volta_v` | スキーマバージョン（現在 `1`）。将来の変更時に既存トークンを壊さないため |
| `volta_tid` | テナント ID。このトークンがスコープされているワークスペース |
| `volta_roles` | このテナントでのユーザーのロール（例：`["MEMBER"]`、`["ADMIN"]`） |
| `volta_display` | ユーザーの表示名 |
| `volta_tname` | テナント名 |
| `volta_tslug` | テナントスラッグ（URL セーフな識別子） |

---

## volta-auth-proxy では

volta は `JwtService.issueToken()` でペイロードを構築します：

```java
new JWTClaimsSet.Builder()
    .issuer(config.jwtIssuer())           // "volta-auth"
    .audience(List.of(config.jwtAudience()))  // ["volta-apps"]
    .subject(principal.userId().toString())
    .expirationTime(Date.from(now.plusSeconds(config.jwtTtlSeconds())))  // 5分
    .issueTime(Date.from(now))
    .jwtID(UUID.randomUUID().toString())
    .claim("volta_v", 1)
    .claim("volta_tid", principal.tenantId().toString())
    .claim("volta_roles", principal.roles())
    .claim("volta_display", principal.displayName())
    .claim("volta_tname", principal.tenantName())
    .claim("volta_tslug", principal.tenantSlug())
    .build();
```

`volta_v` クレームは先を見据えた設計です。v2 でクレームスキーマが変わった場合（例：ネストされた権限の追加）、下流のサービスは `volta_v` を確認して両方のフォーマットを適切に処理できます。

---

## 関連項目

- [jwt-header.md](jwt-header.md) -- 1番目の部分：アルゴリズムと鍵 ID
- [jwt-signature.md](jwt-signature.md) -- 3番目の部分：改ざんされていない証明
- [jwt-decode-howto.md](jwt-decode-howto.md) -- ペイロードを自分で読む方法
