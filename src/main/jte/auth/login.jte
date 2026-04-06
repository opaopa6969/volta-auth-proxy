@param String title

@param java.util.Map<String, String> inviteContext
@param java.util.List<com.volta.authproxy.IdpProvider> providers
@param String baseParams
@param boolean isSwitchAccount = false
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
    <h1>${msg.get("login.title")}</h1>
    @if(isSwitchAccount)
        <div class="alert">
            <p>${msg.get("login.invite_prompt")}</p>
        </div>
    @elseif(inviteContext != null)
        <p><strong>${inviteContext.get("tenantName")}</strong> ${msg.get("login.invited_to", inviteContext.get("tenantName"))}</p>
        <p class="muted">${msg.get("login.invited_by", inviteContext.get("inviterName"), inviteContext.get("role"))}</p>
    @endif
    <p>${msg.get("login.switch_account")}</p>
    <button class="button" id="passkey-login" onclick="startPasskeyLogin()">${msg.get("login.passkey_button")}</button>
    <div class="separator"><span>${msg.get("common.or")}</span></div>
    @for(com.volta.authproxy.IdpProvider p : providers)
        <a class="button button-${p.id().toLowerCase()}"
           href="/login?start=1&provider=${p.id()}${baseParams}">${p.label()} ${msg.get("login.login_with")}</a>
    @endfor
</main>
<script>
function b64url(buf) {
    var s = btoa(String.fromCharCode.apply(null, new Uint8Array(buf)));
    return s.split('+').join('-').split('/').join('_').split('=').join('');
}
function b64urlDecode(s) {
    var b = s.split('-').join('+').split('_').join('/');
    var raw = atob(b);
    var arr = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) arr[i] = raw.charCodeAt(i);
    return arr;
}
async function startPasskeyLogin() {
    try {
        var startRes = await fetch('/auth/passkey/start', { method: 'POST', credentials: 'include', headers: {'Accept':'application/json'} });
        var options = await startRes.json();
        var credential = await navigator.credentials.get({
            publicKey: {
                challenge: b64urlDecode(options.challenge),
                rpId: options.rpId,
                timeout: options.timeout,
                userVerification: options.userVerification,
            }
        });
        var finishRes = await fetch('/auth/passkey/finish', {
            method: 'POST', credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: b64url(credential.rawId),
                response: {
                    clientDataJSON: b64url(credential.response.clientDataJSON),
                    authenticatorData: b64url(credential.response.authenticatorData),
                    signature: b64url(credential.response.signature),
                    userHandle: credential.response.userHandle ? b64url(credential.response.userHandle) : null
                }
            })
        });
        var result = await finishRes.json();
        if (result.ok) window.location.href = result.redirect_to || '/console/';
        else alert(result.error?.message || 'Passkey login failed');
    } catch (e) {
        console.error('Passkey login error:', e);
        if (e.name !== 'NotAllowedError') alert('Passkey error: ' + e.message);
    }
}
</script>
</body>
</html>
