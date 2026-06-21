@param String title

@param java.util.Map<String, String> inviteContext
@param java.util.List<org.unlaxer.infra.volta.IdpProvider> providers
@param String baseParams
@param boolean isSwitchAccount = false
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

    <!-- Magic Link: メールアドレスだけでログイン (初回はアカウント自動作成) -->
    <form id="magic-link-form" onsubmit="sendMagicLink(event)">
        <label class="muted" for="magic-email">${msg.get("login.email_label")}</label>
        <div style="display:flex;gap:0.5em;flex-wrap:wrap;margin-top:0.4em">
            <input id="magic-email" type="email" required autocomplete="email"
                   placeholder="${msg.get("login.email_placeholder")}"
                   style="flex:1;min-width:200px;min-height:44px;padding:0 0.75em;border:1px solid var(--border);border-radius:10px">
            <button class="button" type="submit">${msg.get("login.magic_link_button")}</button>
        </div>
    </form>
    <div id="magic-link-result"
         data-sent="${msg.get("login.magic_link_sent")}"
         data-dev="${msg.get("login.magic_link_dev")}"
         style="display:none;margin-top:0.75em;padding:0.75em;border-radius:10px;background:#f0fdf4;border:1px solid #86efac"></div>

    <div class="separator"><span>${msg.get("common.or")}</span></div>

    <!-- Passkey: 事前に /settings/security で登録済みの端末でログイン -->
    <button class="button" id="passkey-login" onclick="startPasskeyLogin()">${msg.get("login.passkey_button")}</button>

    @if(!providers.isEmpty())
        <div class="separator"><span>${msg.get("common.or")}</span></div>
        @for(org.unlaxer.infra.volta.IdpProvider p : providers)
            <a class="button button-${p.id().toLowerCase()}"
               href="/login?start=1&provider=${p.id()}${baseParams}">${p.label()} ${msg.get("login.login_with")}</a>
        @endfor
    @endif

    <p class="muted" style="font-size:0.85em;margin-top:1.5em">${msg.get("login.register_passkey_hint")}</p>
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
async function sendMagicLink(e) {
    e.preventDefault();
    var email = document.getElementById('magic-email').value;
    var result = document.getElementById('magic-link-result');
    try {
        // 同一オリジン POST なので Origin ヘッダが付き、CSRF チェックは exempt される。
        var res = await fetch('/auth/magic-link/send', {
            method: 'POST', credentials: 'include',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ email: email })
        });
        var data = await res.json();
        if (!res.ok) {
            result.style.background = '#fef2f2'; result.style.borderColor = '#fca5a5';
            result.textContent = (data.error && data.error.message) || 'error';
            result.style.display = 'block';
            return;
        }
        result.style.background = '#f0fdf4'; result.style.borderColor = '#86efac';
        if (data.link) {
            // DEV_MODE: メールを送らずレスポンスに link が返る → そのまま踏める
            var devLabel = document.createElement('strong');
            devLabel.textContent = result.dataset.dev;
            var a = document.createElement('a');
            a.href = data.link; a.textContent = data.link;
            result.replaceChildren(devLabel, document.createElement('br'), a);
        } else {
            result.textContent = result.dataset.sent;
        }
        result.style.display = 'block';
    } catch (err) {
        result.style.background = '#fef2f2'; result.style.borderColor = '#fca5a5';
        result.textContent = 'error: ' + err.message;
        result.style.display = 'block';
    }
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
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({
                flow_id: options.flow_id,
                id: b64url(credential.rawId),
                response: {
                    clientDataJSON: b64url(credential.response.clientDataJSON),
                    authenticatorData: b64url(credential.response.authenticatorData),
                    signature: b64url(credential.response.signature),
                    userHandle: credential.response.userHandle ? b64url(credential.response.userHandle) : null
                }
            })
        });
        var resultText = await finishRes.text();
        var result;
        try { result = JSON.parse(resultText); } catch(pe) {
            alert('Server error ' + finishRes.status + ': ' + resultText.substring(0, 200));
            return;
        }
        if (result.redirect_to) { window.location.href = result.redirect_to; return; }
        if (result.ok) { window.location.href = '/console/'; return; }
        alert(result.error?.message || 'Passkey login failed (status ' + finishRes.status + ')');
    } catch (e) {
        console.error('Passkey login error:', e);
        if (e.name !== 'NotAllowedError') alert('Passkey error: ' + e.message);
    }
}
</script>
</body>
</html>
