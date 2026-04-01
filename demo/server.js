'use strict';

const express = require('express');
const QRCode  = require('qrcode');

const app  = express();
const PORT = process.env.PORT || 3000;

// Providers displayed on the re-auth panel.
// Can be overridden with DEMO_PROVIDERS=GOOGLE,GITHUB env var.
const PROVIDERS = (process.env.DEMO_PROVIDERS || 'GOOGLE,GITHUB,MICROSOFT')
  .split(',').map(s => s.trim().toUpperCase()).filter(Boolean);

const PROVIDER_META = {
  GOOGLE:    { label: 'Google',    icon: '🟦', class: 'google'    },
  GITHUB:    { label: 'GitHub',    icon: '⬛', class: 'github'    },
  MICROSOFT: { label: 'Microsoft', icon: '🟧', class: 'microsoft' },
};

// ---------------------------------------------------------------------------
// QR code endpoint (used by TOTP setup UI)
// ---------------------------------------------------------------------------
app.get('/demo/qr', async (req, res) => {
  const data = req.query.data;
  if (!data) return res.status(400).end();
  try {
    const svg = await QRCode.toString(data, { type: 'svg', width: 220, margin: 2 });
    res.setHeader('Content-Type', 'image/svg+xml');
    res.send(svg);
  } catch {
    res.status(500).end();
  }
});

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------
app.get('/', (req, res) => {
  const h = req.headers;

  // Headers injected by Traefik from volta's /auth/verify response
  const userId      = h['x-volta-user-id']      || '';
  const email       = h['x-volta-email']        || '';
  const tenantId    = h['x-volta-tenant-id']    || '';
  const tenantSlug  = h['x-volta-tenant-slug']  || '';
  const roles       = h['x-volta-roles']        || '';
  const displayName = h['x-volta-display-name'] || '';
  const jwt         = h['x-volta-jwt']          || '';

  // Current provider is encoded in the JWT sub claim prefix (google:/github:/microsoft:)
  // or readable from the session. For the demo we parse it from sub if available.
  const currentProvider = detectProvider(jwt);

  const voltaHeaders = Object.entries(h)
    .filter(([k]) => k.startsWith('x-volta-') && k !== 'x-volta-jwt')
    .map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`)
    .join('') || '<tr><td colspan="2" style="color:#aaa">（なし）</td></tr>';

  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.send(html({ userId, email, tenantId, tenantSlug, roles, displayName,
                  jwt, currentProvider, voltaHeaders }));
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** Best-effort: peek at JWT sub prefix (no verification — demo only). */
function detectProvider(jwt) {
  try {
    const payload = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString());
    const sub = payload.sub || '';
    if (sub.startsWith('github:'))    return 'GITHUB';
    if (sub.startsWith('microsoft:')) return 'MICROSOFT';
    if (sub.startsWith('google:'))    return 'GOOGLE';
  } catch { /* ignore */ }
  return '';
}

// ---------------------------------------------------------------------------
// HTML template
// ---------------------------------------------------------------------------
function html({ userId, email, tenantId, tenantSlug, roles, displayName,
                jwt, currentProvider, voltaHeaders }) {

  const providerCards = PROVIDERS.map(p => {
    const m = PROVIDER_META[p] || { label: p, icon: '🔑', class: p.toLowerCase() };
    const isCurrent = p === currentProvider;
    return `
    <label class="idp-card${isCurrent ? ' idp-card--current' : ''}">
      <input type="radio" name="provider" value="${p}" ${isCurrent ? 'checked' : ''}>
      <span class="idp-icon">${m.icon}</span>
      <span class="idp-name">${m.label}</span>
      ${isCurrent ? '<span class="badge-current">現在</span>' : ''}
    </label>`;
  }).join('');

  return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>volta demo</title>
<style>
:root {
  --bg: #f2f2f7;
  --card: #fff;
  --border: #e5e5ea;
  --text: #1c1c1e;
  --muted: #8e8e93;
  --accent: #007aff;
  --green: #34c759;
  --red: #ff3b30;
  --radius: 16px;
  --mono: 'SF Mono', 'Fira Code', monospace;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
       background: var(--bg); color: var(--text); min-height: 100vh; }

/* Topbar */
.topbar { background: var(--text); color: var(--bg);
          padding: 12px 24px; display: flex; align-items: center; gap: 10px; }
.topbar-logo { font-size: 17px; font-weight: 700; letter-spacing: -.3px; }
.topbar-tag  { background: var(--accent); color: #fff; font-size: 11px;
               padding: 2px 8px; border-radius: 20px; font-weight: 600; }

/* Layout */
.page { max-width: 680px; margin: 32px auto; padding: 0 16px;
        display: flex; flex-direction: column; gap: 16px; }

/* Card */
.card { background: var(--card); border-radius: var(--radius); padding: 24px;
        box-shadow: 0 1px 2px rgba(0,0,0,.04), 0 4px 12px rgba(0,0,0,.06); }
.card-label { font-size: 12px; font-weight: 600; color: var(--muted);
              text-transform: uppercase; letter-spacing: .5px; margin-bottom: 16px; }

/* Info grid */
.info-grid { display: grid; grid-template-columns: 130px 1fr; row-gap: 8px; }
.info-key   { font-size: 14px; color: var(--muted); align-self: center; }
.info-val   { font-size: 14px; font-weight: 500; font-family: var(--mono); word-break: break-all; }
.badge { display: inline-block; padding: 2px 10px; border-radius: 20px;
         font-size: 12px; font-weight: 600; font-family: sans-serif; }
.badge-role   { background: #e8f4fd; color: #0071bc; }
.badge-google { background: #e6f4ea; color: #137333; }
.badge-github { background: #f0e6ff; color: #6b21a8; }
.badge-microsoft { background: #e8f0fe; color: #1a73e8; }
.badge-current { background: var(--border); color: var(--muted);
                 font-size: 11px; padding: 2px 8px; border-radius: 20px; margin-left: auto; }

/* Auth status dot */
.status-dot { width: 8px; height: 8px; border-radius: 50%;
              display: inline-block; margin-right: 6px; }
.status-dot--ok  { background: var(--green); }
.status-dot--err { background: var(--red); }

/* IdP selector */
.idp-list { display: flex; flex-direction: column; gap: 8px; margin-bottom: 20px; }
.idp-card { display: flex; align-items: center; gap: 12px; padding: 14px 16px;
            border: 2px solid var(--border); border-radius: 12px; cursor: pointer;
            transition: border-color .12s, background .12s; }
.idp-card:has(input:checked),
.idp-card--current:has(input:checked) { border-color: var(--accent); background: #f0f6ff; }
.idp-card input[type=radio] { width: 18px; height: 18px; accent-color: var(--accent); }
.idp-icon { font-size: 20px; }
.idp-name { flex: 1; font-size: 15px; font-weight: 500; }

/* 2FA section */
.mfa-status { display: flex; align-items: center; gap: 8px; font-size: 14px; margin-bottom: 16px; }
.mfa-enabled  { color: var(--green); font-weight: 600; }
.mfa-disabled { color: var(--muted); }
.qr-wrapper { margin: 16px 0; text-align: center; }
.qr-wrapper img { border: 1px solid var(--border); border-radius: 12px; padding: 8px; }
.code-input { display: flex; gap: 8px; margin-top: 8px; }
.code-input input { flex: 1; padding: 10px 14px; border: 1.5px solid var(--border);
                    border-radius: 10px; font-size: 20px; letter-spacing: 4px;
                    text-align: center; font-family: var(--mono); outline: none; }
.code-input input:focus { border-color: var(--accent); }

/* Buttons */
.btn { display: inline-flex; align-items: center; justify-content: center;
       padding: 10px 20px; border: none; border-radius: 10px; font-size: 15px;
       font-weight: 600; cursor: pointer; transition: opacity .1s; }
.btn:disabled { opacity: .4; cursor: not-allowed; }
.btn-primary { background: var(--accent); color: #fff; }
.btn-primary:hover:not(:disabled) { opacity: .88; }
.btn-danger  { background: var(--red); color: #fff; }
.btn-danger:hover:not(:disabled) { opacity: .88; }
.btn-full    { width: 100%; }
.btn-sm      { padding: 8px 16px; font-size: 13px; }

/* Misc */
.msg { font-size: 13px; margin-top: 8px; min-height: 18px; }
.msg-ok  { color: var(--green); }
.msg-err { color: var(--red); }
details summary { cursor: pointer; font-size: 14px; color: var(--muted); padding: 4px 0; }
details table  { width: 100%; margin-top: 12px; border-collapse: collapse; font-size: 13px; }
details td     { padding: 6px 8px; border-bottom: 1px solid var(--border); font-family: var(--mono); }
details td:first-child { color: var(--muted); width: 260px; }
</style>
</head>
<body>

<div class="topbar">
  <span class="topbar-logo">volta-auth-proxy</span>
  <span class="topbar-tag">demo</span>
</div>

<div class="page">

  <!-- ① 認証状態 -->
  <div class="card">
    <div class="card-label">認証状態</div>
    <div class="info-grid">
      <span class="info-key">状態</span>
      <span class="info-val">
        <span class="status-dot ${userId ? 'status-dot--ok' : 'status-dot--err'}"></span>
        ${userId ? '認証済み' : '未認証'}
      </span>
      <span class="info-key">Email</span>
      <span class="info-val">${esc(email) || '<span style="color:var(--muted)">—</span>'}</span>
      <span class="info-key">Display name</span>
      <span class="info-val">${esc(displayName) || '<span style="color:var(--muted)">—</span>'}</span>
      <span class="info-key">User ID</span>
      <span class="info-val" style="font-size:12px">${esc(userId) || '<span style="color:var(--muted)">—</span>'}</span>
      <span class="info-key">Tenant</span>
      <span class="info-val">${esc(tenantSlug || tenantId) || '<span style="color:var(--muted)">—</span>'}</span>
      <span class="info-key">Roles</span>
      <span class="info-val">
        ${roles.split(',').filter(Boolean).map(r =>
          `<span class="badge badge-role">${esc(r)}</span>`).join(' ')
          || '<span style="color:var(--muted)">—</span>'}
      </span>
      <span class="info-key">Provider</span>
      <span class="info-val">
        ${currentProvider
          ? `<span class="badge badge-${currentProvider.toLowerCase()}">${esc(currentProvider)}</span>`
          : '<span style="color:var(--muted)">—</span>'}
      </span>
    </div>
  </div>

  <!-- ② IdP 切り替え -->
  <div class="card">
    <div class="card-label">IdP 切り替え</div>
    <p style="font-size:14px;color:var(--muted);margin-bottom:16px">
      再認証に使う IdP を選んで Apply を押してください。
      現在のセッションをログアウトし、選択した IdP でのログインが始まります。
    </p>
    <div class="idp-list">
      ${providerCards}
    </div>
    <button class="btn btn-primary btn-full" id="btn-apply" onclick="applyIdp()">
      Apply（ログアウトして再認証）
    </button>
    <p class="msg" id="idp-msg"></p>
  </div>

  <!-- ③ 2FA / TOTP -->
  <div class="card">
    <div class="card-label">二要素認証（TOTP）</div>
    <div class="mfa-status" id="mfa-status-row">
      <span id="mfa-status-text" class="mfa-disabled">読み込み中…</span>
    </div>

    <!-- Setup section (hidden until "設定する" is clicked) -->
    <div id="mfa-setup-section" style="display:none">
      <p style="font-size:14px;color:var(--muted);margin-bottom:12px">
        Google Authenticator または互換アプリで以下の QR コードをスキャンしてください。
      </p>
      <div class="qr-wrapper">
        <img id="qr-img" src="" alt="QR code" width="220" height="220">
      </div>
      <p style="font-size:13px;color:var(--muted);margin-bottom:8px">
        スキャン後、アプリに表示される 6 桁のコードを入力して確認:
      </p>
      <div class="code-input">
        <input type="text" id="totp-verify-input" maxlength="6"
               inputmode="numeric" pattern="[0-9]{6}" placeholder="000000">
        <button class="btn btn-primary btn-sm" onclick="verifyTotp()">確認</button>
      </div>
      <p class="msg" id="totp-verify-msg"></p>
    </div>

    <!-- Actions -->
    <div id="mfa-actions" style="display:flex;gap:8px;margin-top:12px">
      <button class="btn btn-primary btn-sm" id="btn-setup-totp"
              onclick="startTotpSetup()" style="display:none">
        2FA を設定する（Google Authenticator）
      </button>
      <button class="btn btn-danger btn-sm" id="btn-disable-totp"
              onclick="disableTotp()" style="display:none">
        2FA を無効化
      </button>
    </div>
    <p class="msg" id="mfa-msg"></p>
  </div>

  <!-- ④ Raw headers -->
  <div class="card">
    <details>
      <summary>X-Volta-* ヘッダ（raw）</summary>
      <table>${voltaHeaders}</table>
    </details>
  </div>

</div>

<script>
const USER_ID = ${JSON.stringify(userId)};
const JWT     = ${JSON.stringify(jwt)};

// ---------------------------------------------------------------------------
// IdP 切り替え
// ---------------------------------------------------------------------------
async function applyIdp() {
  const sel = document.querySelector('input[name="provider"]:checked');
  const provider = sel ? sel.value : '';
  setMsg('idp-msg', '', '');
  document.getElementById('btn-apply').disabled = true;

  try {
    await fetch('/auth/logout', {
      method: 'POST',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'include',
    });
  } catch (_) { /* session may already be gone */ }

  window.location.href = '/login' + (provider ? '?provider=' + encodeURIComponent(provider) : '');
}

// ---------------------------------------------------------------------------
// 2FA / TOTP
// ---------------------------------------------------------------------------
async function loadMfaStatus() {
  if (!USER_ID) {
    setMfaUi(false);
    return;
  }
  try {
    const res = await fetch('/api/v1/users/' + USER_ID + '/mfa/totp/status', {
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'include',
    });
    if (res.ok) {
      const data = await res.json();
      setMfaUi(data.enabled === true);
    } else {
      // endpoint may not exist yet — treat as disabled
      setMfaUi(false);
    }
  } catch {
    setMfaUi(false);
  }
}

function setMfaUi(enabled) {
  const txt = document.getElementById('mfa-status-text');
  const btnSetup   = document.getElementById('btn-setup-totp');
  const btnDisable = document.getElementById('btn-disable-totp');
  if (enabled) {
    txt.textContent = '✓ 有効';
    txt.className = 'mfa-enabled';
    btnSetup.style.display   = 'none';
    btnDisable.style.display = 'inline-flex';
  } else {
    txt.textContent = '無効';
    txt.className = 'mfa-disabled';
    btnSetup.style.display   = USER_ID ? 'inline-flex' : 'none';
    btnDisable.style.display = 'none';
    document.getElementById('mfa-setup-section').style.display = 'none';
  }
}

async function startTotpSetup() {
  if (!USER_ID) return;
  setMsg('mfa-msg', '', '');
  try {
    const res = await fetch('/api/v1/users/' + USER_ID + '/mfa/totp/setup', {
      method: 'POST',
      headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
      credentials: 'include',
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      setMsg('mfa-msg', err.message || 'エラーが発生しました', 'err');
      return;
    }
    const data = await res.json();
    const otpauth = data.otpauth || data.uri || '';
    if (otpauth) {
      document.getElementById('qr-img').src = '/demo/qr?data=' + encodeURIComponent(otpauth);
    }
    document.getElementById('mfa-setup-section').style.display = 'block';
    document.getElementById('btn-setup-totp').style.display = 'none';
  } catch {
    setMsg('mfa-msg', 'ネットワークエラー', 'err');
  }
}

async function verifyTotp() {
  const code = document.getElementById('totp-verify-input').value.trim();
  if (code.length !== 6) {
    setMsg('totp-verify-msg', '6桁のコードを入力してください', 'err');
    return;
  }
  setMsg('totp-verify-msg', '', '');
  try {
    const res = await fetch('/api/v1/users/' + USER_ID + '/mfa/totp/verify', {
      method: 'POST',
      headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ code }),
    });
    if (res.ok) {
      setMsg('totp-verify-msg', '✓ 2FA が有効になりました', 'ok');
      document.getElementById('mfa-setup-section').style.display = 'none';
      setMfaUi(true);
    } else {
      const err = await res.json().catch(() => ({}));
      setMsg('totp-verify-msg', err.message || 'コードが正しくありません', 'err');
    }
  } catch {
    setMsg('totp-verify-msg', 'ネットワークエラー', 'err');
  }
}

async function disableTotp() {
  if (!confirm('2FA を無効化しますか？')) return;
  setMsg('mfa-msg', '', '');
  try {
    const res = await fetch('/api/v1/users/' + USER_ID + '/mfa/totp', {
      method: 'DELETE',
      headers: { 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'include',
    });
    if (res.ok || res.status === 404) {
      setMfaUi(false);
      setMsg('mfa-msg', '2FA を無効化しました', 'ok');
    } else {
      setMsg('mfa-msg', '無効化に失敗しました', 'err');
    }
  } catch {
    setMsg('mfa-msg', 'ネットワークエラー', 'err');
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function setMsg(id, text, type) {
  const el = document.getElementById(id);
  if (!el) return;
  el.textContent = text;
  el.className = 'msg' + (type ? ' msg-' + type : '');
}

// Load 2FA status on page load
loadMfaStatus();
</script>
</body>
</html>`;
}

app.listen(PORT, () => console.log('[demo] listening on :' + PORT));
