@param String title
@param java.util.List<java.util.Map<String, Object>> logs
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
    <h1>監査ログ</h1>
    <table class="table">
        <thead><tr><th>時刻</th><th>event</th><th>target</th><th>requestId</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, Object> l : logs)
            <tr>
                <td>${String.valueOf(l.get("timestamp"))}</td>
                <td>${String.valueOf(l.get("eventType"))}</td>
                <td>${String.valueOf(l.get("targetType"))}:${String.valueOf(l.get("targetId"))}</td>
                <td>${String.valueOf(l.get("requestId"))}</td>
            </tr>
        @endfor
        </tbody>
    </table>
</main>
<script src="/js/volta.js" defer></script>
</body>
</html>
