# Elasticsearch

[English version](elasticsearch.md)

---

## これは何？

Elasticsearchは、Apache Lucene上に構築された分散型の検索・分析エンジンです。データをJSONドキュメントとして保存し、そのデータをほぼリアルタイムで検索、フィルタリング、集計できます。元々は全文検索（数百万のドキュメントからキーワードを検索する）用に設計されましたが、ログストレージ、メトリクス分析、アプリケーション検索のデファクトソリューションになっています。

Elasticsearchは写真のような記憶力を持つ司書のようなものです。司書に数百万冊の本（ドキュメント）を渡すと、すべての本のすべての単語のインデックスを構築します。「1月から3月の間に『認証失敗』に言及しているすべての本を見つけて」と聞くと、ミリ秒で答えます。通常のデータベース（Postgres）は、質問するたびにすべての本を読み通す司書のようなもの -- 動きますが、この種のクエリにはずっと遅いです。

Elasticsearchは通常、「ELK Stack」（Elasticsearch + Logstash + Kibana）の一部としてデプロイされます。Logstashがデータを収集・変換し、Elasticsearchが保存・インデックス化し、Kibanaが可視化のためのWebダッシュボードを提供します。

---

## なぜ重要なのか？

volta-auth-proxyのようなSaaS IDゲートウェイにとって、監査ログは極めて重要です。以下のような質問に答える必要があります：

- 「過去24時間のテナントacme.comのすべてのログイン失敗を表示して」
- 「今週、最も多くの認証リクエストを生成したIPアドレスは？」
- 「ユーザーalice@acme.comが最後にログインしたのはいつ、どこから？」
- 「認証トラフィックに異常なパターンはあるか？」

これらは検索と集計のクエリです -- まさにElasticsearchが得意とするものです。Postgresも監査ログを保存できますが、以下に苦労します：

1. ログメッセージの**全文検索**
2. **時系列集計**（毎分のリクエスト数、エラー率のトレンド）
3. **高い書き込みスループット**（毎秒数千のログエントリ）
4. データベースパフォーマンスに影響を与えない**長期保持**

---

## どう動くのか？

### コア概念

| 概念 | Postgresの同等物 | 説明 |
|------|-----------------|------|
| **インデックス** | テーブル | ドキュメントのコレクション（例：`volta-audit-2024-01`） |
| **ドキュメント** | 行 | 単一のJSONレコード |
| **フィールド** | カラム | JSONドキュメントのキー |
| **マッピング** | スキーマ | フィールドタイプの定義（text、keyword、dateなど） |
| **シャード** | パーティション | インデックスの水平スライス（分散用） |
| **レプリカ** | リードレプリカ | シャードのコピー（冗長性と読み取りスループット用） |

### インデックスの仕組み

Elasticsearchにドキュメントを送信すると：

1. JSONをパース
2. テキストフィールドを分析（トークン化、小文字化、ステミング）
3. **転置インデックス**を構築（単語 → その単語を含むドキュメントのリスト）
4. 元のドキュメントを保存

### 監査ログ用のElasticsearch vs 代替手段

| 機能 | Elasticsearch | PostgreSQL | [Kafka](kafka.md) + ClickHouse | Loki |
|------|--------------|-----------|-------------------------------|------|
| 全文検索 | 優秀 | 基本的（ts_vector） | 限定的 | 基本的（LogQL） |
| 集計 | 優秀 | 良好（ただし遅い） | 優秀 | 基本的 |
| 書き込みスループット | 非常に高い | 中程度 | 非常に高い | 高い |
| ストレージ効率 | 中程度 | 良好 | 優秀 | 優秀 |
| クエリ言語 | Query DSL / KQL | SQL | SQL（ClickHouse） | LogQL |
| 可視化 | Kibana | pgAdmin / Grafana | Grafana | Grafana |
| 運用の複雑さ | 高い（クラスター管理） | 低い | 高い | 低い |
| コスト（大規模時） | 高い（RAM大食い） | 低い | 中程度 | 低い |

---

## volta-auth-proxy ではどう使われている？

Elasticsearchはvolta-auth-proxyの**オプションの外部監査ログシンク**です。デフォルトでは、voltaは監査イベントをPostgresデータベースに保存します。高度な検索、長期保持、または既存のELKインフラとの統合が必要な組織のために、voltaは監査イベントをElasticsearchにも送信できます。

