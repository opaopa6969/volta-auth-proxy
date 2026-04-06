# DGE Session: 認証 StateMachine アーキテクチャ設計

- **日付**: 2026-04-06
- **テーマ**: 認証フローを StateMachine + typed processor でモデリングし、state 遷移図を設計の SSOT にする
- **キャラクター**: 今泉、ヤン、リヴァイ、千石、ハウス
- **テンプレート**: カスタム（api-design × security-review hybrid）

---

## Scene 1: Happy Path — OIDC Login を State Machine でモデリング

先輩（ナレーション）:
> 現在の volta-auth-proxy は Main.java 1800 行の中に認証フローが手続き的に書かれている。OIDC login だけで見ても、`/login` → Google redirect → `/callback` → code exchange → upsert user → resolve tenant → issue session → device check → redirect という 8 ステップがある。これを state machine として再定義する提案が出ている。

👤 今泉: 「そもそもなんですけど、この 8 ステップを state として切り出したとき、state の数はいくつになるんですか？ 8 個じゃないですよね？ "Google に飛んでる最中" とか "code exchange の HTTP 待ち" とかも state になるんですか？」

→ Gap 発見: **state の粒度基準が未定義。** HTTP の非同期待ち（外部 IdP への redirect 往復）を state として扱うのか、それとも「ブラウザ側にいる間は auth-proxy の state ではない」とするのか。

☕ ヤン: 「（紅茶を啜りながら）ちょっと待ってほしいんだけど、認証フローって本質的に request-response でしょう？ ブラウザが redirect で戻ってきたときに state パラメータで文脈を復元してる。つまり今の実装でも暗黙の state machine なんだよね。明示化する価値は否定しないけど、state machine にしたからといって "外部に state がある" 問題は消えない。ブラウザが戻ってこなかったら？ タイムアウト state は誰が管理するの？」

→ Gap 発見: **外部待ち state のタイムアウト・GC 戦略が未定義。** OIDC の state パラメータは現在セッションに保存されているが、state machine として明示化するなら TTL と破棄ポリシーが必要。

⚔ リヴァイ: 「話を整理しろ。state machine にする目的は 3 つだ。1: フローが図で見える。2: state ごとにコードを分割できる。3: 不正な遷移をコンパイル時 or ランタイムで弾ける。問題は "誰が state を保持するか" だ。サーバーサイドの DB か？ セッション cookie か？ URL パラメータか？ OIDC の redirect 中は state がブラウザの URL にある。つまり state の持ち主が遷移ごとに変わる。これを型で縛れるのか。」

→ Gap 発見: **state の保持場所（ownership）が遷移ごとに変わる問題。** server session / browser URL / IdP side の 3 箇所に分散する state の一貫性をどう保証するか。

🎩 千石: 「リヴァイさんの 3 番目が最も重要です。"不正な遷移を弾く" — これこそ state machine の真価です。現状の手続き的コードでは、callback に直接 GET を投げれば state パラメータなしでもコードが動いてしまう可能性がある。state machine なら `AWAITING_CALLBACK` state でなければ callback を受け付けないと宣言できる。ただし、これを実現するなら state は必ずサーバー側に持ち、ブラウザの URL は "参照" でなければなりません。」

→ Gap 発見: **state の canonical location の設計方針が未確定。** server-authoritative にするのか、URL も信頼するのか。

---

## Scene 2: State 間 I/O 設計 — Context vs Typed Processor

先輩（ナレーション）:
> 提案では 2 つの設計が比較されている。方式 A: 全 state で共有する Context オブジェクトをたらい回し。方式 B: Processor P1 → P2 間の I/O を型で固定し、間をつなぐオブジェクトを一意に決める。方式 B は processor 間の contract が明示されるが、state が増えると型の組み合わせ爆発が起きうる。

👤 今泉: 「要するに、方式 A は "何でも入る箱" で、方式 B は "専用の受け渡し封筒" ですよね。で、実際の認証フローを見ると、OIDC callback の後に user 情報が生まれて、device check の後に device 情報が生まれて… 情報は遷移のたびに増えていく。方式 B だと、P3 が P1 の出力を使いたいとき、P2 がそれをパススルーする型を持たないといけないんじゃないですか？」

→ Gap 発見: **非隣接 processor 間のデータ伝搬問題。** P1 → P3 で直接必要なデータを、P2 の型にも含めるのか、別のメカニズムが必要か。

