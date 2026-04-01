# CRUD

[English version](crud.md)

---

## これは何？

CRUDはCreate（作成）、Read（読み取り）、Update（更新）、Delete（削除）の略で、あらゆるデータに対して行える4つの基本操作です。シンプルなToDoリストから複雑なSaaSプラットフォームまで、ほぼすべてのアプリケーションは、さまざまなリソースに対するこの4つの操作に帰着します。

ファイルキャビネットのようなものです。新しいフォルダを**作成**して入れ、中身を見るためにフォルダを**読み取り**、書類を差し替えたり追加してフォルダを**更新**し、丸ごと取り除いてフォルダを**削除**します。ファイルキャビネットでできるのはこの4つだけで、ほとんどのソフトウェアがデータに対して行うのもこの4つだけです。

volta-auth-proxyでは、[内部API](internal-api.ja.md)がユーザー、テナント、メンバー、招待のCRUD操作を提供します。各操作はHTTPメソッドにマッピングされ、[RBAC](rbac.ja.md)ルールで保護されています。

---

## なぜ重要なのか？

CRUDはAPIを予測可能にするメンタルモデルです。開発者がREST APIを見ると、何が期待できるか即座にわかります：

- **一貫性**：すべてのリソースが同じパターンに従う（一覧、取得、作成、更新、削除）
- **発見可能性**：メンバーを一覧できるなら、おそらく作成と削除もできる
- **認可のマッピング**：CRUDは権限にきれいにマッピングできる（「ADMINは作成と削除可能、MEMBERは読み取りのみ」）
- **テスト**：すべてのリソースに対して4つの操作を体系的にテストできる
- **ドキュメント**：APIドキュメントが予測可能な構造に従う

---

## どう動くのか？

### CRUDからHTTPへのマッピング

```
  ┌──────────┬─────────┬──────────────────────────────────┐
  │ CRUD     │ HTTP    │ 例                               │
  │──────────│─────────│──────────────────────────────────│
  │ Create   │ POST    │ POST /api/v1/tenants/{id}/invitations   │
  │ Read     │ GET     │ GET  /api/v1/tenants/{id}/members       │
  │ Update   │ PATCH   │ PATCH /api/v1/tenants/{id}/members/{mid}│
  │ Delete   │ DELETE  │ DELETE /api/v1/tenants/{id}/members/{mid}│
  └──────────┴─────────┴──────────────────────────────────┘

  注：voltaはPATCH（部分更新）を使い、PUT（全置換）は使いません。
  これは意図的です：他のすべてのフィールドを送らずに
  display_nameだけを更新できます。
```

### CRUDとREST

REST APIはリソースを名詞（members, invitations, tenants）として整理し、HTTPメソッドを動詞（GET, POST, PATCH, DELETE）として使います。これはCRUDに直接マッピングされます：

```
  リソース: /api/v1/tenants/{tenantId}/members

  GET    /members          → 全メンバーを一覧     (Read)
  GET    /members/{id}     → 1人のメンバーを取得   (Read)
  POST   /members          → メンバーを追加       (Create)
  PATCH  /members/{id}     → メンバーのロール変更  (Update)
  DELETE /members/{id}     → メンバーを削除       (Delete)
```

### CRUDと認可

各CRUD操作は通常、異なる権限レベルを必要とします：

```
  ┌──────────┬───────────────┬──────────────────────────┐
  │ 操作     │ 必要なロール   │ 理由                     │
  │──────────│───────────────│──────────────────────────│
  │ Read     │ MEMBER+       │ チームの誰がいるか見る    │
  │ Create   │ ADMIN+        │ 新メンバーを招待          │
  │ Update   │ ADMIN+        │ ロールを変更              │
  │ Delete   │ ADMIN+        │ メンバーを削除            │
  └──────────┴───────────────┴──────────────────────────┘
```

---

## volta-auth-proxyではどう使われているか？

### メンバーCRUD

