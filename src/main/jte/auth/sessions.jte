@param String title
@param int sessionCount
@param java.util.List<java.util.Map<String, String>> sessions
@param String csrfToken
@param String flashMessage
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
    <h1>セッション管理</h1>
    @if(flashMessage != null && !flashMessage.isBlank())
        <p class="badge">${flashMessage}</p>
    @endif
    <p>現在のセッション数: ${sessionCount} / 5</p>
    <table class="table">
        <thead>
        <tr>
            <th>状態</th>
            <th>デバイス</th>
            <th>IP</th>
            <th>最終アクセス</th>
            <th>期限</th>
            <th>操作</th>
        </tr>
        </thead>
        <tbody>
        @for(java.util.Map<String, String> s : sessions)
            <tr>
                <td>
                    ${s.get("status")}
                    @if("true".equals(s.get("isCurrent")))
                        <span class="badge">この端末</span>
                    @endif
                </td>
                <td>${s.get("device")} ${s.get("browser")} / ${s.get("os")}</td>
                <td>${s.get("ip")}</td>
                <td>${s.get("lastActive")}</td>
                <td>${s.get("expiresAt")}</td>
                <td>
                    @if("ACTIVE".equals(s.get("status")))
                        <button class="button danger" data-revoke-id="${s.get("id")}">失効</button>
                    @else
                        -
                    @endif
                </td>
            </tr>
        @endfor
        </tbody>
    </table>
    <button id="revoke-all" class="button danger">全セッションを失効</button>
</main>
<script src="/js/volta.js" defer></script>
</body>
</html>
