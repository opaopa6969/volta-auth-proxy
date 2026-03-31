@param String title
@param String tenantId
@param java.util.List<java.util.Map<String, String>> members
@param String currentUserRole
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
    <h1>メンバー管理</h1>
    <table class="table">
        <thead><tr><th>名前</th><th>メール</th><th>ロール</th><th>操作</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> m : members)
            <tr>
                <td>${m.get("displayName")}</td>
                <td>${m.get("email")}</td>
                <td>
                    <select class="member-role" data-member-id="${m.get("memberId")}">
                        <option value="VIEWER" selected="${"VIEWER".equals(m.get("role"))}">VIEWER</option>
                        <option value="MEMBER" selected="${"MEMBER".equals(m.get("role"))}">MEMBER</option>
                        <option value="ADMIN" selected="${"ADMIN".equals(m.get("role"))}">ADMIN</option>
                        <option value="OWNER" selected="${"OWNER".equals(m.get("role"))}">OWNER</option>
                    </select>
                </td>
                <td><button class="button danger member-remove" data-member-id="${m.get("memberId")}">削除</button></td>
            </tr>
        @endfor
        </tbody>
    </table>
</main>
<script>
  window.__voltaMembers = { tenantId: "${tenantId}", csrfToken: "${csrfToken}" };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
