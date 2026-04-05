@param String title
@param String code
@param String tenantName
@param String role
@param String inviterName
@param boolean isLoggedIn
@param String csrfToken
@param String inviteLoginHref
@param boolean isEmailMismatch = false
@param String currentEmail = ""
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
    <h1>ワークスペース招待</h1>
    <p><strong>${inviterName}</strong> さんが <strong>${tenantName}</strong> に招待しています。</p>
    <p>参加ロール: <strong>${role}</strong></p>
    @if(isEmailMismatch)
        <div class="alert">
            <p>現在 <strong>${currentEmail}</strong> でログイン中ですが、この招待は別のアカウント宛です。</p>
            <p>招待メール宛先のアカウントに切り替えてください。</p>
        </div>
        <form method="post" action="/auth/switch-account">
            <input type="hidden" name="_csrf" value="${csrfToken}">
            <input type="hidden" name="return_to" value="/invite/${code}">
            <button class="button" type="submit">アカウントを切り替える</button>
        </form>
        <a class="muted" href="/">キャンセル</a>
    @elseif(!isLoggedIn)
        <a class="button" href="${inviteLoginHref}">Googleでログインして参加</a>
    @else
        <p class="muted">参加すると、管理者があなたのプロフィールを閲覧できます。</p>
        <form method="post" action="/invite/${code}/accept">
            <input type="hidden" name="_csrf" value="${csrfToken}">
            <button class="button" type="submit">参加する</button>
        </form>
        <a class="muted" href="/">キャンセル</a>
    @endif
</main>
</body>
</html>
