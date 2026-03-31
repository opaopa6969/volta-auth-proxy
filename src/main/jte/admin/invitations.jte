@param String title
@param String tenantId
@param java.util.List<java.util.Map<String, String>> invitations
@param String csrfToken
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
    <h1>招待管理</h1>
    <table class="table">
        <thead><tr><th>ステータス</th><th>コード</th><th>ロール</th><th>期限</th><th>作成者</th><th>操作</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> i : invitations)
            <tr>
                <td>${i.get("status")}</td>
                <td>${i.get("code")}</td>
                <td>${i.get("role")}</td>
                <td>${i.get("expiresAt")}</td>
                <td>${i.get("createdBy")}</td>
                <td><button class="button danger invite-cancel" data-invitation-id="${i.get("id")}">取消</button></td>
            </tr>
        @endfor
        </tbody>
    </table>

    <h2>招待を作成</h2>
    <form id="invite-create-form">
        <input type="hidden" name="_csrf" value="${csrfToken}">
        <input type="email" name="email" placeholder="メール（任意）" style="min-height:44px;">
        <select name="role" style="min-height:44px;">
            <option value="MEMBER">MEMBER</option>
            <option value="ADMIN">ADMIN</option>
        </select>
        <button class="button" type="submit">招待を作成</button>
    </form>
    <p id="invite-result" class="muted"></p>
</main>
<script>
  window.__voltaInvitations = { tenantId: "${tenantId}", csrfToken: "${csrfToken}" };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
