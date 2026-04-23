@param String title
@param String userEmail
@param java.util.List<java.util.Map<String, Object>> devices
@param String csrfToken
@param String flashMessage
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
@template.layout.navbar(currentPath = "/settings/devices", userEmail = userEmail)
<main class="container">
    <h1>${msg.get("devices.title")}</h1>

    @if(flashMessage != null)
        <p class="flash">${flashMessage}</p>
    @endif

    <p class="muted">${msg.get("devices.description")}</p>

    @if(devices.isEmpty())
        <p class="tenant-empty-help">${msg.get("devices.none")}</p>
    @else
        <table class="data-table" style="width:100%;margin-top:1em;">
            <thead>
                <tr>
                    <th>${msg.get("devices.label")}</th>
                    <th>${msg.get("devices.lastIp")}</th>
                    <th>${msg.get("devices.firstSeen")}</th>
                    <th>${msg.get("devices.lastSeen")}</th>
                    <th></th>
                </tr>
            </thead>
            <tbody>
                @for(java.util.Map<String, Object> d : devices)
                    !{String fingerprint = String.valueOf(d.get("fingerprint"));}
                    <tr data-fingerprint="${fingerprint}">
                        <td>
                            <strong>${String.valueOf(d.get("label"))}</strong>
                            <br>
                            <span class="muted" style="font-family:monospace;font-size:0.85em;">${fingerprint.length() > 20 ? fingerprint.substring(0, 20) + "…" : fingerprint}</span>
                        </td>
                        <td><span class="muted">${String.valueOf(d.get("lastIp") == null ? "" : d.get("lastIp"))}</span></td>
                        <td><span class="muted">${String.valueOf(d.get("firstSeenAt") == null ? "—" : d.get("firstSeenAt"))}</span></td>
                        <td><span class="muted">${String.valueOf(d.get("lastSeenAt") == null ? "—" : d.get("lastSeenAt"))}</span></td>
                        <td>
                            <button type="button"
                                    class="button button-small button-danger"
                                    data-remove-device="${fingerprint}"
                                    style="min-height:36px;">
                                ${msg.get("devices.remove")}
                            </button>
                        </td>
                    </tr>
                @endfor
            </tbody>
        </table>

        <p style="margin-top:1.5em;">
            <button type="button"
                    id="remove-all-devices"
                    class="button button-danger"
                    style="min-height:44px;">
                ${msg.get("devices.removeAll")}
            </button>
        </p>
    @endif

    <p id="devices-error" style="color:red;display:none;margin-top:1em;"></p>
</main>

<script>
  (function() {
    const CSRF = "${csrfToken}";
    const CONFIRM_REMOVE     = "${msg.get("devices.confirmRemove")}";
    const CONFIRM_REMOVE_ALL = "${msg.get("devices.confirmRemoveAll")}";

    async function callDelete(url, confirmMsg) {
      if (!window.confirm(confirmMsg)) return null;
      const resp = await fetch(url, {
        method: 'DELETE',
        credentials: 'include',
        headers: { 'X-CSRF-Token': CSRF, 'Accept': 'application/json' },
      });
      if (!resp.ok) {
        const err = document.getElementById('devices-error');
        err.textContent = 'Request failed: ' + resp.status;
        err.style.display = 'block';
        return null;
      }
      return resp.json().catch(() => ({}));
    }

    document.querySelectorAll('[data-remove-device]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const fp = btn.getAttribute('data-remove-device');
        const result = await callDelete(
          '/api/v1/users/me/known-devices/' + encodeURIComponent(fp),
          CONFIRM_REMOVE
        );
        if (result) {
          const tr = btn.closest('tr');
          if (tr) tr.remove();
        }
      });
    });

    const allBtn = document.getElementById('remove-all-devices');
    if (allBtn) {
      allBtn.addEventListener('click', async () => {
        const result = await callDelete('/api/v1/users/me/known-devices', CONFIRM_REMOVE_ALL);
        if (result) {
          window.location.reload();
        }
      });
    }
  })();
</script>
</body>
</html>
