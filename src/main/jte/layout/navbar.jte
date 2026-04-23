@param String currentPath = ""
@param String userEmail = ""
!{var msg = org.unlaxer.infra.volta.Messages.current();}
<nav style="display:flex;align-items:center;gap:1.5em;padding:0.75em 1.5em;background:#1a1a2e;border-bottom:1px solid #333;font-size:0.9em">
    <a href="/settings/security" style="color:${currentPath.equals("/settings/security") ? "#fff" : "#aaa"};text-decoration:none">${msg.get("navbar.security")}</a>
    <a href="/settings/devices" style="color:${currentPath.equals("/settings/devices") ? "#fff" : "#aaa"};text-decoration:none">${msg.get("navbar.devices")}</a>
    <a href="/auth/sessions" style="color:${currentPath.equals("/auth/sessions") ? "#fff" : "#aaa"};text-decoration:none">${msg.get("navbar.sessions")}</a>
    <a href="/console/" style="color:#aaa;text-decoration:none">${msg.get("navbar.dashboard")}</a>
    <span style="flex:1"></span>
    @if(!userEmail.isEmpty())
        <span style="color:#888;font-size:0.85em">${userEmail}</span>
    @endif
    <a href="/auth/logout" style="color:#e55;text-decoration:none;font-size:0.85em">${msg.get("common.logout")}</a>
</nav>
