# オープンリダイレクト

[English / 英語](open-redirect.md)

---

## これは何？

オープンリダイレクトは、ウェブサイトが外部の攻撃者が管理する URL にユーザーをリダイレクトするよう騙される脆弱性です。アプリケーションがリダイレクト先を URL パラメータ（`?returnTo=...` など）から取得し、その行き先が安全かどうか検証しない場合に発生します。攻撃者は正規に見えるリンク（信頼されたドメインで始まる）を作成しますが、最終的にユーザーを悪意のあるサイトに送ります。

---

## なぜ重要？

オープンリダイレクトが危険なのは信頼を悪用するからです。`https://your-trusted-app.com/login?returnTo=https://evil.com/steal` というリンクを含むフィッシングメールは正規に見えます -- URL がユーザーの信頼するドメインで始まっているからです。ログイン後、ユーザーは無音で攻撃者のサイトにリダイレクトされ、そこで偽の「セッション期限切れ」ページが表示されて認証情報を再度入力させられたり、URL からトークンを盗まれたりします。

OIDC の文脈では、オープンリダイレクトはさらに危険です。ログイン後の `returnTo` パラメータが検証されていないと、攻撃者はユーザーを（新しいセッション Cookie ごと）Cookie やトークンを収集するサイトにリダイレクトできます。

---

## 簡単な例

脆弱なコード：
```java
String returnTo = request.getParameter("returnTo");
response.redirect(returnTo);  // どこにでもリダイレクト、https://evil.com でも
```

攻撃：
```
https://trusted-app.com/login?returnTo=https://evil.com/phishing
```

ユーザーは URL に「trusted-app.com」を見て信頼し、ログインし、`evil.com` にリダイレクトされます。

安全なコード：
```java
String returnTo = request.getParameter("returnTo");
if (isAllowedDomain(returnTo)) {
    response.redirect(returnTo);
} else {
    response.redirect("/dashboard");  // 安全なフォールバック
}
```

---

## volta-auth-proxy での使い方

volta は環境変数 `ALLOWED_REDIRECT_DOMAINS` で設定されたドメインホワイトリストでオープンリダイレクトを防止します：

```
ALLOWED_REDIRECT_DOMAINS=localhost,127.0.0.1
```

検証は `HttpSupport.isAllowedReturnTo()` で行われます：

```java
public static boolean isAllowedReturnTo(String returnTo, String allowedDomainsCsv) {
    if (returnTo == null || returnTo.isBlank()) return false;
    URI uri;
    try { uri = URI.create(returnTo); } catch (Exception e) { return false; }
    if (uri.getHost() == null ||
        !"https".equalsIgnoreCase(uri.getScheme()) &&
        !"http".equalsIgnoreCase(uri.getScheme())) {
        return false;
    }
    Set<String> allowedDomains = Arrays.stream(allowedDomainsCsv.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());
    return allowedDomains.contains(uri.getHost());
}
```

チェック内容：

1. **null/空チェック**: 空の `returnTo` 値は拒否
2. **URI パース可能性**: 不正な URI は拒否（パーサー混乱攻撃を防止）
3. **スキームチェック**: `http` と `https` のみ許可（`javascript:`、`data:`、`ftp:` の URI をブロック）
4. **ホストホワイトリスト**: ホスト名が設定済みの許可ドメインのいずれかと完全一致する必要あり

検証に失敗した場合、volta は安全なデフォルトにリダイレクトします。本番環境では、ホワイトリストにはアプリケーションのドメインのみを含めます（例：`wiki.example.com,admin.example.com`）。

関連: [redirect-uri.md](redirect-uri.md), [token-theft.md](token-theft.md)
