@param String title
@param boolean success
@param String message
!{var msg = org.unlaxer.infra.volta.Messages.current();}
<!doctype html>
<html lang="${msg.locale()}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container" style="max-width:480px;text-align:center;padding-top:3em;">
    @if(success)
        <h1 style="color:#4a9">&#x2705; ${msg.get("revoke.success.title")}</h1>
    @else
        <h1 style="color:#c44">&#x26A0;&#xFE0F; ${msg.get("revoke.fail.title")}</h1>
    @endif
    <p class="muted">${message == null ? "" : message}</p>
    <p style="margin-top:2em;">
        <a href="/settings/devices" class="button" style="min-height:44px;">
            ${msg.get("revoke.cta.devices")}
        </a>
        <a href="/settings/sessions" class="button" style="min-height:44px;margin-left:1em;">
            ${msg.get("revoke.cta.sessions")}
        </a>
    </p>
</main>
</body>
</html>
