@param String currentPath = ""
@param String userEmail = ""
<nav style="display:flex;align-items:center;gap:1.5em;padding:0.75em 1.5em;background:#1a1a2e;border-bottom:1px solid #333;font-size:0.9em">
    <a href="/settings/security" style="color:${currentPath.startsWith("/settings") ? "#fff" : "#aaa"};text-decoration:none">🔐 セキュリティ</a>
    <a href="/auth/sessions" style="color:${currentPath.equals("/auth/sessions") ? "#fff" : "#aaa"};text-decoration:none">📱 セッション</a>
    <a href="/console/" style="color:#aaa;text-decoration:none">📊 ダッシュボード →</a>
    <span style="flex:1"></span>
    @if(!userEmail.isEmpty())
        <span style="color:#888;font-size:0.85em">${userEmail}</span>
    @endif
    <a href="/auth/logout" style="color:#e55;text-decoration:none;font-size:0.85em">ログアウト</a>
</nav>