☕ ヤン: 「ここ、僕なら折衷案を取るね。Context は存在するけど、各 processor が取り出せるフィールドを型で制限する。Java なら sealed interface で state ごとの Context を定義して、processor はその state 専用の Context しか受け取れない。共通フィールド（session ID、request metadata）は base に、state 固有データ（OIDC token、user info）は state の型に。これなら "グローバルだけど型で守られてる" になる。」

→ Gap 発見: **Context の型戦略が未設計。** sealed interface approach は Java 17+ で使えるが、volta-auth-proxy の Java バージョンは？ また、state 追加時に sealed の permit list を更新する必要がある — これは protocol version up にどう影響するか。

⚔ リヴァイ: 「ヤンの案は悪くない。だが sealed interface は "全 state を 1 ファイルで宣言する" ことになる。state が 20 個になったらどうする。俺なら processor を interface にする。」

```java
interface StateProcessor<IN extends AuthContext, OUT extends AuthContext> {
    OUT process(IN context);
    Class<IN> inputType();
    Class<OUT> outputType();
}
```

「これなら processor ごとにファイルを分けられる。state machine engine が `inputType()` と `outputType()` を見て配線を検証する。コンパイル時に型が合わなければ落ちる。」

→ Gap 発見: **Processor の配線検証タイミング（compile vs boot vs runtime）。** Java の generics は erasure があるので、実際には起動時の検証が必要。

🏥 ハウス: 「全員、肝心なことを見落としてる。認証の state machine には "失敗 state" が必要だ。そしてそれは 1 つじゃない。"IdP が 500 返した" "CSRF state が不一致" "ユーザーが存在しない" "テナントが suspended" — 全部違う失敗だ。今のコードはこれを catch で握りつぶして `/login?error=something` に飛ばしてるだろう？ state machine にするなら、失敗遷移を全部明示しろ。そうしないと "state machine にしたのにエラーハンドリングは手続き的" という最悪のハイブリッドになる。」

→ Gap 発見: **失敗 state と失敗遷移の設計が欠如。** Happy path だけの state machine は価値半減。Error state の分類と遷移先（retry 可能 / login に戻る / admin 通知）の定義が必要。

🎩 千石: 「ハウス先生の指摘に加えて — 失敗 state には **監査ログ** が必要です。"なぜこの state で失敗したか" のコンテキストを全て記録する。state machine なら、遷移のログが自然に残る。これは現状の手続き的コードにはない利点です。ただし、ログに PII を出さない制約との兼ね合いが必要です。」

→ Gap 発見: **State 遷移の監査ログ設計が未定義。** 遷移履歴を audit_log に書くなら、どの粒度で、PII をどう redact するか。

---

## Scene 3: エッジケース — MFA・Invite・Device Check の State 爆発

先輩（ナレーション）:
> volta-auth-proxy の認証フローは OIDC login だけではない。MFA challenge、invite accept + account switch、new device notification、passkey login がある。OIDC login に MFA が加わるだけで state が分岐し、さらに invite 経由だと account switch が挟まる。全フローを 1 つの state machine に入れるのか、フローごとに separate state machine にするのかが設計上の分岐点。

👤 今泉: 「他にないんですか？ つまり、1 つの巨大な state machine か、フローごとにバラバラか、の 2 択だけですか？」

☕ ヤン: 「3 つ目がある。**hierarchical state machine**（HSM）だ。親 state machine が "認証中" / "認証済み" / "エラー" を管理して、"認証中" の中に OIDC / Passkey / Invite の sub-state machine が入る。Statechart とも言う。XState がこのモデルだね。」

→ Gap 発見: **State machine のトポロジー未決定。** Flat FSM / Separate FSMs / Hierarchical (Statechart) の 3 択。それぞれのトレードオフが未分析。

⚔ リヴァイ: 「HSM は図は綺麗だが実装が重い。Java で HSM ライブラリを入れるのか？ 自前で書くのか？ 俺の意見を言う — **フローごとに separate FSM で、共通の exit state（SessionIssued）に合流させろ。** sub-state machine にするほど各フローは複雑じゃない。OIDC は 5 state、Passkey は 3 state、MFA は 2 state。合計 10 state。HSM にする必要がない。」

→ Gap 発見: **MFA は "サブプロセス" であり独立 FSM ではない。** OIDC FSM の途中で MFA FSM を呼び出して戻る仕組み（call/return）が必要。これは flat FSM では表現できない。

