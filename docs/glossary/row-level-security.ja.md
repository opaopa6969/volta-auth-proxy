# 行レベルセキュリティ（RLS）

[English version](row-level-security.md)

---

## これは何？

行レベルセキュリティ（RLS）は、ユーザーが自分のテナント（ワークスペース、組織）のデータのみを閲覧・変更できるようにする技術です。アプリケーションコードがすべてのクエリに `WHERE tenant_id = ?` を忘れずに付けることに頼るのではなく、データベースレベルでルールを強制し、忘れることを不可能にします。

ビルで各階に異なるキーカードが必要な仕組みと同じです。ビルに入れたとしても、自分の階にしかアクセスできません。RLS はデータベースを同じように機能させます。

---

## なぜ重要？

マルチテナントシステムで最悪のバグは**テナント間のデータ漏洩**です。ユーザー A がユーザー B のプライベートデータを見てしまうこと。これは `WHERE tenant_id = ?` の付け忘れが1つコードレビューをすり抜けるだけで発生します。

RLS がなければ、すべての開発者がすべてのクエリでフィルターを忘れないことに頼ることになります。RLS があれば、データベース自体が境界を強制します。アプリケーションコードが `SELECT * FROM documents` を実行しても、データベースが自動的にそのテナントの行だけにフィルターします。

---

## 簡単な例

### アプリケーションレベルの強制（RLS なし）

```sql
-- 開発者は常に tenant_id を付けることを覚えていなければならない
SELECT * FROM invoices WHERE tenant_id = '7' AND status = 'unpaid';

-- 忘れると：
SELECT * FROM invoices WHERE status = 'unpaid';
-- 全テナントの未払い請求書が返ってくる！
```

### データベースレベルの強制（Postgres RLS）

```sql
-- テーブルで RLS を有効化
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;

-- ポリシーを作成
CREATE POLICY tenant_isolation ON invoices
  USING (tenant_id = current_setting('app.tenant_id')::uuid);

-- 各リクエストの開始時にテナントコンテキストを設定
SET app.tenant_id = '7';

-- このクエリは自動的にフィルターされる
SELECT * FROM invoices WHERE status = 'unpaid';
-- テナント7の未払い請求書だけが返る。保証付き
```

---

## volta-auth-proxy では

volta は現在、Postgres RLS ではなく**アプリケーションレベルの強制**を使用しています。テナントスコープのデータに触れるすべてのクエリに、明示的な `tenant_id` フィルターが含まれています：

```sql
SELECT id, user_id, tenant_id, role, is_active
FROM memberships
WHERE tenant_id = ? AND id = ?
```

さらに、volta の API 層は `enforceTenantMatch()` でテナント分離を強制し、URL パスの `tenantId` と認証済みユーザーの JWT 内の `volta_tid` が一致するかチェックします：

```java
private static void enforceTenantMatch(AuthPrincipal principal, UUID tenantId) {
    if (!principal.tenantId().equals(tenantId)) {
        throw new ApiException(403, "TENANT_ACCESS_DENIED", "Tenant access denied");
    }
}
```

これにより、Postgres RLS がなくても2層の防御（API パスチェック + クエリフィルター）が提供されます。RLS を追加すれば、将来の開発に対する3番目のセーフティネットとなります。

---

## 関連項目

- [cross-tenant-access.md](cross-tenant-access.md) -- テナント分離がなぜ重要か
- [tenant-resolution.md](tenant-resolution.md) -- volta がリクエストのテナントを判定する方法
