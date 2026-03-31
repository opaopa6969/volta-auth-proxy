# DGE Auto-iteration — UX C/H Gap 解決
- **Date**: 2026-03-31
- **Flow**: auto-iterate (UX)
- **Theme**: UI/UX 全 C/H Gap に具体的解決策を当てる

---

## 解決策サマリー

### Critical 3 件 → ✅ 全解決

1. **Content Negotiation**: Javalin before handler で Accept ヘッダ判定。JSON には 302 禁止。
2. **return_to**: /login?return_to={url} → session 保存 → ホワイトリスト検証 → リダイレクト
3. **volta-sdk-js**: ~150 行。401 intercept → /auth/refresh → retry → login redirect。concurrent queue。

### High 10 件 → ✅ 全解決

4. **招待着地ページ**: テナント名・招待者名・ロール表示。Google ログインボタン。3 ステップ。
5. **OIDC インタースティシャル**: callback ~350ms。不要。遅延フォールバックのみ用意。
6. **テナント選択**: 2-5 → カード一覧、6+ → 検索付き。最終アクセス順。
7. **テナント切替**: switchTenant() → reload。キャッシュ問題なし。
8. **エラー画面**: 共通テンプレート + 全エラーの人間向けメッセージ + 次のアクション。
9. **メールなし招待**: コピーボタン + QR。
10. **開発者 DX**: DEV_MODE + /dev/token。
11. **モバイル**: viewport meta + min 44px タッチ + responsive CSS。WebView は対象外。
12. **セッション管理 UI**: 一覧 + デバイス表示 + 個別終了 + 全終了。同時上限到達 UI。
13. **招待同意**: 確認画面必須（auto_join 含む）。テナント名・ロール・招待者表示。

### ADR: テンプレートエンジン → jte

## 技術スタック確定

```
サーバー: Javalin
テンプレート: jte
CSS: 単一ファイル、responsive (media queries)
JS: volta-sdk-js (~150行、vanilla JS)
フロントフレームワーク: なし（server-rendered HTML）
```

## C/H Gap 残存数: 0
