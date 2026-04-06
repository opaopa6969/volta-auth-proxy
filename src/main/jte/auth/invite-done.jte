@param String tenantName
@param String role
@param String dashboardUrl
!{var msg = com.volta.authproxy.Messages.current();}
<!doctype html>
<html lang="${msg.locale()}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${msg.get("invite.done")}</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container">
    <h1>${msg.get("invite.done_message", tenantName)}</h1>
    <p>${msg.get("invite.role", role)}</p>
    <a class="button" href="${dashboardUrl}">${msg.get("invite.go_dashboard")}</a>
    <p style="margin-top:1em"><a href="/settings/security" class="muted">${msg.get("invite.setup_passkey")}</a></p>
</main>
</body>
</html>
