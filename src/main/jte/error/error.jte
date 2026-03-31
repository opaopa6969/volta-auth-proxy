@param String title
@param String errorCode
@param String message
@param java.util.List<java.util.Map<String, String>> actions
@param boolean showSupport
@param String supportContact
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
    <p class="muted">Error: ${errorCode}</p>
    <h1>操作を完了できませんでした</h1>
    <p>${message}</p>
    <div class="actions">
        @for(java.util.Map<String, String> action : actions)
            <a class="button" href="${action.get("url")}">${action.get("label")}</a>
        @endfor
    </div>
    @if(showSupport)
        <p class="muted">問題が継続する場合は ${supportContact} へ連絡してください。</p>
    @endif
</main>
</body>
</html>
