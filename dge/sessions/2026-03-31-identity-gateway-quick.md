# DGE Session — Identity Gateway 設計
- **Date**: 2026-03-31
- **Flow**: quick
- **Structure**: roundtable
- **Theme**: Identity Gateway — auth / signup / user attribute / role / multitenancy を前段サーバーに集約する設計
- **Characters**: 今泉, ヤン, 千石, Red Team, 僕

---

## Scene 1: 基本フロー — 「そもそも前段で何を持つのか」

**先輩**: 「構成を整理する。Traefik → Identity Gateway → 各 App。Gateway は OIDC login、signup、tenant 解決、role mapping、signed JWT 発行を担う。App 側は JWT 検証と業務認可だけ。目標は "サービスを爆速で量産する" こと。」

**👤 今泉**: 「そもそも、Identity Gateway が発行する internal JWT と、Keycloak が発行する OIDC token って、2 種類のトークンが飛び交うわけですよね？ App 側はどっちを見るんですか？ 両方見る？ 片方？ そこ、決まってます？」

→ Gap 発見: Internal JWT と IdP token の境界が未定義。App が検証すべきトークンの仕様が決まっていない。

**☕ ヤン**: 「…紅茶飲みながら言うけど、そもそも internal JWT を独自発行する必要あるの？ Keycloak の token をそのまま通せば、署名検証のコードも鍵ローテーションも Keycloak に任せられる。Gateway が token を再発行するって、つまり Gateway 自体が IdP になるってことだよ。それ、やりたいの？」

**👤 今泉**: 「要するに、Gateway が token 再発行するなら、Gateway の署名鍵の管理、ローテーション、JWK エンドポイント、全部自前ってことですよね？」

→ Gap 発見: Gateway が JWT を自前発行する場合の鍵管理（生成・ローテーション・配布・失効）の設計が欠落。

**🎩 千石**: 「ヤンさんの指摘はもっともですが、Keycloak の token をそのまま通す場合、tenant や内部 role のような Keycloak に無い文脈はどこで載せるんですか。claims mapper で Keycloak 側に押し込む？ それは Keycloak の設定に業務知識が漏れるということです。それは品質として許容できません。」

**☕ ヤン**: 「…じゃあ妥協点として、Keycloak token は認証に使って、Gateway が追加 claims を載せた internal token を発行する。ただし署名は Keycloak の鍵を使わず、Gateway 専用の鍵ペアで。App は internal token だけ見る。シンプルでしょ。」

**😰 僕**: 「...でも、それって Gateway が単一障害点になりませんか？ Gateway 落ちたら全 App 死にますよね... せめてヘルスチェックと、Gateway 障害時に App が graceful degradation できる設計って...」

→ Gap 発見: Identity Gateway の可用性設計（冗長化・ヘルスチェック・障害時の App 側挙動）が未定義。

---

## Scene 2: Signup と Tenant — 「誰を入れて、どこに紐づけるか」

**先輩**: 「現状は CF Zero Trust + Gmail ドメイン制限。これを Gateway に移行して signup / invitation を入れたい。tenant 解決は email domain / invite code / URL subdomain / 明示選択の 4 パターン。」

**👤 今泉**: 「そもそも、email domain で tenant を決めるって、同じドメインで別会社ってないんですか？ gmail.com とか、フリーメールのドメインが来たらどうなるんですか？」

→ Gap 発見: フリーメールドメイン（gmail.com, outlook.com 等）による tenant 誤マッチの考慮が欠落。tenant 解決の優先順位ルールが未定義。

**🎩 千石**: 「招待制と自由 signup が混在する場合を考えてください。ユーザー A が company-x の招待で参加した後、同じメールで company-y にも自分で signup できる。マルチテナントの membership はそれでいいんですか？ 一人が複数 tenant に所属するモデルなのか、所属は一つなのか。これはデータモデルの根幹です。」

