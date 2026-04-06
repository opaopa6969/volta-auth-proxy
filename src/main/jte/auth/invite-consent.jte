@param String title
@param String code
@param String tenantName
@param String role
@param String inviterName
@param boolean isLoggedIn
@param String csrfToken
@param String inviteLoginHref
@param boolean isEmailMismatch = false
@param String currentEmail = ""
!{var msg = com.volta.authproxy.Messages.current();}
<!doctype html>
<html lang="${msg.locale()}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container">
    <h1>${msg.get("invite.title")}</h1>
    <p>${msg.get("invite.invited_by", inviterName, tenantName)}</p>
    <p>${msg.get("invite.role", role)}</p>
    @if(isEmailMismatch)
        <div class="alert">
            <p>${msg.get("invite.email_mismatch", currentEmail)}</p>
            <p>${msg.get("invite.switch_prompt")}</p>
        </div>
        <form method="post" action="/auth/switch-account">
            <input type="hidden" name="_csrf" value="${csrfToken}">
            <input type="hidden" name="return_to" value="/invite/${code}">
            <button class="button" type="submit">${msg.get("invite.switch_account")}</button>
        </form>
        <a class="muted" href="/">${msg.get("common.cancel")}</a>
    @elseif(!isLoggedIn)
        <a class="button" href="${inviteLoginHref}">${msg.get("invite.login_to_join")}</a>
    @else
        <p class="muted">${msg.get("invite.join_notice")}</p>
        <form method="post" action="/invite/${code}/accept">
            <input type="hidden" name="_csrf" value="${csrfToken}">
            <button class="button" type="submit">${msg.get("invite.join")}</button>
        </form>
        <a class="muted" href="/">${msg.get("common.cancel")}</a>
    @endif
</main>
</body>
</html>
