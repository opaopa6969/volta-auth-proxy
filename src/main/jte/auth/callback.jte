@param String title
@param String code
@param String state
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
    <h1>ログイン処理中...</h1>
    <p>認証を完了しています。画面を閉じずにお待ちください。</p>
    <div class="spinner" aria-hidden="true"></div>
    <p id="callback-status" class="muted">Google から受け取った情報を検証しています...</p>
    <div id="callback-data" data-code="${code}" data-state="${state}" style="display:none"></div>
</main>
<script src="/js/volta.js" defer></script>
</body>
</html>
