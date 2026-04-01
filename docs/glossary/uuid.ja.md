# UUID（汎用一意識別子）

[English version](uuid.md)

---

## これは何？

UUID（Universally Unique Identifier、汎用一意識別子）は、何かを一意に識別するために使われる128ビットの数値です。見た目はこうなります：`550e8400-e29b-41d4-a716-446655440000`。UUIDの重要な特性は、どこでも -- どのコンピュータでも、いつでも、誰とも調整せずに -- 生成でき、過去に生成された他のどのUUIDとも衝突しないことが事実上保証されていることです。

指紋に似ています。すべての人が一意の指紋を持っています。指紋が一意であることを中央登録局に確認する必要はありません -- 指紋が形成される仕組みの性質上、そうなっているのです。UUIDも同じです。数値空間が広大なため（2^128 = 340澗の可能性）、ランダムな衝突は本質的に不可能です。

volta-auth-proxyはすべての識別子にUUIDを使用しています：ユーザーID、テナントID、メンバーID、招待トークン、JWT ID（jti）。これは意図的な設計選択です。

---

## なぜ重要なのか？

UUIDがなければ、通常は連番整数（1, 2, 3, ...）をIDとして使います。これはいくつかの問題を生みます：

- **セキュリティ** -- 連番IDは推測可能。ユーザーIDが42なら、攻撃者は43、44、45...を試して他のユーザーのデータにアクセスする。UUIDは推測不可能。
- **分散生成** -- 連番IDでは、単一の[データベース](database.md)が各番号を割り当てなければならない。UUIDなら任意の[プロセス](process.md)が独立してIDを生成できる。
- **情報漏洩** -- テナントIDが5なら、攻撃者はテナントが最大5つしかないと知る。UUID `abcd1234-...`はテナント数について何も明かさない。
- **マージの安全性** -- 複数のシステムからデータを結合する際、連番IDは衝突する（両方にユーザー「1」がある）。UUIDは衝突しない。

---

## どう動くのか？

### UUIDのフォーマット

UUIDは32個の16進文字（0-9, a-f）で、ハイフンで区切られた5つのセクションにグループ化されます：

```
  550e8400-e29b-41d4-a716-446655440000
  ├──────┤ ├──┤ ├──┤ ├──┤ ├──────────┤
  8文字    4    4    4    12文字
           文字  文字  文字

  合計: 32の16進文字 = 128ビット = 16バイト
```

### UUIDのバージョン

| バージョン | 生成方法 | 一意性保証 | voltaで使用？ |
|-----------|---------|-----------|-------------|
| v1 | タイムスタンプ + MACアドレス | マシン/時間ごとに一意 | いいえ |
| v3 | 名前のMD5ハッシュ | 決定論的（同じ入力 = 同じUUID） | いいえ |
| v4 | **ランダム** | 統計的に一意 | **はい** |
| v5 | 名前のSHA-1ハッシュ | 決定論的 | いいえ |
| v7 | タイムスタンプ + ランダム（ソート可能） | 時間 + ランダムで一意 | 将来の可能性 |

voltaは**UUID v4**（ランダム）を使用しており、データベースIDの最も一般的な選択です。

### v4はどれだけランダムか？

UUID v4は122個のランダムビットを使用します（6ビットはバージョン/バリアント用に予約）。衝突の確率：

```
  50%の衝突確率に必要なUUIDの数:
  2.71 x 10^18（2.71京）

  毎秒10億個のUUIDを生成した場合:
  50%の衝突確率に達するのに85年かかる。

  実際には、voltaがこれが問題になるほどのUUIDを生成することは決してない。
```

### JavaでのUUID

```java
import java.util.UUID;

// ランダムUUID（v4）を生成
UUID id = UUID.randomUUID();
// "550e8400-e29b-41d4-a716-446655440000"

// 文字列からUUIDをパース
UUID parsed = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

// UUIDを比較
if (userId.equals(tenantOwnerId)) {
    // これがオーナー
}
```

### PostgreSQLでのUUID

PostgreSQLにはUUIDを16バイトで効率的に格納するネイティブな`uuid`型があります：

```sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       TEXT NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT now()
);

-- 自動生成UUIDで挿入
INSERT INTO users (email) VALUES ('taro@example.com');

-- UUIDでクエリ
SELECT * FROM users WHERE id = '550e8400-e29b-41d4-a716-446655440000';
```

### UUID vs 連番整数

| 特性 | UUID | 連番整数 |
|------|------|---------|
| サイズ | 16バイト | 4-8バイト |
| 推測可能？ | いいえ | はい（インクリメントするだけ） |
| 生成者 | 任意のプロセス | データベースのみ |
| インデックス性能 | やや遅い（ランダム、非連続） | 速い（連続） |
| 情報漏洩 | なし | 総数が分かる |
| 異システム間マージ | 衝突なし | 衝突の可能性大 |

