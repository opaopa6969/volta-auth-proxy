@param String title
@param java.util.List<java.util.Map<String, String>> sessions
@param int totalActive
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
    <h1>セッション管理</h1>
    <p class="muted">アクティブセッション数: ${totalActive}</p>
    <table class="table">
        <thead><tr><th>Email</th><th>Name</th><th>デバイス</th><th>IP</th><th>最終アクセス</th><th>期限</th><th>操作</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> s : sessions)
            <tr>
                <td>${s.get("email")}</td>
                <td>${s.get("displayName")}</td>
                <td>${s.get("device")}</td>
                <td>${s.get("ip")}</td>
                <td>${s.get("lastActive")}</td>
                <td>${s.get("expiresAt")}</td>
                <td><button class="button button-small button-danger" onclick="revokeSession('${s.get("sessionId")}')">失効</button></td>
            </tr>
        @endfor
        </tbody>
    </table>
</main>
<script>
function revokeSession(id) {
    if (!confirm('このセッションを失効しますか？')) return;
    fetch('/admin/sessions/' + id, { method: 'DELETE', headers: { 'X-CSRF-Token': '${csrfToken}' } })
        .then(r => r.json())
        .then(() => location.reload());
}
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