🏥 ハウス: 「もっと厄介な問題がある。invite accept のフローを見ろ。"ログイン済みの alice が、bob 宛の invite を開く → account switch → bob で再ログイン → invite に戻る"。この "invite に戻る" は state machine の **continuation** だ。switch-account でセッションを破棄した後、新しいセッションで元の state machine を再開する必要がある。state がセッションに紐づいてるなら、セッション破棄で state も消える。」

→ Gap 発見: **セッション跨ぎの state 継続問題。** Account switch でセッションが切れた後、invite FSM の state をどこに退避して、新セッションでどう復元するか。

🎩 千石: 「ハウス先生の指摘は critical です。state machine の state をセッションに保持するなら、セッション破棄 = state 喪失。これを避けるには state を DB に保存するか、signed token（JWT）で持ち回すか。DB ならクエリが増える。JWT なら改ざん防止が必要。どちらにしても、**state の永続化戦略** が state machine 設計の前に必要です。」

→ Gap 発見: **State 永続化戦略が未定義。** Session (ephemeral) / DB (persistent) / Signed Token (portable) のどれを canonical store にするか。フローによって使い分ける場合、その基準は？

---

## Scene 4: 運用・進化 — Protocol Version Up と State 追加

先輩（ナレーション）:
> 認証プロトコルは進化する。今後 AUTH-004（known device）、AUTH-009（MFA policy per tenant）が追加予定。さらに将来的に WebAuthn PRF extension や Conditional UI が入る可能性がある。state machine を導入した後、新しい state を追加するときの後方互換性が課題。

👤 今泉: 「前もそうだったんじゃないですか？ つまり、Main.java が 1800 行になったのは "ちょっとだけ追加" の積み重ねですよね。state machine にしても "ちょっと state を追加" の繰り返しで、state machine 自体が複雑になるのでは？」

→ Gap 発見: **State machine の複雑性増大への対策が未定義。** state 数の上限ガイドライン、state machine の分割基準がないと Main.java の二の舞。

☕ ヤン: 「だからこそ version が要る。state machine 定義自体にバージョンを振る。`AuthFlowV1`, `AuthFlowV2`。進行中のフローは開始時のバージョンで完走させて、新規フローだけ新バージョンを使う。migration は不要。古い version の state machine は TTL 後に消える。」

→ Gap 発見: **State machine versioning の具体的な仕組みが未設計。** version ごとの state machine 定義をどう管理するか。

⚔ リヴァイ: 「version 管理は必要だが、過剰設計するな。現実的には **state を追加するのは "間に挿入" か "分岐を追加" の 2 パターンだ。** 間に挿入（例: UserResolved → DeviceChecked → SessionIssued）なら、既存の processor は影響を受けない。分岐を追加（例: MFA required の分岐）なら、分岐元の processor だけ変更。state machine の利点はまさにここで、**影響範囲が state 遷移図から一目で分かる。** version 管理は "破壊的変更（state の削除・統合）" のときだけでいい。」

→ Gap 発見: **State 変更の分類（追加 / 挿入 / 分岐 / 削除・統合）と、それぞれの互換性ルールが未定義。**

🏥 ハウス: 「最後に診断結果を伝える。お前たちが議論してる state machine は "認証フローの state" だ。だが本当にモデリングすべきは **"ユーザーの認証ライフサイクル state"** じゃないのか？ つまり "未認証" → "認証中" → "認証済み(MFA 未)" → "完全認証" → "セッション期限切れ" → "再認証要求" のような **常駐 state machine**。フローの state machine はその中の遷移を詳細化したもの。この 2 層を混同すると、"ログイン済みだが MFA 未完了のユーザーが invite を開いた" みたいな state の組み合わせで破綻する。」

→ Gap 発見: **認証ライフサイクル state machine と認証フロー state machine の 2 層構造が未検討。** Session state（常駐）と Flow state（一時的）を分離しないと、state の組み合わせ爆発が起きる。

🎩 千石: 「ハウス先生の診断に全面同意です。そしてこの 2 層構造こそが、state 遷移図の最大の価値になります。上位の図で "このユーザーは今どの状態か" が分かり、下位の図で "今どのフローのどのステップにいるか" が分かる。プログラムを読む人にとって、これ以上の地図はありません。」

---

