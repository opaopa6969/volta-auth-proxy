# DGE Session Round 2 — Identity Gateway 設計（専門家投入）
- **Date**: 2026-03-31
- **Flow**: quick (round 2)
- **Structure**: roundtable
- **Theme**: Identity Gateway — SaaS アーキテクチャ + Auth セキュリティ深堀り
- **Characters**: 今泉, ヤン, 🔐 Auth 専門家(ad-hoc), 💼 SaaS 専門家(ad-hoc)
- **Previous**: 2026-03-31-identity-gateway-quick.md (22 gaps)

---

## Scene 1: SaaS としてのテナントライフサイクル

**先輩**: 「前回、tenant 解決と membership モデルの Gap が出た。今回はもう一段深く、SaaS プロダクトとしての tenant ライフサイクルと、auth 設計の具体仕様を詰める。」

**💼 SaaS 専門家**: 「まず聞きたいのは、テナントの"死"の設計です。作るのは簡単。問題は、テナントを停止・削除するとき何が起きるか。テナント admin が退職した、支払いが止まった、トライアルが期限切れ。このとき Gateway は何を返すべきか。403？ 専用のランディングページ？ そのテナントに所属するユーザーが他のテナントにもいる場合、そっちのアクセスは維持するのか。」

→ Gap 発見: テナントのライフサイクル管理（停止・凍結・削除）と、停止テナントへのアクセス時の Gateway の振る舞いが未設計。

**👤 今泉**: 「そもそも、テナントの"作成"って誰がやるんですか？ 最初の signup ユーザーが自動的に tenant owner になる？ それとも別の管理者が事前にテナントを作る？ SaaS として self-service なのか、managed なのか、ここ決まってないですよね。」

→ Gap 発見: テナント作成フロー（self-service vs managed）と初期 owner の決定ロジックが未定義。

**💼 SaaS 専門家**: 「もう一つ。"サービスを爆速で量産" って言ったけど、量産した App ごとに機能制限かけたくならない？ Free プランでは App A だけ、Pro プランでは App A + B + C、みたいな。Gateway がテナントのプランを知ってないと、この制御は App 側に分散する。20 個の App に全部プラン判定入れるの？」

**☕ ヤン**: 「…それ、Gateway の責務がまた膨張するパターンだよね。プラン管理まで Gateway に入れたら、もう Gateway じゃなくて PaaS を作ってる。」

**💼 SaaS 専門家**: 「いや、プランの"詳細"は別サービスでいい。でも Gateway が JWT に `plan: "pro"` くらいは載せないと、各 App が毎回 billing サービスに問い合わせることになる。それこそ量産に向かない。」

→ Gap 発見: テナントのプラン / エンタイトルメント情報の配布方法が未設計。Gateway が載せるか、App が個別に取得するか、の方針が欠落。

---

## Scene 2: Auth の内側 — M2M と API キー

**先輩**: 「ここまでブラウザからのアクセスを前提に話してきた。でも現実には App 間通信や外部 API 連携もある。」

**🔐 Auth 専門家**: 「ここが一番大きな穴だと思います。Identity Gateway はブラウザの OIDC フローしか見ていない。でも量産した App 同士が通信するとき、誰が認証するんですか？ App A が App B の API を叩くとき、ユーザーの JWT を使い回す？ それだとユーザーコンテキストなしの batch 処理は動かない。service-to-service の client credentials フローが要ります。」

→ Gap 発見: Service-to-service (M2M) 認証の設計が完全に欠落。Client credentials フロー、サービスアカウント、内部 API の認証方式が未定義。

**👤 今泉**: 「他にないの？ って思うんですけど、外部の連携先が webhook を送ってくるとか、外部パートナーが API を叩くケースは？ そのとき OIDC ログインさせるわけにいかないですよね。API キーとか要りません？」

**🔐 Auth 専門家**: 「当然要る。そして API キーの管理は地味に重い。発行、失効、スコープ制限、レート制限、テナント紐付け。API キーを Gateway で管理するのか、別の API Management レイヤーを置くのか。ここを最初から考えないと後で全部作り直しになる。」

→ Gap 発見: 外部連携向けの API キー管理（発行・失効・スコープ・テナント紐付け）の設計が欠落。

