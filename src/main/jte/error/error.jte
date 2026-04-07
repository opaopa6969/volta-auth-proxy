@param String title
@param String errorCode
@param String message
@param java.util.List<java.util.Map<String, String>> actions
@param boolean showSupport
@param String supportContact
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
    <p class="muted">Error: ${errorCode}</p>
    <h1>${msg.get("error.generic")}</h1>
    <p>${message}</p>
    <div class="actions">
        @for(java.util.Map<String, String> action : actions)
            <a class="button" href="${action.get("url")}">${action.get("label")}</a>
        @endfor
    </div>
    @if(showSupport)
        <p class="muted">${msg.get("error.contact", supportContact)}</p>
    @endif
</main>
</body>
</html>