---

## volta-auth-proxy ではどう使われている？

### あらゆる場所でUUID

voltaはすべてのエンティティIDにUUIDを使用しています：

```
  エンティティ        UUIDの例                                   カラム
  ──────              ────────────                               ──────
  ユーザーID          550e8400-e29b-41d4-a716-446655440000       users.id
  テナントID          abcd1234-5678-9012-3456-789012345678       tenants.id
  メンバーID          11111111-2222-3333-4444-555555555555       members.id
  招待トークン        aaaabbbb-cccc-dddd-eeee-ffffffffffff       invitations.token
  JWT ID (jti)       12345678-1234-1234-1234-123456789012       （JWTペイロード内）
```

### APIでのUUID

voltaの[API](api.md)は[エンドポイント](endpoint.md)パスとレスポンスボディでUUIDを使用します：

```
  GET /api/v1/users/me
  レスポンス:
  {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "tenantId": "abcd1234-5678-9012-3456-789012345678",
    "displayName": "Taro Yamada"
  }
```

### JWTクレームでのUUID

[JWT](jwt.md)ペイロードはサブジェクト（ユーザー）とテナントのUUIDを含みます：

```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "jti": "12345678-1234-1234-1234-123456789012",
  "volta_tid": "abcd1234-5678-9012-3456-789012345678"
}
```

### X-VoltaヘッダーでのUUID

voltaが[ForwardAuth](forwardauth.md)経由で認証情報を転送するとき：

```
X-Volta-User-Id: 550e8400-e29b-41d4-a716-446655440000
X-Volta-Tenant-Id: abcd1234-5678-9012-3456-789012345678
```

### 型安全なUUID処理

voltaはコードベース全体でJavaの`UUID`型を使用し、`String`ではありません。これにより、テナントIDがユーザーIDを期待する場所で誤って使われることを防げます：

```java
// voltaのアプローチ -- UUIDは型付き
UUID userId = UUID.fromString(ctx.pathParam("userId"));
UUID tenantId = session.tenantId();

// Stringアプローチ -- 混同しやすい
String userId = ctx.pathParam("userId");     // これはユーザーIDかテナントID？
String tenantId = session.tenantId();        // コンパイラの助けなし
```

---

## よくある間違いと攻撃

### 間違い1：UUIDを連番として扱う

UUIDはランダムです。時系列順を得るためにUUIDでソートしないでください -- 代わりに`created_at`タイムスタンプカラムを使いましょう。

### 間違い2：UUIDだけをセキュリティトークンとして使う

UUIDは推測不可能ですが、秘密ではありません。招待UUIDが`/invite/accept?token=aaaa-bbbb-...`のようなURLに含まれると、ブラウザ履歴、リファラーヘッダー、アクセスログを通じて漏洩する可能性があります。高セキュリティトークンには、追加のサーバーサイドチェックを検討してください。

### 攻撃1：UUIDの列挙

UUIDはインクリメントでは推測できませんが、攻撃者は一般的なパターンを試すかもしれません：

```
00000000-0000-0000-0000-000000000000  （nil UUID）
11111111-1111-1111-1111-111111111111  （繰り返しパターン）
```

voltaは予測可能なUUIDを使用しません。すべてのUUIDは`UUID.randomUUID()`またはPostgreSQLの`gen_random_uuid()`で生成されます。

### 間違い3：データベースにUUIDを文字列として保存する

UUIDを`VARCHAR(36)`として保存するとスペースの無駄（36バイト vs 16バイト）で、インデックスも遅くなります。PostgreSQLのネイティブ`uuid`型が正しい選択です。voltaは`uuid`カラム型を使用しています。

### 間違い4：パスパラメータのUUID形式を検証しない

[エンドポイント](endpoint.md)がUUIDパスパラメータを期待する場合、常に`UUID.fromString()`でパースしてください。クライアントが`/api/v1/users/not-a-uuid`を送ると例外がスローされ、400 Bad Requestとして返せます。

---

## さらに学ぶ

- [RFC 9562 - UUIDs](https://www.rfc-editor.org/rfc/rfc9562) -- 公式UUID仕様（RFC 4122を置き換え）。
- [database.md](database.md) -- UUIDが主キーとして保存される場所。
- [jwt.md](jwt.md) -- subとjtiクレーム内のUUID。
- [header.md](header.md) -- X-Volta-User-IdとX-Volta-Tenant-Id内のUUID。
- [type-safe.md](type-safe.md) -- voltaがStringではなくUUID型を使う理由。
- [sql.md](sql.md) -- PostgreSQLのuuidカラム型。