**👤 今泉**: 「誰が困るの、って考えると... tenant admin が "うちのメンバー一覧" を見たとき、他の tenant にも所属してるユーザーが見えるんですか？ 見えちゃダメですよね？」

→ Gap 発見: ユーザーの複数 tenant 所属モデルの仕様（許可するか・membership の可視性・tenant 間のデータ分離）が未定義。

**😈 Red Team**: 「招待コードの話をしよう。招待コードが推測可能だったら？ 期限は？ 使用回数制限は？ 招待コードを URL に載せるなら、リファラーやアクセスログから漏洩する。それを使って攻撃者が tenant に参加できる。」

→ Gap 発見: 招待コードのセキュリティ仕様（エントロピー・有効期限・使用回数・漏洩対策）が未定義。

**😰 僕**: 「...signup のフローで、メール認証って必要ですよね... でも確認メール送るってことはメール送信の仕組みも Gateway に必要で... SMTP とか... それって結構重くないですか...」

→ Gap 発見: メール送信基盤（確認メール・招待メール・パスワードリセット等）の設計が欠落。Gateway の責務が膨張するリスク。

---

## Scene 3: App との契約 — 「Schema JSON でやりとり」

**先輩**: 「目標は "App と Gateway が API / JSON schema でやりとりして、App 側は auth を何も考えない" こと。JWT の claims がその契約になる。」

**☕ ヤン**: 「契約って言うなら、schema のバージョニングは？ Gateway が claims に `department` を追加したら、古い App は無視する？ 壊れる？ App が 10 個あるとき、全部同時にデプロイしないと動かないとか言わないよね？」

→ Gap 発見: JWT claims schema のバージョニング戦略・後方互換性ルールが欠落。

**🎩 千石**: 「JWT の claims に user attributes を全部載せるのは危険です。department、locale 程度ならいいですが、電話番号や住所のような PII を JWT に入れたら、ログに残り、ブラウザの DevTools で見え、中間キャッシュに乗る。JWT は暗号化されていない限り、ただの Base64 です。」

→ Gap 発見: JWT claims の PII ポリシー（何を claims に入れ、何を API 経由にするか）が未定義。

**😈 Red Team**: 「App が Gateway の JWT だけを信用する設計は分かった。でも、App に直接アクセスされたら？ Traefik を迂回して App のポートに直接 HTTP を投げたら、JWT のヘッダを偽装できる。internal network の信頼前提が崩れたら全滅だよ。」

**🎩 千石**: 「だからこそ、App 側での JWT 署名検証は省略してはいけません。ヘッダだけ信用する設計は論外です。」

→ Gap 発見: App 直接アクセス防止策・JWT 署名検証必須化の設計方針が欠落。

---

## Scene 4: 運用 — 「量産した後の世界」

**先輩**: 「"サービスを爆速で量産" が目標。10 個、20 個の App が Gateway の後ろに並ぶ世界を考える。」

**👤 今泉**: 「前もそうだったっけ？ って思うんですけど、App ごとに "この App はどの role が使える" っていう設定、どこで管理するんですか？ Gateway？ App 自身？ 設定ファイル？ DB？ App が 20 個になったとき、role の管理画面って... 誰が見るんですか？」

→ Gap 発見: App ごとの role/permission マッピング管理方法と管理 UI が欠落。

**😈 Red Team**: 「量産するってことは、App ごとにセキュリティレベルが違う。管理画面は admin only、公開 API は全ユーザー。Gateway 側で App ごとのアクセスポリシーを持つのか、App に任せるのか。任せるなら、新人が作った App にバグがあって全 tenant のデータが見えるのは誰の責任？」

→ Gap 発見: App ごとのセキュリティレベル分類・Gateway 側粗粒度アクセス制御が未定義。

**☕ ヤン**: 「...あと、ログアウトどうするの？ Gateway でセッション切っても、App 側にキャッシュされた JWT は有効期限まで生きてる。全 App に "このユーザー無効になったよ" って伝える仕組み、要るんじゃない？ ...まあ、JWT の有効期限を短くすればいいって言う人もいるけど。」

