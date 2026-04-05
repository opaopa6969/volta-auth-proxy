@param String tenantName
@param String role
@param String dashboardUrl
<!doctype html>
<html lang="ja">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>参加完了</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container">
    <h1>🎉 ${tenantName} に参加しました</h1>
    <p>ロール: <strong>${role}</strong></p>
    <a class="button" href="${dashboardUrl}">ダッシュボードへ</a>
</main>
</body>
</html>
