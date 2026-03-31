# Pagination

[日本語版はこちら](pagination.ja.md)

---

## What is it?

Pagination is the practice of returning large lists of data in small chunks (pages) instead of all at once. Just like a book splits content into pages, an API splits results into manageable pieces.

Without pagination, a request for "all users" could return millions of rows, overwhelming the client, the network, and the database.

---

## Why does it matter?

**Performance:** Returning 10,000 rows when the user only sees 20 wastes bandwidth, memory, and database time.

**Security:** An unbounded query is a denial-of-service vector. An attacker can request `?limit=999999999` and crash your server.

**UX:** Users can't meaningfully browse thousands of items at once. Pagination gives them a digestible view with "next/previous" navigation.

---

## A simple example

### Offset/limit (what volta uses)

```
GET /api/v1/tenants/abc/members?offset=0&limit=20   -> items 1-20
GET /api/v1/tenants/abc/members?offset=20&limit=20  -> items 21-40
GET /api/v1/tenants/abc/members?offset=40&limit=20  -> items 41-60
```

**Pros:** Simple to understand and implement. Works for any SQL database.
**Cons:** Slow for deep pages (offset=100000 still scans 100,000 rows). Results can shift if data is added/deleted between pages.

### Cursor-based (the alternative)

```
GET /api/v1/items?limit=20                           -> items 1-20, cursor="abc"
GET /api/v1/items?limit=20&after=abc                 -> items 21-40, cursor="def"
```

**Pros:** Fast regardless of depth. Stable results.
**Cons:** More complex. Can't jump to "page 50" directly.

### When to use which?

| Scenario | Best approach |
|----------|-------------|
| Admin dashboards, small datasets | Offset/limit |
| Infinite scroll, large datasets | Cursor-based |
| Real-time feeds (chat, events) | Cursor-based |

---

## In volta-auth-proxy

volta uses **offset/limit pagination** for all list endpoints. The implementation enforces a maximum limit to prevent abuse:

```java
private static int parseLimit(String limitRaw) {
    if (limitRaw == null) {
        return 20;  // Default: 20 items
    }
    int value = Integer.parseInt(limitRaw);
    return Math.min(100, Math.max(1, value));  // Clamped to 1-100
}

private static int parseOffset(String offsetRaw) {
    if (offsetRaw == null) {
        return 0;
    }
    return Math.max(0, Integer.parseInt(offsetRaw));
}
```

Key design decisions:
- **Default limit: 20.** A reasonable page size for most UIs.
- **Max limit: 100.** Prevents clients from requesting enormous result sets.
- **Min limit: 1.** Prevents zero or negative limits.
- **Offset floor: 0.** Prevents negative offsets.

The response includes pagination metadata so clients know where they are:

```json
{
  "items": [...],
  "offset": 20,
  "limit": 20
}
```

For volta's current scale (admin dashboards, tenant member lists), offset/limit is the right choice. Cursor-based pagination would be appropriate if volta adds features like audit log streaming or activity feeds.

---

## See also

- [api-versioning.md](api-versioning.md) -- How pagination parameters are part of the API contract
- [content-type.md](content-type.md) -- The JSON format of paginated responses