→ Gap 発見: ログアウト・セッション無効化の全 App 伝播メカニズムが未設計。

**😰 僕**: 「...ところで、これ全部ローカルで動かすんですよね... Keycloak + Traefik + Gateway + Postgres + Redis... docker-compose がめちゃくちゃ重くなりません...? 開発者の MacBook で動くんですかね...」

→ Gap 発見: ローカル開発環境の構成重量・DX 設計が未検討。

---

## Gap 一覧

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| 1 | Internal JWT と IdP token の境界・App が検証すべき token 仕様が未定義 | Spec-impl mismatch | 🔴 Critical |
| 2 | Gateway の JWT 自前発行時の鍵管理（生成・ローテーション・配布・失効）設計が欠落 | Missing logic | 🔴 Critical |
| 3 | Identity Gateway の可用性設計（冗長化・障害時 App 挙動）が未定義 | Ops gap | 🟠 High |
| 4 | フリーメールドメインでの tenant 誤マッチ・tenant 解決の優先順位ルールが未定義 | Missing logic | 🔴 Critical |
| 5 | ユーザーの複数 tenant 所属モデル（許可・可視性・データ分離）が未定義 | Missing logic | 🟠 High |
| 6 | 招待コードのセキュリティ仕様（エントロピー・期限・使用回数・漏洩対策）が未定義 | Safety gap | 🟠 High |
| 7 | メール送信基盤の設計欠落・Gateway 責務の膨張リスク | Integration gap | 🟡 Medium |
| 8 | JWT claims schema のバージョニング戦略・後方互換性ルールが欠落 | Spec-impl mismatch | 🟠 High |
| 9 | JWT claims の PII ポリシー（何を載せ、何を API 経由にするか）が未定義 | Safety gap | 🟠 High |
| 10 | App 直接アクセス防止策・JWT 署名検証必須化の設計方針が欠落 | Safety gap | 🔴 Critical |
| 11 | App ごとの role/permission マッピング管理方法と管理 UI が欠落 | Missing logic | 🟡 Medium |
| 12 | App ごとのセキュリティレベル分類・Gateway 側粗粒度アクセス制御が未定義 | Safety gap | 🟡 Medium |
| 13 | ログアウト・セッション無効化の全 App 伝播メカニズムが未設計 | Missing logic | 🟠 High |
| 14 | ローカル開発環境の構成重量・DX 設計が未検討 | Ops gap | 🟡 Medium |

---

## Auto-merge: 素の LLM レビュー結果

### LLM のみで発見された追加 Gap

| # | Gap | Category | Severity |
|---|-----|----------|----------|
| L1 | トークン失効・無効化メカニズム（revocation list / 短寿命 + refresh）が未設計 | Missing logic | 🔴 Critical |
| L2 | セッション管理の具体実装（ストア選定・セッション固定攻撃対策・同時ログイン制御）が未定義 | Missing logic | 🟠 High |
| L3 | 監査ログが Phase 4 に後回し — 認証イベントのログは Phase 1 から必須 | Ops gap | 🟠 High |
| L4 | IdP (Keycloak) 障害時のフォールバック・タイムアウト・縮退運転が未検討 | Ops gap | 🟠 High |
| L5 | CSRF 対策の設計が明示されていない | Safety gap | 🟠 High |
| L6 | email_verified のバイパスリスク（IdP 設定ミスで未検証メールが通過） | Safety gap | 🟠 High |
| L7 | Cloudflare Zero Trust からの移行パス（並行稼働・ロールバック手順）が不明確 | Ops gap | 🟡 Medium |
| L8 | レート制限（ログイン試行・signup・招待コード）が未定義 | Safety gap | 🟡 Medium |

### マージ統合: 全 22 Gap（Critical 5 / High 11 / Medium 6）
