# Backlog 001: フォーム入力状態の復元（セッション切れ対応）

## Phase: 2
## Priority: High
## Category: UX / SDK

---

## 問題

ユーザーがフォーム入力中にセッションが切れると:
- HTML form POST → 401 で失敗 → 再ログイン → フォーム入力が消える
- fetch/ajax → volta-sdk-js の retry で自動復帰する（問題なし）

## Phase 1 の対応（ドキュメントのみ）

App 開発者への推奨:
- フォーム submit は `Volta.fetch()` を使う（HTML form POST を避ける）
- SDK の 401 intercept → refresh → retry で submit が自動復帰する
- HTML form POST を使う場合は、App 側で自前の自動保存を実装する

→ この内容を volta-sdk のドキュメントに記載する。
→ tasks/001 の volta-sdk-js セクションに追記する。

## Phase 2 の対応（SDK 実装）

### 仕様

volta-sdk-js にフォーム自動保存機能を追加:

```javascript
// App 側の使い方
Volta.init({ gatewayUrl: "...", autoSaveForm: true });
```

### 動作

1. `autoSaveForm: true` の場合、SDK がページ内の form を監視
2. input/textarea/select の変更を検知（input イベント）
3. 変更のたびに sessionStorage に保存
   - key: `volta_form_${location.pathname}_${form.id || form.action}`
   - value: `JSON.stringify({ fieldName: value, ... })`
   - password フィールドは除外
   - file input は除外
4. ページロード時に sessionStorage をチェック
   - 保存データがあれば各フィールドに復元
   - トースト表示: 「入力内容を復元しました」
   - 5秒後に sessionStorage から削除
5. form submit 成功時に sessionStorage から削除
6. 明示的に破棄: `Volta.clearSavedForm(formId)`

### セッション切れ → 復元のフロー

```
1. ユーザーがフォーム入力中
2. SDK が sessionStorage に定期保存（input イベントごと）
3. セッション切れ → 401
4. SDK が /login?return_to={current_url} にリダイレクト
5. Google 再ログイン
6. return_to で元ページに戻る
7. ページロード → SDK が sessionStorage からフォーム復元
8. ユーザーが submit → 成功
```

### 除外するもの

- password フィールド（セキュリティ）
- file input（サイズ制限）
- contenteditable 要素（Phase 2 では対象外）
- hidden input（CSRF token 等は新しい値を使う）

### sessionStorage を使う理由

- localStorage ではなく sessionStorage → タブを閉じたら消える
- Cookie ではない → サイズ制限がない
- サーバーに送らない → Gateway に業務データが漏れない

### テスト項目

- [ ] input[type=text] の復元
- [ ] textarea の復元
- [ ] select の復元
- [ ] checkbox / radio の復元
- [ ] password フィールドが除外されること
- [ ] file input が除外されること
- [ ] submit 成功で sessionStorage 削除
- [ ] 別タブの同じフォームには影響しないこと
- [ ] CSRF token は復元しないこと（新しい値を使う）

---

## 参照

- dge/specs/ui-flow.md — FL-5: フォーム入力中のセッション切れ
- tasks/001 — volta-sdk-js 実装
