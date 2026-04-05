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
    <button class="button" id="passkey-login" onclick="startPasskeyLogin()">🔑 パスキーでログイン</button>
    <div class="separator"><span>または</span></div>
    @for(com.volta.authproxy.IdpProvider p : providers)
        <a class="button button-${p.id().toLowerCase()}"
           href="/login?start=1&provider=${p.id()}${baseParams}">${p.label()} でログイン</a>
    @endfor
</main>
<script>
async function startPasskeyLogin() {
    try {
        const startRes = await fetch('/auth/passkey/start', { method: 'POST', credentials: 'include' });
        const options = await startRes.json();
        const credential = await navigator.credentials.get({
            publicKey: {
                challenge: Uint8Array.from(atob(options.challenge.replace(/-/g,'+').replace(/_/g,'/')), c => c.charCodeAt(0)),
                rpId: options.rpId,
                timeout: options.timeout,
                userVerification: options.userVerification,
            }
        });
        const finishRes = await fetch('/auth/passkey/finish', {
            method: 'POST', credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: btoa(String.fromCharCode(...new Uint8Array(credential.rawId))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,''),
                response: {
                    clientDataJSON: btoa(String.fromCharCode(...new Uint8Array(credential.response.clientDataJSON))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,''),
                    authenticatorData: btoa(String.fromCharCode(...new Uint8Array(credential.response.authenticatorData))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,''),
                    signature: btoa(String.fromCharCode(...new Uint8Array(credential.response.signature))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,''),
                    userHandle: credential.response.userHandle ? btoa(String.fromCharCode(...new Uint8Array(credential.response.userHandle))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=/g,'') : null
                }
            })
        });
        const result = await finishRes.json();
        if (result.ok) window.location.href = result.redirect_to || '/console/';
        else alert(result.error?.message || 'Passkey login failed');
    } catch (e) {
        if (e.name !== 'NotAllowedError') alert('Passkey error: ' + e.message);
    }
}
</script>
</body>
</html>
