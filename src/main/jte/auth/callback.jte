@param String title
@param String code
@param String state
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
    <h1>${msg.get("callback.processing")}</h1>
    <p>${msg.get("callback.verifying")}</p>
    <div class="spinner" aria-hidden="true"></div>
    <p id="callback-status" class="muted"></p>
    <div id="callback-data" data-code="${code}" data-state="${state}" style="display:none"></div>
</main>
<script src="/js/volta.js" defer></script>
</body>
</html>