### アーキテクチャ

```
  volta-auth-proxy
       │
       │  監査イベント（ログイン、ログアウト、失敗など）
       │
       ├──► PostgreSQL（常時 -- プライマリストレージ）
       │
       └──► Elasticsearch（オプション -- 外部シンク）
                │
                ▼
            Kibana（可視化/ダッシュボード）
```

### Elasticsearchに送信されるもの

| イベントタイプ | フィールド |
|-------------|---------|
| `auth.login.success` | timestamp, user_id, tenant_id, email, ip, user_agent |
| `auth.login.failed` | timestamp, email_attempted, tenant_id, ip, user_agent, reason |
| `auth.logout` | timestamp, user_id, tenant_id, ip |
| `auth.session.expired` | timestamp, user_id, tenant_id, session_id |
| `auth.token.issued` | timestamp, user_id, tenant_id, token_type |
| `admin.user.created` | timestamp, admin_id, tenant_id, target_user_id |
| `admin.tenant.updated` | timestamp, admin_id, tenant_id, changes |

### なぜオプションで必須ではないのか？

voltaの哲学は最小限の依存関係です。多くのデプロイでは監査ログにPostgresだけで十分です -- SQLでクエリすれば十分です。Elasticsearchは以下を追加します：

- **運用の複雑さ**：管理、監視、バックアップするクラスター
- **リソース使用量**：ElasticsearchはRAM大食い（ノードあたり1GB+のRAMを計画）
- **コスト**：Elastic Cloud料金またはセルフホストインフラコスト

小〜中規模のデプロイにはPostgresで十分です。Elasticsearchが意味を持つのは：

- 毎分数千の認証イベントがある
- 監査ログの全文検索が必要
- 他のサービスですでにELKスタックを運用している
- コンプライアンスが高速検索付きの長期ログ保持を要求

---

## よくある間違いと攻撃

### 間違い1：Elasticsearchを保護しない

ElasticsearchのOSS版にはデフォルトで認証がありません。インターネットに公開すると、誰でも監査ログ（メールアドレス、IPアドレス、テナント情報を含む）を読めます。常にファイアウォールの背後で実行し、Elasticのセキュリティ機能を使用するか、VPNを使用してください。

### 間違い2：インデックスライフサイクル管理を設定しない

監査ログのインデックスは永遠に成長します。Index Lifecycle Management（ILM）なしでは、ディスクスペースが枯渇します。ILMを設定してインデックスを自動的にロールオーバー（例：毎日）し、古いものを削除（例：90日後）してください。

### 間違い3：インデックスしすぎる

すべてのフィールドを`text`（全文検索可能）と`keyword`（完全一致）の両方でインデックスすると、ストレージが倍になります。監査ログでは、ほとんどのフィールドは`keyword`（完全一致で十分）にすべきです。全文検索が本当に必要なフィールドのみ`text`でインデックスしてください。

### 間違い4：Elasticsearchをプライマリデータストアとして使用する

Elasticsearchは検索エンジンであり、データベースではありません。レプリカが設定されていない場合、ノード障害時にデータを失う可能性があります。常にPostgresを真実のソースとして保持し、Elasticsearchはセカンダリシンクとして使用してください。

### 攻撃：監査ログの改ざん

攻撃者がElasticsearchへの書き込みアクセスを得た場合、監査ログを変更・削除して痕跡を隠せます。書き込み一度きりのインデックス（またはElasticsearchのデータストリーム不変性機能）を使用し、フォレンジック目的で別の強化されたクラスターにログを転送してください。

---

## さらに学ぶ

- [Elasticsearchドキュメント](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html) -- 公式リファレンス。
- [Kibanaドキュメント](https://www.elastic.co/guide/en/kibana/current/index.html) -- 可視化ツール。
- [Elastic Security](https://www.elastic.co/guide/en/elasticsearch/reference/current/security-minimal-setup.html) -- 認証の設定。
- [kafka.md](kafka.md) -- 代替/補完的な監査ログシンク。
- [docker.md](docker.md) -- voltaとElasticsearchの実行方法。
