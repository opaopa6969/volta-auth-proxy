@param String title
@param String return_to
!{var msg = org.unlaxer.infra.volta.Messages.current();}
<!doctype html>
<html lang="${msg.locale()}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Cache-Control" content="no-store">
    <title>${title}</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container">
    <h1>${msg.get("mfa.title")}</h1>
    <p>${msg.get("mfa.prompt")}</p>

    <form id="mfa-form" onsubmit="return submitMfa(event)">
        <input type="text" id="mfa-code" name="code"
               inputmode="numeric" pattern="[0-9]{6}" maxlength="6"
               placeholder="123456" autocomplete="one-time-code" autofocus
               class="input-code"
               required />
        <p id="mfa-error" class="error" style="display:none"></p>
        <button type="submit" class="button" id="mfa-submit">${msg.get("mfa.title")}</button>
    </form>

    <details style="margin-top:2rem">
        <summary class="muted">リカバリーコードを使う</summary>
        <form id="recovery-form" onsubmit="return submitRecovery(event)" style="margin-top:1rem">
            <input type="text" id="recovery-code" name="recovery_code"
                   placeholder="XXXX-XXXX" autocomplete="off"
                   class="input-code" />
            <button type="submit" class="button">リカバリーコードで認証</button>
        </form>
    </details>
</main>
<script>
async function submitMfa(e) {
    e.preventDefault();
    var code = document.getElementById('mfa-code').value.trim();
    if (!code) return false;
    await verify({ code: parseInt(code, 10) });
    return false;
}
async function submitRecovery(e) {
    e.preventDefault();
    var rc = document.getElementById('recovery-code').value.trim();
    if (!rc) return false;
    await verify({ recovery_code: rc });
    return false;
}
async function verify(body) {
    var errEl = document.getElementById('mfa-error');
    var btn = document.getElementById('mfa-submit');
    errEl.style.display = 'none';
    btn.disabled = true;
    btn.textContent = '...';
    try {
        var res = await fetch('/auth/mfa/verify', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify(body)
        });
        var text = await res.text();
        var data;
        try { data = JSON.parse(text); } catch(e) {
            errEl.textContent = 'Server error: ' + res.status + ' — ' + text.substring(0, 100);
            errEl.style.display = 'block';
            return;
        }
        if (data.ok) {
            window.location.href = data.redirect_to || '/';
        } else if (data.redirect_to) {
            window.location.href = data.redirect_to;
        } else {
            errEl.textContent = data.error?.message || '${msg.get("mfa.invalid_code")}';
            errEl.style.display = 'block';
        }
    } catch (err) {
        errEl.textContent = err.message;
        errEl.style.display = 'block';
    } finally {
        btn.disabled = false;
        btn.textContent = '${msg.get("mfa.title")}';
    }
}
</script>
</body>
</html>