**☕ ヤン**: 「…つまり認証パターンが最低 3 つ必要ってこと？ ブラウザ OIDC、M2M client credentials、外部 API キー。Gateway は全部ハンドルするの？ 3 つのフローを 1 つの Gateway に突っ込んだら、複雑度かなり上がるよ。」

**🔐 Auth 専門家**: 「突っ込むべきです。入口を分散させたら、認証ポリシーの一貫性が崩壊する。ただし内部のハンドラは分離する。ルーティングで `/oauth/token` は client credentials、`/login` はブラウザ OIDC、`/api/v1/*` は API キー検証、と振り分ける。」

→ Gap 発見: 複数認証フロー（OIDC / client credentials / API キー）の統合アーキテクチャと、Gateway 内部のルーティング・ハンドラ分離設計が未定義。

---

## Scene 3: App 側の SDK とエラー契約

**先輩**: 「"App 側は何も考えない" を実現するには、App が Gateway と話すための SDK やライブラリが要る。毎回 JWT パースを手書きはしない。」

**💼 SaaS 専門家**: 「ここ、SaaS プラットフォームを作った経験から言うと、SDK の設計がプラットフォームの生死を分ける。JWT 検証、テナント解決、role チェック、ユーザー情報取得。これを各 App で手書きしたら、20 個の App に 20 種類の認証バグが生まれる。共通ライブラリ、もしくは sidecar パターンが必須。」

**👤 今泉**: 「要するに、Gateway 作るだけじゃなくて、App 側のクライアントライブラリも同時に作らないと "爆速量産" にならないってことですよね。」

→ Gap 発見: App 向けクライアント SDK / ライブラリ（JWT 検証・テナント解決・role チェック）の設計が欠落。SDK なしでは "量産" の目標と矛盾する。

**🔐 Auth 専門家**: 「SDK で大事なのはエラー契約です。Gateway が 401 を返したとき、App は何をすべきか。token refresh を試す？ ログインページにリダイレクト？ 403 は？ テナント停止なのか権限不足なのか区別できるレスポンスボディが要る。ここを曖昧にすると、App ごとにエラーハンドリングがバラバラになる。」

→ Gap 発見: Gateway → App 間のエラーレスポンス契約（HTTP ステータス + エラーボディの構造・コード体系）が未設計。

**💼 SaaS 専門家**: 「もう一個。テナント管理者が自分のテナントのユーザーを管理したいとき、API はどこにある？ Gateway が `/admin/tenants/{id}/members` みたいな管理 API を持つのか。それとも別の admin service があるのか。この管理 API の認証は？ tenant admin の role で通すとして、tenant admin 自身の昇格・降格は誰がやるの？ super admin？」

→ Gap 発見: テナント管理 API（メンバー管理・role 変更・テナント設定）の設計と、管理権限の階層（tenant admin / super admin / platform admin）が未定義。

---

## Scene 4: 運用とデータ主権

**先輩**: 「SaaS として運用が始まったら、テナントからデータエクスポートの要求が来る。GDPR もある。」

**💼 SaaS 専門家**: 「テナント退会時のデータ取り扱い。ユーザーデータの削除要求。これは法的要件。Gateway がユーザー情報を持ってるなら、Gateway 側のデータ削除も必要。App 側にも "このユーザーのデータ消して" を伝える仕組みが要る。Right to be Forgotten は Gateway だけで完結しない。」

→ Gap 発見: ユーザーデータの削除要求（Right to be Forgotten）への対応設計が欠落。Gateway と App 横断でのデータ削除オーケストレーションが未定義。

**🔐 Auth 専門家**: 「もう一つ、アカウントリンクの話。今は Keycloak 一本だけど、テナント A は Google Workspace で SSO したい、テナント B は独自の SAML IdP がある、テナント C はパスワード認証でいい。テナントごとに認証方式が違う世界は SaaS では普通です。Gateway は複数 IdP を同時にサポートできますか？ 同じユーザーが Google と SAML の両方を紐付けたいケースは？」

→ Gap 発見: テナント別の IdP 設定（Google SSO / SAML / パスワード認証）と、同一ユーザーの複数 IdP アカウントリンク機能の設計が欠落。