## Gap 一覧

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 1 | State の粒度基準が未定義（外部 redirect 中は state か？） | Missing logic | High |
| 2 | 外部待ち state のタイムアウト・GC 戦略が未定義 | Ops gap | High |
| 3 | State の保持場所（ownership）が遷移ごとに変わる問題 | Integration gap | Critical |
| 4 | State の canonical location（server-authoritative vs URL 信頼）未確定 | Missing logic | High |
| 5 | 非隣接 processor 間のデータ伝搬問題（P1→P3 パススルー） | Type/coercion gap | Medium |
| 6 | Context の型戦略未設計（sealed interface? generics?） | Missing logic | High |
| 7 | Processor の配線検証タイミング（compile vs boot vs runtime） | Integration gap | Medium |
| 8 | 失敗 state と失敗遷移の設計が欠如 | Missing logic | Critical |
| 9 | State 遷移の監査ログ設計が未定義 | Ops gap | Medium |
| 10 | State machine トポロジー未決定（Flat / Separate / HSM） | Missing logic | Critical |
| 11 | MFA の "サブプロセス" 呼び出し・復帰メカニズム未設計 | Integration gap | High |
| 12 | セッション跨ぎの state 継続問題（invite account switch） | Missing logic | Critical |
| 13 | State 永続化戦略未定義（Session / DB / Signed Token） | Missing logic | Critical |
| 14 | State machine 複雑性増大への対策・分割基準が未定義 | Ops gap | Medium |
| 15 | State machine versioning の具体的仕組みが未設計 | Missing logic | High |
| 16 | State 変更の分類と互換性ルールが未定義 | Spec-impl mismatch | Medium |
| 17 | 認証ライフサイクル SM と認証フロー SM の 2 層構造が未検討 | Missing logic | Critical |

## Gap 詳細（Critical + High）

### Gap-3: State ownership が遷移ごとに変わる
- **Observe**: OIDC flow では state が server session → browser URL → IdP → browser URL → server に移動する
- **Suggest**: Server を唯一の state authority とし、URL の state param は "lookup key" として扱う
- **Act**: `auth_flows` テーブルに flow_id, current_state, context_json, created_at, expires_at を持つ。URL には flow_id のみ含める

### Gap-8: 失敗 state と失敗遷移の設計欠如
- **Observe**: 現在はエラーを catch して `/login?error=` にリダイレクトしており、失敗の種類が区別されない
- **Suggest**: Error state を分類し、各 state からの失敗遷移を明示する
- **Act**: `ErrorState { category: IdpError|CsrfMismatch|UserNotFound|TenantSuspended|MfaFailed, retryable: bool, redirectTo: String }`

### Gap-10: State machine トポロジー未決定
- **Observe**: Flat FSM / Separate FSMs / HSM の 3 択が分析されていない
- **Suggest**: リヴァイ案（Separate FSMs + 共通 exit state）を base に、MFA だけ call/return で対応
- **Act**: `OidcFlow`, `PasskeyFlow`, `InviteFlow` を独立 FSM として定義。MFA は `MfaSubFlow` として parent FSM から call/return

### Gap-12: セッション跨ぎの state 継続
- **Observe**: Invite の account switch でセッション破棄 → 新セッション作成の間に flow state が消失する
- **Suggest**: Flow state を DB に保存し、session ではなく flow_id で紐づける
- **Act**: `return_to` を `flow_id` に置き換え。新セッションで `/invite?flow_id=xxx` → DB から flow state 復元 → 継続

### Gap-13: State 永続化戦略
- **Observe**: State の保存先によってパフォーマンス・セキュリティ特性が異なる
- **Suggest**: 短命フロー（OIDC, Passkey）は Redis/memory + TTL、長命フロー（Invite）は DB
- **Act**: `FlowStateStore` interface を定義し、`InMemoryFlowStore`（TTL 5min）と `PersistentFlowStore`（PostgreSQL）を実装

### Gap-17: 2 層 state machine 構造
- **Observe**: Session state（ユーザーの認証ライフサイクル）と Flow state（認証フローの進行）を混同すると state 組み合わせ爆発
- **Suggest**: 上位 = `SessionStateMachine { Unauthenticated, Authenticating, Authenticated_MfaPending, FullyAuthenticated, Expired }` / 下位 = 各 Flow FSM
- **Act**: Session SM は `sessions` テーブルの `auth_state` カラムで管理。Flow SM は `auth_flows` テーブルで一時管理。Session SM の state 遷移が Flow SM の開始・完了をトリガー
