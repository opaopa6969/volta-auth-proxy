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
    <script src="https://cdn.jsdelivr.net/npm/qrcode/build/qrcode.min.js" defer></script>
</head>
<body>
<main class="container">
    <h1>セキュリティ設定</h1>

    @if(flashMessage != null)
        <p class="flash">${flashMessage}</p>
    @endif

    <h2>二要素認証 (TOTP)</h2>

    @if(mfaEnabled)
        <p>&#x2705; 二要素認証は<strong>有効</strong>です。</p>
        <p>リカバリーコード残数: <strong>${recoveryRemaining}</strong></p>

        <details style="margin:1em 0">
            <summary class="button button-small">リカバリーコードを再生成</summary>
            <div id="recovery-section" style="margin-top:1em">
                <button class="button button-small" onclick="regenerateCodes()">新しいコードを生成</button>
                <pre id="recovery-codes" style="display:none"></pre>
            </div>
        </details>

        <details style="margin:1em 0">
            <summary class="button button-small button-danger">二要素認証を無効にする</summary>
            <div style="margin-top:1em">
                <p>無効にするには現在の TOTP コードを入力してください:</p>
                <form onsubmit="disableMfa(event)">
                    <input id="disable-code" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="000000" required style="min-height:44px;width:120px;">
                    <button type="submit" class="button button-danger">無効にする</button>
                </form>
                <p id="disable-error" style="color:red;display:none"></p>
            </div>
        </details>
    @else
        <p>二要素認証は<strong>未設定</strong>です。</p>

        <div id="setup-section">
            <button class="button" onclick="startSetup()">二要素認証を有効にする</button>
        </div>

        <div id="qr-section" style="display:none">
            <h3>ステップ 1: QR コードをスキャン</h3>
            <p>認証アプリ（Google Authenticator, Authy 等）でこの QR コードをスキャンしてください:</p>
            <canvas id="qr-canvas" style="margin:1em 0"></canvas>
            <details>
                <summary>スキャンできない場合は手動入力</summary>
                <code id="manual-secret" style="word-break:break-all"></code>
            </details>

            <h3>ステップ 2: 確認コードを入力</h3>
            <p>認証アプリに表示された 6 桁のコードを入力してください:</p>
            <form onsubmit="verifySetup(event)">
                <input id="verify-code" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" placeholder="000000" required style="min-height:44px;width:120px;">
                <button type="submit" class="button">確認して有効にする</button>
            </form>
            <p id="setup-error" style="color:red;display:none"></p>
        </div>

        <div id="recovery-result" style="display:none">
            <h3>ステップ 3: リカバリーコードを保存</h3>
            <p><strong>これらのコードを安全な場所に保存してください。</strong>認証デバイスを紛失した場合にログインに使えます。</p>
            <pre id="setup-recovery-codes"></pre>
            <button class="button" onclick="location.reload()">保存しました</button>
        </div>
    @endif
</main>

<script>
const userId = '${userId}';

async function startSetup() {
    const res = await fetch('/api/v1/users/' + userId + '/mfa/totp/setup', {
        method: 'POST', credentials: 'include',
        headers: {'Content-Type':'application/json','Accept':'application/json'}
    });
    const data = await res.json();
    if (!res.ok) { alert(data.error?.message || 'セットアップに失敗しました'); return; }
    document.getElementById('manual-secret').textContent = data.secret;
    document.getElementById('setup-section').style.display = 'none';
    document.getElementById('qr-section').style.display = 'block';
    if (typeof QRCode !== 'undefined') {
        QRCode.toCanvas(document.getElementById('qr-canvas'), data.otpauth_url, {width:200}, function(err) {
            if (err) console.error('QR error:', err);
        });
    }
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
        document.getElementById('setup-error').textContent = data.error?.message || 'コードが無効です';
        document.getElementById('setup-error').style.display = 'block';
        return;
    }
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
        document.getElementById('disable-error').textContent = data.error?.message || '無効化に失敗しました';
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