**☕ ヤン**: 「…ちょっと待って。ここまで来ると、やってることは Keycloak の再発明じゃない？ Keycloak 自体がマルチレルム、IdP ブローカー、アカウントリンク、全部持ってる。Gateway でもう一層被せる必要、本当にある？ Keycloak のレルム = テナントにして、Keycloak に任せた方が早くない？」

**🔐 Auth 専門家**: 「鋭い指摘です。確かに Keycloak のレルムをテナントとして使えば、IdP 設定・ユーザー管理・role 管理は Keycloak に寄せられる。でもそうすると、テナントの追加 = Keycloak レルムの追加になって、Keycloak の運用負荷が跳ね上がる。レルム 100 個の Keycloak は現実的か。ここはトレードオフの判断が必要です。」

→ Gap 発見: Keycloak のレルム = テナントモデル vs Gateway 独自テナント管理のアーキテクチャ判断が未決定。責務分担の方針が欠落。

**💼 SaaS 専門家**: 「最後に。Gateway を更新するとき、全 App に影響する。JWT の claims 構造を変えたら、全 App のデプロイが要る。Gateway のデプロイ戦略 — blue/green、canary、feature flag — は考えてある？ 20 App が繋がってるゲートウェイを無停止で更新する方法は？」

→ Gap 発見: Gateway の無停止デプロイ戦略（blue/green・canary）と、claims 構造変更時の全 App 協調デプロイの運用手順が未定義。

---

## Gap 一覧（Round 2）

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| R2-1 | テナントのライフサイクル管理（停止・凍結・削除）と停止時の Gateway 振る舞い | Missing logic | 🟠 High |
| R2-2 | テナント作成フロー（self-service vs managed）と初期 owner 決定ロジック | Missing logic | 🟠 High |
| R2-3 | テナントのプラン / エンタイトルメント情報の配布方法 | Integration gap | 🟡 Medium |
| R2-4 | Service-to-service (M2M) 認証の設計（client credentials・サービスアカウント） | Missing logic | 🔴 Critical |
| R2-5 | 外部連携向け API キー管理（発行・失効・スコープ・テナント紐付け） | Missing logic | 🟠 High |
| R2-6 | 複数認証フロー（OIDC / client credentials / API キー）の統合アーキテクチャ | Spec-impl mismatch | 🟠 High |
| R2-7 | App 向けクライアント SDK / ライブラリの設計 | Integration gap | 🟠 High |
| R2-8 | Gateway → App 間のエラーレスポンス契約（ステータス + エラーボディ構造） | Spec-impl mismatch | 🟠 High |
| R2-9 | テナント管理 API と管理権限の階層（tenant admin / super admin） | Missing logic | 🟠 High |
| R2-10 | ユーザーデータ削除要求（Right to be Forgotten）の横断対応設計 | Legal gap | 🟠 High |
| R2-11 | テナント別 IdP 設定と複数 IdP アカウントリンク機能 | Missing logic | 🟠 High |
| R2-12 | Keycloak レルム = テナント vs Gateway 独自テナント管理のアーキテクチャ判断 | Spec-impl mismatch | 🔴 Critical |
| R2-13 | Gateway の無停止デプロイ戦略と claims 変更時の協調デプロイ手順 | Ops gap | 🟠 High |

---

## Auto-merge: 素の LLM レビュー結果（Round 2）

### Severity 昇格
- R2-1: テナント停止時の既存 JWT アクセス窓 → 🔴 Critical に昇格
- R2-7: SDK の alg:none 防止・JWKS キャッシュ → 🔴 Critical に昇格

### LLM のみで発見された追加 Gap

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| R2-14 | OAuth スコープ設計と consent 管理の Gateway/Keycloak 責務分担 | Spec-impl mismatch | 🟡 Medium |
| R2-15 | テナント単位 MFA ポリシーの強制と `amr` claim 反映 | Safety gap | 🟠 High |
| R2-16 | シークレット管理の統一方針（Vault / SOPS / dotenv・環境別注入） | Ops gap | 🟠 High |

### マージ統合: Round 2 全 16 Gap（Critical 4 / High 10 / Medium 2）
### 累計: Round 1 (22) + Round 2 (16) = 38 Gap
