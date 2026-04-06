@param String title
@param java.util.List<java.util.Map<String, Object>> tenants
@param String returnTo
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
    <h1>ワークスペースを選択</h1>
    @if(tenants.size() >= 6)
        <input id="tenant-search" type="search" placeholder="ワークスペースを検索" style="width:100%;min-height:44px;margin-bottom:12px;">
    @endif
    <div id="tenant-list" class="tenant-grid">
        @for(java.util.Map<String, Object> t : tenants)
            <button class="tenant-card" data-tenant-id="${String.valueOf(t.get("id"))}">
                <div><strong>${String.valueOf(t.get("name"))}</strong> <span class="muted">(${String.valueOf(t.get("slug"))})</span></div>
                <div class="muted">role: ${String.valueOf(t.get("role"))}</div>
                @if(Boolean.TRUE.equals(t.get("isLast")))
                    <span class="badge">前回</span>
                @endif
            </button>
        @endfor
    </div>
</main>
<script>
  window.__voltaTenantSelect = { returnTo: "${returnTo == null ? "" : returnTo}" };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
