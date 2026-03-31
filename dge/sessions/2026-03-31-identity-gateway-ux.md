# DGE Session — UI/UX Review
- **Date**: 2026-03-31
- **Flow**: quick
- **Structure**: roundtable
- **Theme**: volta-auth-proxy の全ユーザー接触面の UI/UX
- **Characters**: 深澤, 利根川, 今泉, 僕

---

## Gap 一覧

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| UX-1 | 招待リンク着地ページの信頼構築コンテキスト欠落 | UX gap | 🟠 High |
| UX-2 | 初回フロー全体のステップ数・モバイル体験未検証 | UX gap | 🟠 High |
| UX-3 | ログイン後リダイレクト先・return_to パラメータ未定義 | Missing logic | 🔴 Critical |
| UX-4 | 「Google のみ」の理由づけ・ログイン画面拡張性 | UX gap | 🟡 Medium |
| UX-5 | 現在テナント常時表示・切替の視覚フィードバック | UX gap | 🟠 High |
| UX-6 | テナント切替時の App 側リフレッシュ | Missing logic | 🟠 High |
| UX-7 | テナント切替 UI vs 再ログイン代用の判断 | UX gap | 🟡 Medium |
| UX-8 | エラー画面の「次のアクション」導線欠落 | UX gap | 🟠 High |
| UX-9 | 招待期限切れ時の再招待リクエストフロー | Missing logic | 🟡 Medium |
| UX-10 | 全エラーの人間向けコピーライティング | UX gap | 🟠 High |
| UX-11 | メールなし招待の代替 UX（コピー・QR） | UX gap | 🟠 High |
| UX-12 | 招待状態管理 UI・参加トラッキング | Missing logic | 🟡 Medium |
| UX-13 | ローカル開発時の認証バイパス（DX） | Ops gap | 🟠 High |
| UX-14 | 認証切れ → 再認証 → 元画面復帰フロー | Missing logic | 🔴 Critical |

## Auto-merge: 素の LLM 追加 Gap

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| UX-15 | Content Negotiation 中間状態 — SPA fetch が 302 で HTML 受信する問題 | Missing logic | 🔴 Critical |
| UX-16 | モバイル・レスポンシブ方針欠落（Safari ITP・WebView） | UX gap | 🟠 High |
| UX-17 | ローカライゼーション (ja/en) 未定義 | UX gap | 🟡 Medium |
| UX-18 | アクセシビリティ要件ゼロ | UX gap | 🟡 Medium |
| UX-19 | テナント別ブランディング拡張ポイント未設計 | UX gap | 🟡 Medium |
| UX-20 | セッション管理 UI（一覧・失効・同時上限 UX） | Missing logic | 🟠 High |
| UX-21 | OIDC コールバック中インタースティシャル画面 | UX gap | 🟠 High |
| UX-22 | 招待参加の明示的同意ステップ + auto_join GDPR 問題 | Legal gap | 🟠 High |

### マージ統合: UX Gap 全 22 件（Critical 3 / High 13 / Medium 6）
