@param String title
@param java.util.Map<String, String> inviteContext
@param java.util.List<com.volta.authproxy.IdpProvider> providers
@param String baseParams
@param boolean isSwitchAccount = false
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
    @if(isSwitchAccount)
        <div class="alert">
            <p>招待メール宛先のアカウントでログインしてください。</p>
        </div>
    @elseif(inviteContext != null)
        <p><strong>${inviteContext.get("tenantName")}</strong> に招待されています。</p>
        <p class="muted">${inviteContext.get("inviterName")} さん / ロール: ${inviteContext.get("role")}</p>
    @endif
    <p>アカウントでログインしてください。</p>
    @for(com.volta.authproxy.IdpProvider p : providers)
        <a class="button button-${p.id().toLowerCase()}"
           href="/login?start=1&provider=${p.id()}${baseParams}">${p.label()} でログイン</a>
    @endfor
</main>
</body>
</html>
