@param String title
@param String userId
@param boolean mfaEnabled
@param int recoveryRemaining
@param String csrfToken
@param String flashMessage
<!doctype html>
<html lang="ja">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <link rel="stylesheet" href="/css/volta.css">
    <script src="https://cdn.jsdelivr.net/npm/qrcode@1.5.4/build/qrcode.min.js" defer></script>
</head>
<body>
<main class="container">
    <h1>Security Settings</h1>

    @if(flashMessage != null)
        <p class="flash">${flashMessage}</p>
    @endif

    <h2>Two-Factor Authentication (TOTP)</h2>

    @if(mfaEnabled)
        <p>&#x2705; MFA is <strong>enabled</strong>.</p>
        <p>Recovery codes remaining: <strong>${recoveryRemaining}</strong></p>

        <details style="margin:1em 0">
            <summary class="button button-small">Regenerate Recovery Codes</summary>
            <div id="recovery-section" style="margin-top:1em">
                <button class="button button-small" onclick="regenerateCodes()">Generate New Codes</button>
                <pre id="recovery-codes" style="display:none"></pre>
            </div>
        </details>

        <details style="margin:1em 0">
            <summary class="button button-small button-danger">Disable MFA</summary>
            <div style="margin-top:1em">
                <p>Enter your current TOTP code to disable MFA:</p>
                <form onsubmit="disableMfa(event)">
                    <input id="disable-code" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="000000" required style="min-height:44px;width:120px;">
                    <button type="submit" class="button button-danger">Disable</button>
                </form>
                <p id="disable-error" style="color:red;display:none"></p>
            </div>
        </details>
    @else
        <p>MFA is <strong>not configured</strong>.</p>

        <div id="setup-section">
            <button class="button" onclick="startSetup()">Enable MFA</button>
        </div>

        <div id="qr-section" style="display:none">
            <h3>Step 1: Scan QR Code</h3>
            <p>Scan this QR code with your authenticator app (Google Authenticator, Authy, etc.):</p>
            <canvas id="qr-canvas" style="margin:1em 0"></canvas>
            <details>
                <summary>Can't scan? Enter manually</summary>
                <code id="manual-secret" style="word-break:break-all"></code>
            </details>

            <h3>Step 2: Enter Verification Code</h3>
            <p>Enter the 6-digit code from your authenticator app:</p>
            <form onsubmit="verifySetup(event)">
                <input id="verify-code" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="000000" required style="min-height:44px;width:120px;">
                <button type="submit" class="button">Verify & Enable</button>
            </form>
            <p id="setup-error" style="color:red;display:none"></p>
        </div>

        <div id="recovery-result" style="display:none">
            <h3>Step 3: Save Recovery Codes</h3>
            <p><strong>Save these codes in a safe place.</strong> You can use them to log in if you lose your authenticator device.</p>
            <pre id="setup-recovery-codes"></pre>
            <button class="button" onclick="location.reload()">Done</button>
        </div>
    @endif
</main>

<script>
const userId = '${userId}';
const csrf = '${csrfToken}';
let setupSecret = null;

async function startSetup() {
    const res = await fetch('/api/v1/users/' + userId + '/mfa/totp/setup', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type':'application/json','Accept':'application/json'}
    });
    const data = await res.json();
    if (!res.ok) { alert(data.error?.message || 'Setup failed'); return; }
    setupSecret = data.secret;
    document.getElementById('manual-secret').textContent = data.secret;
    document.getElementById('setup-section').style.display = 'none';
    document.getElementById('qr-section').style.display = 'block';
    QRCode.toCanvas(document.getElementById('qr-canvas'), data.otpauth_url, {width:200});
}

async function verifySetup(e) {
    e.preventDefault();
    const code = document.getElementById('verify-code').value;
    const res = await fetch('/api/v1/users/' + userId + '/mfa/totp/verify', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type':'application/json','Accept':'application/json'},
        body: JSON.stringify({code: parseInt(code)})
    });
    const data = await res.json();
    if (!res.ok) {
        document.getElementById('setup-error').textContent = data.error?.message || 'Invalid code';
        document.getElementById('setup-error').style.display = 'block';
        return;
    }
    // Generate recovery codes
    const rcRes = await fetch('/api/v1/users/' + userId + '/mfa/recovery-codes/regenerate', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type':'application/json','Accept':'application/json'}
    });
    const rcData = await rcRes.json();
    document.getElementById('qr-section').style.display = 'none';
    document.getElementById('recovery-result').style.display = 'block';
    document.getElementById('setup-recovery-codes').textContent = (rcData.codes || []).join('\n');
}

async function disableMfa(e) {
    e.preventDefault();
    const code = document.getElementById('disable-code').value;
    const res = await fetch('/api/v1/users/' + userId + '/mfa/totp', {
        method: 'DELETE', credentials: 'include',
        headers: {'Content-Type':'application/json','Accept':'application/json'},
        body: JSON.stringify({code: parseInt(code)})
    });
    const data = await res.json();
    if (!res.ok) {
        document.getElementById('disable-error').textContent = data.error?.message || 'Failed';
        document.getElementById('disable-error').style.display = 'block';
        return;
    }
    location.reload();
}

async function regenerateCodes() {
    const res = await fetch('/api/v1/users/' + userId + '/mfa/recovery-codes/regenerate', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type':'application/json','Accept':'application/json'}
    });
    const data = await res.json();
    const el = document.getElementById('recovery-codes');
    el.textContent = (data.codes || []).join('\n');
    el.style.display = 'block';
}
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
