# Handoff: ページネーション + 検索 + ソート (100+ ユーザー対応)

> Date: 2026-04-11
> From: volta-platform
> To: volta-auth-proxy implementation session

## Goal

全 admin API にページネーション・検索・ソートを追加。100+ ユーザー規模で使えるようにする。

## 対象 API

| API | 現状 | 改善 |
|-----|------|------|
| GET /api/v1/admin/users | 全件返却 | ?page=1&size=50&sort=created_at&q=検索語 |
| GET /api/v1/admin/sessions | 全件返却 | ?page=1&size=50&user_id=フィルタ |
| GET /api/v1/admin/audit-log | 全件返却 | ?page=1&size=50&from=日時&to=日時&event=タイプ |
| GET /api/v1/tenants/{id}/members | 全件返却 | ?page=1&size=50 |
| GET /api/v1/admin/invitations | 全件返却 | ?status=pending&page=1&size=50 |

## 統一レスポンスフォーマット

```json
{
  "items": [...],
  "total": 1234,
  "page": 1,
  "size": 50,
  "pages": 25
}
```

## SQL パターン

```sql
-- ユーザー検索 + ページネーション
SELECT *, COUNT(*) OVER() AS total_count
FROM users
WHERE tenant_id = ?
  AND (email ILIKE '%' || ? || '%' OR display_name ILIKE '%' || ? || '%')
ORDER BY created_at DESC
LIMIT ? OFFSET ?
```

## DB インデックス (Flyway migration)

```sql
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_tenant_created ON users(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_created ON sessions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_event ON audit_log(event_type, created_at DESC);
```

## Java 実装パターン

```java
// SqlStore に追加するメソッド
record PageRequest(int page, int size, String sort, String query) {
    int offset() { return (page - 1) * size; }
}

record PageResponse<T>(List<T> items, long total, int page, int size) {
    int pages() { return (int) Math.ceil((double) total / size); }
}

PageResponse<User> findUsers(UUID tenantId, PageRequest req) {
    String sql = """
        SELECT *, COUNT(*) OVER() AS total_count FROM users
        WHERE tenant_id = ?
        AND (? IS NULL OR email ILIKE '%' || ? || '%' OR display_name ILIKE '%' || ? || '%')
        ORDER BY """ + sanitizeSort(req.sort()) + """
        LIMIT ? OFFSET ?
        """;
    // ...
}
```

## volta-auth-console UI (React)

volta-auth-console は別リポ。API ができたら対応：
- テーブルヘッダークリックでソート
- 検索バー (debounce 300ms)
- ページネーション UI (< 1 2 3 ... >)

## 実装順序

1. Flyway migration: インデックス追加
2. SqlStore: PageRequest/PageResponse + findUsers, findSessions, findAuditLog
3. API routes: クエリパラメータ対応
4. テスト: ページネーションの boundary test