```yaml
# dsl/protocol.yamlから
- method: GET
  path: /tenants/{tenantId}/members
  auth: "MEMBER+"
  description: テナントメンバーを一覧（ページネーション付き）

- method: GET
  path: /tenants/{tenantId}/members/{memberId}
  auth: "MEMBER+"
  description: 個別メンバーの詳細

- method: PATCH
  path: /tenants/{tenantId}/members/{memberId}
  auth: "ADMIN+"
  description: メンバーのロール変更
  guards:
    - "自分のロールより上にはプロモートできない"
    - "最後のOWNERはデモートできない"

- method: DELETE
  path: /tenants/{tenantId}/members/{memberId}
  auth: "ADMIN+"
  description: メンバーを削除（無効化）
  guards:
    - "最後のOWNERは削除できない"
    - "自分自身は削除できない"
```

注：`POST /members`はありません。メンバーは直接作成ではなく[招待フロー](invitation-flow.ja.md)を通じて作成されるためです。

### 招待CRUD

```
  Create:  POST   /tenants/{tid}/invitations     ADMIN+
  Read:    GET    /tenants/{tid}/invitations     ADMIN+
  Delete:  DELETE /tenants/{tid}/invitations/{id} ADMIN+
  Update:  （未対応 — 招待は作成後不変）
```

### レスポンス形式

すべてのCRUD操作は`protocol.yaml`で定義された一貫したレスポンス形式に従います：

```json
// 単一リソース（1件取得）：
{
  "data": { "id": "uuid", "role": "ADMIN", ... },
  "meta": { "request_id": "uuid" }
}

// コレクション（複数取得）：
{
  "data": [{ ... }, { ... }],
  "meta": { "total": 150, "limit": 20, "offset": 0, "request_id": "uuid" }
}

// エラー：
{
  "error": { "code": "ROLE_INSUFFICIENT", "message": "...", "status": 403, "request_id": "uuid" }
}
```

### ソフトデリート vs ハードデリート

voltaはメンバーにソフトデリートを使います：`DELETE /members/{id}`はデータベース行を削除するのではなく`membership.is_active = false`を設定します。これにより監査履歴が保持され、再有効化も可能です。メンバーレコードは存在し続けますが、アクティブなクエリからは除外されます。

```java
// SqlStore.java DELETE /members/{id}の効果：
// UPDATE tenant_members SET is_active = false WHERE id = ?
// さらに：このテナントのこのユーザーのすべてのセッションを無効化
```

---

## よくある間違いと攻撃

### 間違い1：一貫性のないHTTPメソッド

`DELETE /members/{id}`の代わりに`POST /members/delete/{id}`を使う。CRUDからHTTPへのマッピングが壊れ、API利用者を混乱させます。voltaは標準的なREST規約に従います。

### 間違い2：Read（一覧）のページネーションなし

1回のレスポンスで10,000人のメンバー全員を返すとパフォーマンスが死にます。voltaはすべての一覧エンドポイントを`?offset=0&limit=20`（最大100）でページネーションします。

### 間違い3：監査に関連するデータのハードデリート

メンバー行を削除すると、誰がテナントにいつまでいたかの記録を失います。voltaはメンバーをソフトデリートし、監査ログのエントリは決して削除しません。

### 攻撃：列挙による大量削除

メンバーをDELETEできる攻撃者がすべてのメンバーIDを反復するかもしれません。voltaの[ガード](guard.ja.md)がこれを防ぎます：最後のOWNERは削除できず、自分自身は削除できず、すべての削除は[監査ログ](audit-log.ja.md)に記録されます。

---

## さらに学ぶために

- [internal-api.md](internal-api.md) -- CRUD操作を公開するAPI。
- [rbac.md](rbac.md) -- 各CRUD操作のロール要件。
- [membership.md](membership.md) -- CRUDが操作するリソース。
- [invitation-flow.md](invitation-flow.md) -- メンバーの作成方法（直接POSTの代わり）。
- [audit-log.md](audit-log.md) -- すべてのCRUD操作がログに記録される。
- [pagination.md](pagination.md) -- 一覧（Read）操作のページネーション方法。
