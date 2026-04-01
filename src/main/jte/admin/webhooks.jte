@param String title
@param String tenantId
@param java.util.List<java.util.Map<String, String>> webhooks
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
    <h1>Webhook 管理</h1>
    <table class="table">
        <thead><tr><th>URL</th><th>イベント</th><th>状態</th><th>作成日時</th><th>操作</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> w : webhooks)
            <tr>
                <td>${w.get("url")}</td>
                <td>${w.get("events")}</td>
                <td>${"true".equals(w.get("active")) ? "有効" : "無効"}</td>
                <td>${w.get("createdAt")}</td>
                <td><button class="button danger webhook-remove" data-webhook-id="${w.get("id")}">無効化</button></td>
            </tr>
        @endfor
        </tbody>
    </table>

    <h2>Webhook を追加</h2>
    <form id="webhook-create-form">
        <input type="hidden" name="_csrf" value="${csrfToken}">
        <input type="url" name="endpoint_url" placeholder="https://example.com/hook" required style="min-height:44px;">
        <input type="text" name="events" placeholder="member.joined,member.removed" style="min-height:44px;">
        <button class="button" type="submit">追加</button>
    </form>
    <p id="webhook-result" class="muted"></p>
</main>
<script>
  window.__voltaWebhooks = { tenantId: "${tenantId}", csrfToken: "${csrfToken}" };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
