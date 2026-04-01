@param String title
@param String tenantId
@param java.util.List<java.util.Map<String, String>> idps
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
    <h1>IdP 設定</h1>
    <table class="table">
        <thead><tr><th>Provider</th><th>Issuer</th><th>Metadata URL</th><th>Client ID</th><th>証明書</th></tr></thead>
        <tbody>
        @for(java.util.Map<String, String> i : idps)
            <tr>
                <td>${i.get("provider")}</td>
                <td>${i.get("issuer")}</td>
                <td>${i.get("metadataUrl")}</td>
                <td>${i.get("clientId")}</td>
                <td>${i.get("x509Cert") == null || i.get("x509Cert").isBlank() ? "-" : "登録済み"}</td>
            </tr>
        @endfor
        </tbody>
    </table>
    <h2>IdP を追加/更新</h2>
    <form id="idp-create-form">
        <input type="hidden" name="_csrf" value="${csrfToken}">
        <select name="provider_type" style="min-height:44px;">
            <option value="OIDC">OIDC</option>
            <option value="SAML">SAML</option>
        </select>
        <input type="text" name="issuer" placeholder="issuer (任意)" style="min-height:44px;">
        <input type="url" name="metadata_url" placeholder="metadata URL (任意)" style="min-height:44px;">
        <input type="text" name="client_id" placeholder="client_id (任意)" style="min-height:44px;">
        <textarea name="x509_cert" placeholder="-----BEGIN CERTIFICATE----- ... (任意)" rows="4"></textarea>
        <button class="button" type="submit">保存</button>
    </form>
    <p id="idp-result" class="muted"></p>
</main>
<script>
  window.__voltaIdp = { tenantId: "${tenantId}", csrfToken: "${csrfToken}" };
</script>
<script src="/js/volta.js" defer></script>
</body>
</html>
