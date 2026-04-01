@param String title
@param java.util.Map<String, String> inviteContext
@param String startUrl
@param boolean googleEnabled
@param boolean githubEnabled
@param boolean microsoftEnabled
@param String baseParams
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
    <p>アカウントでログインしてください。</p>
    @if(googleEnabled)
        <a class="button button-google" href="/login?start=1&provider=GOOGLE${baseParams}">Google でログイン</a>
    @endif
    @if(githubEnabled)
        <a class="button button-github" href="/login?start=1&provider=GITHUB${baseParams}">GitHub でログイン</a>
    @endif
    @if(microsoftEnabled)
        <a class="button button-microsoft" href="/login?start=1&provider=MICROSOFT${baseParams}">Microsoft でログイン</a>
    @endif
</main>
</body>
</html>
