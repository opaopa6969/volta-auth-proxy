@param String title
@param java.util.List<java.util.Map<String, Object>> tenants
@param String userEmail
@param String creationPolicy
@param boolean canCreateOrg
@param String returnTo
@param String csrfToken
!{var msg = org.unlaxer.infra.volta.Messages.current();}
<!doctype html>
<html lang="${msg.locale()}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <link rel="stylesheet" href="/css/volta.css">
</head>
<body>
<main class="container">
    <header class="tenant-select-header">
        <h1>${msg.get("tenant.title")}</h1>
        @if(userEmail != null && !userEmail.isBlank())
            <p class="muted">${userEmail} — <a href="/auth/logout">${msg.get("auth.logout")}</a></p>
        @endif
    </header>

    @if(tenants.isEmpty())
        <section class="tenant-empty-state">
            <h2>${msg.get("tenant.empty.title")}</h2>
            <p class="muted">${msg.get("tenant.empty.body")}</p>
            @if(canCreateOrg)
                <form action="/api/v1/tenants" method="POST" class="tenant-create-form">
                    <input type="hidden" name="_csrf" value="${csrfToken}">
                    <label>
                        ${msg.get("tenant.create.name")}
                        <input name="name" required maxlength="100" autocomplete="organization" style="width:100%;min-height:44px;">
                    </label>
                    <label>
                        ${msg.get("tenant.create.slug")}
                        <input name="slug" required maxlength="50" pattern="[a-z0-9][a-z0-9-]{1,48}[a-z0-9]" style="width:100%;min-height:44px;">
                    </label>
                    <button type="submit" class="primary" style="min-height:44px;">${msg.get("tenant.create.submit")}</button>
                </form>
            @else
                <p class="tenant-empty-help">
                    @if("DISABLED".equals(creationPolicy))
                        ${msg.get("tenant.empty.disabled")}
                    @elseif("ADMIN_ONLY".equals(creationPolicy))
                        ${msg.get("tenant.empty.adminOnly")}
                    @elseif("INVITE_ONLY".equals(creationPolicy))
                        ${msg.get("tenant.empty.inviteOnly")}
                    @else
                        ${msg.get("tenant.empty.contact")}
                    @endif
                </p>
            @endif
        </section>
    @else
        @if(tenants.size() >= 6)
            <input id="tenant-search" type="search" placeholder="${msg.get("tenant.search")}" style="width:100%;min-height:44px;margin-bottom:12px;">
        @endif
        <div id="tenant-list" class="tenant-grid">
            @for(java.util.Map<String, Object> t : tenants)
                <button class="tenant-card" data-tenant-id="${String.valueOf(t.get("id"))}" data-slug="${String.valueOf(t.get("slug"))}">
                    <div class="tenant-card-head">
                        <strong>${String.valueOf(t.get("name"))}</strong>
                        <span class="muted">/${String.valueOf(t.get("slug"))}</span>
                    </div>
                    <div class="tenant-card-meta">
                        <span class="badge badge-role">${String.valueOf(t.get("role"))}</span>
                        @if(t.get("plan") != null && !"FREE".equals(String.valueOf(t.get("plan"))))
                            <span class="badge badge-plan">${String.valueOf(t.get("plan"))}</span>
                        @endif
                        !{Object mc = t.get("memberCount");}
                        @if(mc instanceof Integer && ((Integer) mc).intValue() > 0)
                            <span class="muted">${msg.get("tenant.memberCount")}: ${String.valueOf(mc)}</span>
                        @endif
                        @if(t.get("emailDomain") != null && !String.valueOf(t.get("emailDomain")).isBlank())
                            <span class="muted">@${String.valueOf(t.get("emailDomain"))}</span>
                        @endif
                    </div>
                    @if(Boolean.TRUE.equals(t.get("isLast")))
                        <span class="badge">${msg.get("tenant.last")}</span>
                    @endif
                </button>
            @endfor
        </div>
        @if(canCreateOrg)
            <p class="tenant-extra-action">
                <a href="#" id="create-another-org-link">${msg.get("tenant.createAnother")}</a>
            </p>
        @endif
    @endif
</main>
<script>
  window.__voltaTenantSelect = {
    returnTo: "${returnTo == null ? "" : returnTo}",
    csrfToken: "${csrfToken}"
  };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
