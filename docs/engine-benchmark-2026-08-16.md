# Engine Benchmark — 2026-08-16

 tramli `FlowEngine` (状態マシンエンジン) のベンチマーク改善記録。
 対象: `recordTransition` CPU 経路（DB 往復なし）。

 ## 測定環境

 - Java 21.0.9 LTS (HotSpot 64-Bit Server VM)
 - Linux x86_64
 - tramli 3.7.1 (jar 配布物、本体は不変)
 - JMH は未導入のため、`System.nanoTime()` + warmup/measurement 区間分割で簡易計測
   - DCE 対策: 結果を `volatile` シンクへ消費
   - プロファイル汚染対策: 3 fork で中央値を採用
   - warmup: 5 iter x 20000 ops (100K)、measure: 5 iter x 20000 ops (200K total)

 ## 対象経路

 `SqlFlowStore.recordTransition` が1遷移ごとに呼ぶ CPU 処理:
 1. `FlowDataRegistry.serialize(ctx)` — 全 FlowData を Jackson `convertValue` で Map 化
 2. `SensitiveRedactor.redact(serialized, registry)` — `@Sensitive` フィールドをマスキング
 3. `objectMapper.writeValueAsString(redacted)` — JSON 文字列化

 1 OIDC フローは5遷移するので、この経路が5回呼ばれる。
 `SqlFlowStore.save` も `serializeContext` を呼ぶ（redact なし）ので、1 フローで合計7回の serialize。

 ## 結果

 ### 反復1: baseline → SensitiveRedactor リフレクションキャッシュ追加

 | 指標 | baseline (元実装) | 反復1後 | 改善 |
 |---|---|---|---|
 | `SensitiveRedactor.redact` (ns/op) | 282,749 | 791 | **357x** |
 | `recordTransition` CPU 経路 (ns/op) | 323,435 | 11,313 | **28.6x** |
 | `recordTransition` CPU 経路 (ops/sec) | 3,091 | 88,393 | **28.6x** |

 元実装の `redact` は毎回 `clazz.getRecordComponents()` を呼び、各 component の `isAnnotationPresent(Sensitive.class)` をチェックしていた。クラス定義は不変なので `ConcurrentHashMap<Class<?>, Set<String>>` でキャッシュ。

 ### 反復2: redactFields の不要 Map コピー削除

 `@Sensitive` フィールドが無いクラスは入力 Map をそのまま返す（コピー不要）。

 | 指標 | 反復1後 | 反復2後 | 改善 |
 |---|---|---|---|
 | `recordTransition` CPU 経路 (ns/op) | 11,313 | 11,025 | 2.5% |

 効果は微細（`redact` 自体が既に1µs以下だったため）。テスト全件通過で維持。

 ### 反復3: FlowDataRegistry.serialize の TypeReference 最適化

 `convertValue(value, Map.class)` を `convertValue(value, TypeReference<Map<String, Object>>)` に変更。
 `TypeFactory.constructType(Map.class)` の per-call overhead を削減。

 | 指標 | 反復2後 | 反復3後 | 改善 |
 |---|---|---|---|
 | `recordTransition` CPU 経路 (ns/op) | 11,025 | 10,757 | 2.4% |
 | `recordTransition` CPU 経路 (ops/sec) | 90,700 | 92,959 | 2.5% |

 ### 全体改善

 | 指標 | baseline | 最終 | 改善 |
 |---|---|---|---|
 | `recordTransition` CPU 経路 (ns/op) | 323,435 | 10,757 | **30.1x** |
 | `recordTransition` CPU 経路 (ops/sec) | 3,091 | 92,959 | **30.1x** |

 ## InMemoryFlowStore の full flow ベンチマーク (参考)

 tramli 本体の `FlowEngine` + `InMemoryFlowStore` で1フロー（startFlow + resumeAndExecute）を実行した際のスループット。
 これは `recordTransition` の最適化とは別軸（tramli 本体のホットパス）。

 | fork | ops/sec | ns/op |
 |---|---|---|
 | 1 | 97,587 | 10,247 |
 | 2 | 100,535 | 9,947 |
 | 3 | 114,930 | 8,701 |
 | **中央値** | **100,535** | **9,947** |

 JFR プロファイルで tramli 本体のホットスポット:
 1. `InMemoryFlowStore.create` — HashMap.put で resize 多発（flows が際限なく成長）
 2. `FlowContext.snapshot()` — 毎回 LinkedHashMap コピー
 3. `dispatchStep` 内 `transitionsFrom(state)` — stream filter + toList で毎回新 List 生成

 これらは tramli 本体（jar 配布物）のため、本リポジトリからは直接変更不可。
 `FlowDefinition` は `final class` で継承も不可。アーキテクチャ変更（tramli fork）が必要なため、今回は見送り。

 ## 変更ファイル

 - `src/main/java/org/unlaxer/infra/volta/flow/SensitiveRedactor.java` (+37/-15)
   - `ConcurrentHashMap<Class<?>, Set<String>>` で `@Sensitive` フィールド名をキャッシュ
   - `redactFields` で sensitive 無しクラスは Map コピーを省略
 - `src/main/java/org/unlaxer/infra/volta/flow/FlowDataRegistry.java` (+6/-1)
   - `TypeReference<Map<String, Object>>` を static final で保持し `convertValue` の型構築 overhead を削減

 ## テスト

 - `SensitiveRedactorTest` (2件) — 全通過
 - `FlowDataRegistryTest` (5件) — 全通過
 - `RiskAndMfaBranchTest` (5件) — 全通過
 - `OidcFlowDefTest` (4件) — 環境制約（Nexus依存の propstack 未解決）で未検証。変更とは無関係。

 ## 外部データの出典・ライセンス

 | # | URL | 取得日 | ライセンス |
 |---|-----|--------|-----------|
 | 1 | https://github.com/hekailiang/squirrel (README, User Guide) | 2026-08-16 | Apache License 2.0 |
 | 2 | https://doc.akka.io/libraries/akka-core/current/typed/fsm.html | 2026-08-16 | Business Source License 1.1 (© 2011-2026 Lightbend) |
 | 3 | https://docs.spring.io/spring-statemachine/docs/current/reference/ | 2026-08-16 | Apache 2.0 (spring-attic, 2026-07-05アーカイブ) |
 | 4 | https://github.com/spring-attic/spring-statemachine | 2026-08-16 | Apache 2.0 |
 | 5 | https://openjdk.java.net/projects/code-tools/jmh/ | 2026-08-16 | GPLv2 (OpenJDK) |
 | 6 | https://github.com/openjdk/jmh (README, LICENSE) | 2026-08-16 | GPL-2.0-only |
 | 7 | https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples | 2026-08-16 | GPL-2.0 |
 | 8 | https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_01_HelloWorld.java | 2026-08-16 | GPL-2.0 |
 | 9 | https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java | 2026-08-16 | GPL-2.0 |
 | 10 | https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_09_Blackholes.java | 2026-08-16 | GPL-2.0 |
 | 11 | https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_11_Loops.java | 2026-08-16 | GPL-2.0 |
 | 12 | https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java | 2026-08-16 | GPL-2.0 |
 | 13 | https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_13_RunToRun.java | 2026-08-16 | GPL-2.0 |
 | 14 | https://www.postgresql.org/docs/current/explicit-locking.html | 2026-08-16 | PostgreSQL License (© 1996-2026 PostgreSQL Global Development Group) |
 | 15 | https://www.postgresql.org/docs/current/datatype-json.html | 2026-08-16 | PostgreSQL License |
 | 16 | https://www.postgresql.org/docs/current/pgbench.html | 2026-08-16 | PostgreSQL License |

 ## 再現手順

 ```bash
 # クラスパス構築（mvn が無い環境向け）
 CP="target/classes:target/test-classes"
 CP="$CP:/home/opa/.m2/repository/org/unlaxer/tramli/3.7.1/tramli-3.7.1.jar"
 # jackson
 for j in jackson-databind jackson-core jackson-annotations; do
   CP="$CP:$(find ~/.m2/repository -name "${j}-2.18.4.jar" | head -1)"
 done
 CP="$CP:$(find ~/.m2/repository -name 'jackson-datatype-jsr310-2.18.4.jar' | head -1)"
 # slf4j, junit, nimbus, propstack 等（テスト用）

 # ベンチマークコンパイル・実行
 javac -cp "$CP" -d /tmp/bench /tmp/bench/BenchRedactOpt.java
 java -cp "$CP:/tmp/bench" BenchRedactOpt
 ```

 ## 次の判断（人間ゲート候補）

 1. **tramli 本体の最適化**（`FlowDefinition.transitionsFrom` のキャッシュ、`InMemoryFlowStore` の容量上限）は、tramli リポジトリの fork が必要。アーキテクチャ変更に該当するため要判断。
 2. **`SqlFlowStore` の `recordTransition` バッチ化**（5遷移分の INSERT を1トランザクションにまとめる）は、監査ログの即時永続性を低下させるため、運用要件の確認が必要。
 3. **JMH の正式導入**（`pom.xml` に `jmh-core` 依存追加）は、ビルド設定変更。ベンチマーク精度を上げるなら推奨。
 4. **PostgreSQL 実環境でのベンチマーク**は、`docker-compose up -d` でDBを立てて `SqlFlowStore` 経路を測定する必要がある。今回は環境制約で未実施。
