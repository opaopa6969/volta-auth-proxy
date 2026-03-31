@param String title
@param java.util.Map<String, String> inviteContext
@param String startUrl
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
    <h1>ログイン</h1>
    @if(inviteContext != null)
        <p><strong>${inviteContext.get("tenantName")}</strong> に招待されています。</p>
        <p class="muted">${inviteContext.get("inviterName")} さん / ロール: ${inviteContext.get("role")}</p>
    @endif
    <p>Google アカウントで認証します。</p>
    <a class="button" href="${startUrl}">Google でログイン</a>
</main>
</body>
</html>
