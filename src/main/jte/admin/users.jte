@param String title
@param java.util.List<java.util.Map<String, String>> users
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
    <h1>ユーザー一覧</h1>
    <table class="table">
        <thead><tr><th>Email</th><th>Name</th><th>Locale</th><th>Status</th><th>Created</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> u : users)
            <tr>
                <td>${u.get("email")}</td>
                <td>${u.get("displayName")}</td>
                <td>${u.get("locale")}</td>
                <td>${"true".equals(u.get("active")) ? "active" : "inactive"}</td>
                <td>${u.get("createdAt")}</td>
            </tr>
        @endfor
        </tbody>
    </table>
</main>
<script src="/js/volta.js" defer></script>
</body>
</html>
