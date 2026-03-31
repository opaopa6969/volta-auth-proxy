# ページネーション

[English version](pagination.md)

---

## これは何？

ページネーションとは、大量のデータを一度にすべて返すのではなく、小さなまとまり（ページ）に分けて返す手法です。本がコンテンツをページに分けるように、API も結果を扱いやすい単位に分割します。

ページネーションがないと、「全ユーザー」のリクエストが何百万行も返し、クライアント、ネットワーク、データベースを圧倒する可能性があります。

---

## なぜ重要？

**パフォーマンス：** ユーザーが20件しか見ないのに10,000行を返すのは、帯域幅、メモリ、データベース時間の無駄です。

**セキュリティ：** 上限なしのクエリはサービス拒否攻撃のベクトルです。攻撃者が `?limit=999999999` をリクエストしてサーバーをクラッシュさせる可能性があります。

**UX：** ユーザーは何千ものアイテムを一度に閲覧することはできません。ページネーションは「次へ/前へ」ナビゲーション付きの見やすいビューを提供します。

---

## 簡単な例

### Offset/limit（volta が使用）

```
GET /api/v1/tenants/abc/members?offset=0&limit=20   -> アイテム 1-20
GET /api/v1/tenants/abc/members?offset=20&limit=20  -> アイテム 21-40
GET /api/v1/tenants/abc/members?offset=40&limit=20  -> アイテム 41-60
```

**メリット：** 理解と実装が簡単。どの SQL データベースでも動作。
**デメリット：** 深いページが遅い（offset=100000 でも 100,000 行をスキャン）。ページ間でデータが追加/削除されると結果がずれる。

### カーソルベース（代替手段）

```
GET /api/v1/items?limit=20                           -> アイテム 1-20, cursor="abc"
GET /api/v1/items?limit=20&after=abc                 -> アイテム 21-40, cursor="def"
```

**メリット：** 深さに関係なく高速。結果が安定。
**デメリット：** より複雑。「50ページ目」に直接ジャンプできない。

### どちらを使うべき？

| シナリオ | ベストなアプローチ |
|---------|-----------------|
| 管理ダッシュボード、小規模データ | Offset/limit |
| 無限スクロール、大規模データ | カーソルベース |
| リアルタイムフィード（チャット、イベント） | カーソルベース |

---

## volta-auth-proxy では

volta はすべてのリスト系エンドポイントで **offset/limit ページネーション**を使用しています。実装は悪用防止のために最大 limit を強制します：

```java
private static int parseLimit(String limitRaw) {
    if (limitRaw == null) {
        return 20;  // デフォルト: 20件
    }
    int value = Integer.parseInt(limitRaw);
    return Math.min(100, Math.max(1, value));  // 1-100 に制限
}

private static int parseOffset(String offsetRaw) {
    if (offsetRaw == null) {
        return 0;
    }
    return Math.max(0, Integer.parseInt(offsetRaw));
}
```

主な設計判断：
- **デフォルト limit: 20。** ほとんどの UI で妥当なページサイズ。
- **最大 limit: 100。** クライアントが巨大な結果セットをリクエストすることを防止。
- **最小 limit: 1。** ゼロや負の limit を防止。
- **Offset の下限: 0。** 負の offset を防止。

レスポンスにはページネーションのメタデータが含まれ、クライアントが現在位置を把握できます：

```json
{
  "items": [...],
  "offset": 20,
  "limit": 20
}
```

volta の現在の規模（管理ダッシュボード、テナントメンバーリスト）では offset/limit が正しい選択です。監査ログストリーミングやアクティビティフィードなどの機能を追加する場合は、カーソルベースのページネーションが適切になります。

---

## 関連項目

- [api-versioning.md](api-versioning.md) -- ページネーションパラメータが API 契約の一部である理由
- [content-type.md](content-type.md) -- ページネーションされたレスポンスの JSON フォーマット
