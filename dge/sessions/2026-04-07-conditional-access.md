# DGE Session: 条件付きアクセス / リスクベース認証

> Date: 2026-04-07
> Structure: 🗣 座談会 (roundtable)
> Characters: ☕ヤン / 👤今泉 / 🎩千石 / 😈Red Team / 🏥ハウス / 🎨深澤
> Theme: volta-auth-proxy に条件付きアクセスをどう設計するか
> Round: 1
> Gaps: 11

## Scene 1: そもそも何を守るのか

ヤン: 今の仕組み（MFA, session TTL, ForwardAuth）で何が守れてないのか？
今泉: 具体的に誰がどう困っている？
Red Team: 正規クレデンシャルを盗んだ攻撃者がブラジルから深夜3時にログインしても通る。「誰が」は見てるが「どこから」「いつ」は見てない。
千石: 認証は「誰か」を確認するが「今のアクセスは信頼できる状況か」を確認していない。
ハウス: Cookie窃取前提の設計 vs 異常パターン検知 — 全然違う病気。
ヤン: 80%の価値は異常パターン検知。新デバイスからのログイン検知 + step-up要求で十分では。
深澤: 「不審なアクセスです」で正当ユーザーが仕事できなくなる恐怖。

→ Gap G1: 条件付きアクセスのスコープ未定義 [Critical]
→ Gap G2: 誤検知時のUX・回復フロー未設計 [High]

## Scene 2: 評価タイミングと判定基準

今泉: ログイン時だけ？毎リクエスト？
ヤン: 毎リクエストは重すぎる。
千石: ログイン時に信頼スコアを算出→セッションに記録→ForwardAuthはスコアだけ見る。
Red Team: モバイルのWi-Fi→LTE切替でIP変わる。IP変更=不審にすると誤検知だらけ。
ハウス: 条件の定義を誰がするのか。管理者か？プラットフォームか？
深澤: 管理者が厳しくしすぎて自分がロックアウト。設定プレビューがないと危険。
今泉: テナント管理者が本当にこの設定を欲しいと言ったのか？

→ Gap G3: 評価タイミング未定義 [Critical]
→ Gap G4: 条件の主体不明 + セルフロックアウト防止 [High]
→ Gap G5: IP変更の扱い（正当 vs ハイジャック） [High]

## Scene 3: amr と step-up の関係

ヤン: amr と session_scopes は同じことを2箇所で管理してないか？
千石: amr=認証方法の記録、step-up=追加認証の要求。別物。
Red Team: JWT内のamrはsession TTL内は変わらない。step-up後に更新されるか？
ハウス: auth_state / session_scopes / amr — 3つの認証状態表現が共存。本当に必要か？
ヤン: 3つとも役割が違うから共存は正しい。ただ更新タイミングを決めないと。
深澤: amrを条件分岐に使えたら便利（passkey持ちは地理制限緩和など）。

→ Gap G6: amr更新タイミング未定義 [Medium]
→ Gap G7: 3つの認証状態表現の関係未文書化 [Medium]

## Scene 4: 最小実装と段階的拡張

ヤン提案:
  Phase 1: 新デバイス/IP検知 → メール通知 + amr JWT追加
  Phase 2: 新デバイス時にstep-up（MFA再要求）+ テナント管理者設定
  Phase 3: 地理/時間帯制限 + カスタム条件UI + amrベース条件分岐

Red Team: 通知だけは弱いが、検知の仕組みを先に作るのは正しい。
千石: 検知レイヤーと対応レイヤーを分離すれば段階的に進められる。
ハウス: デバイス判定方法を決めてない。曖昧な検知は誤検知の温床。
深澤: 通知メールのトーン。「不審なログイン」はパニックを起こす。

→ Gap G8: デバイス識別方法未定義（004-3と密結合） [Critical]
→ Gap G9: 検知/対応レイヤーの分離設計なし [High]
→ Gap G10: 通知メッセージのトーン設計 [Medium]
→ Gap G11: amr値セット未定義 [Low]

## Gap Summary

| # | Gap | Severity | Category |
|---|-----|----------|----------|
| G1 | スコープ未定義（Cookie窃取 vs 異常検知 vs ポリシー） | Critical | Architecture |
| G2 | 誤検知時UX・回復フロー | High | UX |
| G3 | 評価タイミング（login / IP変更 / 毎req） | Critical | Architecture |
| G4 | 条件の主体 + セルフロックアウト防止 | High | Policy |
| G5 | IP変更の扱い（正当 vs ハイジャック） | High | Security |
| G6 | amr更新タイミング | Medium | Protocol |
| G7 | auth_state/session_scopes/amr 関係文書化 | Medium | Documentation |
| G8 | デバイス識別方法（004-3密結合） | Critical | Architecture |
| G9 | 検知/対応レイヤー分離 | High | Architecture |
| G10 | 通知メッセージトーン | Medium | UX |
| G11 | amr値セット | Low | Protocol |

Critical: 3 / High: 4 / Medium: 3 / Low: 1 — Total: 11
