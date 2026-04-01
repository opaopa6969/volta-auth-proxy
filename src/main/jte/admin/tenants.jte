@param String title
@param java.util.List<java.util.Map<String, String>> tenants
@param String csrfToken
<!doctype html>
<html lang="ja">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container">
    <h1>テナント管理</h1>
    <table class="table">
        <thead><tr><th>Name</th><th>Slug</th><th>Plan</th><th>Members</th><th>Status</th><th>操作</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> t : tenants)
            <tr>
                <td>${t.get("name")}</td>
                <td>${t.get("slug")}</td>
                <td>${t.get("plan")}</td>
                <td>${t.get("memberCount")}</td>
                <td>${"true".equals(t.get("active")) ? "active" : "suspended"}</td>
                <td>
                    <button class="button tenant-suspend" data-tenant-id="${t.get("id")}">停止</button>
                    <button class="button tenant-activate" data-tenant-id="${t.get("id")}">再開</button>
                </td>
            </tr>
        @endfor
        </tbody>
    </table>
</main>
<script>
  window.__voltaTenants = { csrfToken: "${csrfToken}" };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
