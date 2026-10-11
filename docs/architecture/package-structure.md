# パッケージ構成リファレンス

cli（`modules/cli/`）の`com.dbxray` 配下の全パッケージと主要クラスの一覧。
MCPサーバー（`modules/mcp-server/`。`com.dbxray.mcp`配下）のパッケージは[mcp-server.md](./mcp-server.md)を参照。
役割の全体像は先に [overview.md](./overview.md)、ドメインの概念同士の関係・用語は [domain-model.md](./domain-model.md) を参照。

## 命名

クラス名の接尾辞で役割を表す。名前の本体（何についてのクラスか）は[domain-model.md の用語集](./domain-model.md)の用語に揃え、
接尾辞（どんな形のクラスか）は次の表に揃える。新しい種類のクラスで当てはまる接尾辞が無い場合は、既存の接尾辞を流用せず、
役割をそのまま表す名前にする（例: `TableNamePatterns`・`NodeLimit`）。
「置き場所」の列は`ArchitectureTest`で検査している（✓の行）。

| 接尾辞 | 役割 | 置き場所 | 検査 | 例 |
|---|---|---|---|---|
| `…Entity` | DBから取得したメタ情報1件のrecord | `domain.model.*` | ✓ | `TableEntity`・`ColumnEntity` |
| 複数形 | エンティティの集合（ファーストクラスコレクション）。検索・振り分けのメソッドを持つ | `domain.model.*` | | `Tables`・`ForeignKeys`・`Triggers` |
| `…Dto` | 層の境界で受け渡すだけの形（SQLの結果・コントローラーの処理結果） | `infrastructure.db.repository.dto`・`presentation.dto` | ✓ | `ColumnDto`・`ResultDto` |
| `…Repository` | 外部（DB・ファイル）とのやり取り。インタフェースはdomain、実装はinfrastructure | `domain.repository`／`infrastructure.*.repository` | ✓ | `CatalogRepository`・`PostgresCatalogRepository` |
| `…Usecase`・`…Request` | ユースケースと、その入力をまとめたrecord | `application` | ✓ | `ExportSchemaUsecase`・`ExportSchemaRequest` |
| `…Writer` | 何を・どの順で・どのファイルに書くかの段取り | `domain.service.*` | ✓ | `ErDiagramWriter`・`SchemaSnapshotWriter` |
| `…Templates` | Markdownの行・セクションの組み立て（副作用の無いstaticメソッド） | `domain.service.writer.*`（対応するWriterと同じサブパッケージ。種別をまたぐ部品は`template`） | ✓ | `ErDiagramTemplates` |
| `…Locations` | 出力物の配置・ファイル名の規則 | `domain.service.path` | ✓ | `DocumentLocations`・`SnapshotLocations` |
| `…Resolver` | 出力先パスの解決（インタフェースと実装） | `domain.service.path`／`infrastructure.path` | | `OutputPathResolver`・`DefaultOutputPathResolver` |
| `…Module` | Guiceの束縛定義 | `config.module` | | `DbxrayModule` |

## エントリーポイント（`com.dbxray`直下）

| クラス | 役割 |
|---|---|
| `Dbxray` | `main()`。処理全体（入力の検証・DBへの接続・DIコンテナの組み立てを含む）を1つのtry-catchで囲んで例外を1箇所で捕捉し、`FailureReporter`で報告したうえで、終了状態を終了コードへ変換する |
| `CliArguments`（パッケージプライベート） | CLI引数の解析（`--check`・`--rm-dist`、DB接続情報・実行時設定の上書き値）。実行時設定のCLI引数名は設定ファイルのキーから導く。解釈できない引数（書き誤り等）は`requireKnownArguments()`で誤りとする |
| `OutputDirectoryValidator`（パッケージプライベート） | 出力先（`output.path`）をDBへ接続する前に検証する。既存のファイル（ディレクトリではないもの）を指す場合と、`--rm-dist`指定時に削除してはならないディレクトリ（`OutputPathResolver.isRemovableOutputDir`）を指す場合は`UserCorrectableException`を投げる。DB種別に依存しない部品のDIコンテナから取得する |
| `DbxrayProperties`（パッケージプライベート） | 設定ファイル（`conf/config.yml`）のうち`database`以外の設定項目の仕様（位置・CLI引数名・既定値・値の形式）と検証を1箇所に持つ（ファイルの読み込みは`ConfigFile`に委ねる）。CLI引数による上書き値で上書きしてから検証する。キーの省略＝未指定、未知のキー・リストでない値・整数として読めない値・出力対象の条件の誤りは、まとめて`InvalidConfigurationException`で報告する |

## presentation層

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `presentation` | `ExportSchemaController` | エントリーポイントから呼ばれ、ユースケースを実行して結果を`ResultDto`/`DiffCheckResultDto`へ変換する。例外は捕捉せずエントリーポイントまで伝える |
| | `FailureReporter` | エントリーポイントが捕捉した例外を受け取り、利用者が直せる誤り（`UserCorrectableException`）か想定外の失敗かに応じて報告する（表示に含まれない原因の併記、想定外の失敗はスタックトレースをログへ） |
| | `DiffReportFormatter`（パッケージプライベート） | `checkDiff`の差分メッセージ組み立て。`ContentDiff`のunified diffを1オブジェクトあたり・全体それぞれ行数の上限付きで含める |
| `presentation.dto` | `ResultDto` | 通常実行（`execute`）の処理結果（成功時のメッセージ）を表すrecord。失敗時の報告は`FailureReporter`が組み立てる |
| | `DiffCheckResultDto` | `--check`モード（`checkDiff`）の処理結果（差分の報告・差分の有無）を表すrecord。差分の有無から終了状態を返す |
| `presentation.type` | `ProcessResult` | 処理結果種別（成功/失敗）のenum。コンソールに出す`[result]:`の行を組み立てる |
| | `ExitStatus` | 終了状態と終了コード（0＝成功・差分なし／1＝`--check`で差分あり／2＝失敗）のenum |

## application層

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `application` | `ExportSchemaUsecase` | DBドキュメント出力（通常実行）のユースケース。MarkdownとスナップショットのExportSinkを渡して`SchemaExportPipeline`に取得・書き出しさせる（`exportSchema`）。`--rm-dist`の削除は取得の成功後に行う（削除してよい出力先かは、入口の`OutputDirectoryValidator`が検証済み） |
| | `CheckDocumentDiffUsecase` | DB vs ドキュメントの差分検知（`--check`モード）のユースケース。スナップショットの`ExportSink`のみで一時ディレクトリへ出力し、`SnapshotDiff`で`output.path`配下の`snapshot/`と比較する（`checkDocumentDiff`） |
| | `ExportSchemaRequest`, `CheckDocumentDiffRequest` | 各ユースケースメソッドへの入力をまとめたrecord。エントリーポイント→コントローラー→ユースケースを分解・再構築せず通過する。`CheckDocumentDiffRequest`はMarkdownの描画・ER図の生成を行わないため`erDiagramMaxNodes`・`rmDist`を持たない |
| | `PreviewFeature` | `--preview`で有効にするプレビューの機能のenum（現在は関数・プロシージャの利用しているテーブル）。`ExportSchemaRequest`が有効にした機能の集合を持つ |
| | `TargetSelection` | 両requestが持つ出力対象の絞り込み条件（`TableScope`・`OutputObjectType`の集合）のrecord。`of()`で設定値の文字列を入口で型へ変換・検証する（テーブル名パターンと出力対象オブジェクト種別の誤りはまとめて報告する） |
| | `SchemaExportPipeline`（パッケージプライベート） | 両ユースケースが共有する、DBからの取得（一括取得・スキーマ単位・チャンク単位）と書き出しの段取り。取得（`fetchTargets`）と出力（`export`）を分け、書き出しは出力形式ごとの`ExportSink`に、取得した情報同士の突き合わせは`ExportTargetConsistency`に委ね、返された通知（`ConsistencyNotice`）を重要度に応じてログへ出力する。ドキュメントの生成日は`Clock`から与える |

## domain層

### domain.model

概念ごとにサブパッケージへ分けている（依存関係は [domain-model.md](./domain-model.md#概念のまとまりパッケージ) を参照）。

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `domain.model.table` | `TableEntity`, `ColumnEntity`, `IndexEntity`, `ConstraintEntity`, `TriggerEntity`, `PartitionEntity` | DBから取得したテーブルとそれに属するメタ情報のrecord群。`TableEntity`の区分は`TableType`（enum）で持ち、パーティション表のパーティションキーも持つ。`PartitionEntity`は、パーティション表の下位のパーティション（多段パーティションの中間を含む。テーブルとしては扱わない）1件 |
| | `TableKey` | スキーマ名+テーブル名の値オブジェクト（テーブルの識別子。付帯情報・関連とテーブル実体の突合キー）。`parse()`で「スキーマ.テーブル」形式の文字列を解析できる |
| | `SchemaTableKeyed` | スキーマ名・テーブル名を持つエンティティの共通IF。所属テーブルの`TableKey`を`tableKey()`で返す |
| | `Tables` | 出力対象のテーブル一覧のファーストクラスコレクション（テーブルキーでの存在判定・検索、スキーマ単位の分割） |
| | `TableDetail` | 1テーブル分の詳細情報（カラム・インデックス・制約）のrecord。`assembleAll()`で複数テーブル分の取得結果をテーブルごとに振り分ける |
| | `Triggers`, `Partitions`, `AbstractEntities` | エンティティのリストをテーブルキーで引けるようにしたコレクションとその基底クラス（`Partitions`は所属するパーティション表のキーで引く）（`Columns`・`Indexes`・`Constraints`は`TableDetail`の組み立て専用のためパッケージプライベート） |
| | `ViewReferenceEntity`, `ViewReferences` | ビュー・マテリアライズドビューが参照するテーブル（ビューを含む）1件のrecordと、ビューから参照先（`belongingTo`）・テーブルから参照しているビュー（`referencingTo`）の両方向で引けるコレクション |
| | `TableNamePatterns` | テーブル名パターン（`table=`の記法。ワイルドカード・除外・スキーマ修飾）のリストを判定する値オブジェクト。出力対象の範囲（`TableScope`）と観点の所属テーブルの指定で共通に使う。テーブル名・スキーマ名の部分が空のパターンは誤り |
| `domain.model.relation` | `ForeignKeyEntity` | 関連（DBの外部キー制約＝物理、サイドカーで宣言した論理リレーション＝論理）のrecord。参照先の`referenceTableKey()`、論理リレーションの関連名の自動生成（`resolveLogicalRelationName`）を持つ |
| | `ForeignKeys` | 物理外部キーと論理リレーションを同一集合として保持するコレクション。`physicalBelongingTo`/`logicalBelongingTo`で由来ごとに、`referencingTo`で被参照側を取り出せ、`crossSchema`でスキーマ跨ぎの関連を、`withinTables`/`crossingTableSetBoundary`でテーブルの集合の内側・境界の関連を、`neighborhoodOf`でテーブル定義書のER図に描く描画距離以内の関連を抽出する |
| | `DiagramNeighborhood` | テーブル定義書のER図に描く関連と、描いた距離（上限を超えないよう指定より縮めた場合がある）のrecord |
| | `ForeignKeyGroup`, `ForeignKeyGroups` | ER図1枚分の関連のまとまり（ノード算出・上限超過の判定・主なテーブル）と、その分割（連結成分の算出・1枚に収まる範囲でのまとめ直し。`compose()`がページ構成`PageComposition`を決める） |
| | `NodeLimit`, `RenderingPlan` | ER図1枚に描画するノード数の上限（0以下は上限なしへ正規化）と、上限との比較で決まる「描く（`Draw`）／描画を省略して一覧にフォールバック（`Omit`）」の描き方の計画 |
| | `DiagramBoxes`, `DiagramColumn` | ER図のテーブルの箱に表示する内容。論理テーブル名と、取得したカラムから関連カラム（関連の参照元・参照先として使われるカラム）だけを集めて保持し（`Builder`）、図に描く関連で使われるカラムを`FK`の有無とともに返す |
| | `Cardinality`, `RelationType` | 多重度（1対1／1対多等。判定と、論理リレーションの既定値を持つ）、関連の由来（物理／論理）のenum |
| `domain.model.schemaobject` | `FunctionEntity`, `SequenceEntity`, `TypeEntity` | テーブルに属さないスキーマ直下のオブジェクト（関数・プロシージャ／シーケンス／ユーザー定義型）のrecord。`FunctionEntity`は同名関数（オーバーロード）内の番号を持つ |
| | `Functions`, `Sequences`, `Types` | 上記エンティティのファーストクラスコレクション（取得順のリストを保持する。テーブルキーでは引かない） |
| `domain.model.tableusage` | `FunctionTableUsage`, `TableUsage` | 関数・プロシージャ1つ分の利用しているテーブル（状態・テーブルごとの操作・動的SQLの種類・不完全／オーバーロードの和の印）と、1テーブル分の利用（一致した出力対象のテーブル。スキーマが決まらない名前は候補のすべて） |
| | `CrudOperation`, `DynamicSqlKind`, `TableUsageStatus`, `SchemaResolution` | 操作（C・R・U・D）、動的SQLの種類、解析の状態（抽出しなかった・解析した・対象外の言語・定義が無い・wrap・サブプログラムが見つからない）、名前のスキーマの決まり方（決まった・実行時のsearch_path・実行者・シノニムの可能性）のenum |
| `domain.model.database` | `DatabaseEntity`, `Dbms` | DBのカタログから取得するデータベースの情報（DB名・DBMS種別・メジャーバージョン）のrecordと、DBMS種別のenum（表示名を持つ。関数の定義本体の字句の規則の切り替えにも使う） |
| | `BaseInfoEntity` | 各ドキュメントに掲載する基本情報（`DatabaseEntity`の情報＋生成日）のrecord |
| `domain.model.sidecar` | `Sidecar` | サイドカーYAMLの読み込み結果全体（手動付帯情報＋論理リレーション＋観点）を束ねるrecord |
| | `Annotations`, `TableAnnotation` | サイドカーYAML由来の手動付帯情報（テーブルキーごとの集合とその1件分） |
| `domain.model.viewpoint` | `Viewpoint`, `Viewpoints` | 観点（業務ドメイン別にテーブルをまとめる切り口）とその集合。`Viewpoint.of()`が識別子の形式・所属テーブルの指定（`TableNamePatterns`）を検証し、`resolve()`で1観点分の出力内容を求める。`Viewpoints.containing(TableEntity)`でテーブルから所属する観点を逆引きする |
| | `ViewpointContent` | 1観点分の出力内容（所属テーブル・所属テーブル同士の関連・観点外のテーブルとの関連）のrecord |
| `domain.model.target` | `TableScope` | テーブル定義出力対象の範囲（スキーマ名リスト＋テーブル名パターン）を表す値オブジェクト。実行設定から1回だけ生成し、`matches(TableEntity)`で各テーブルを判定する（パターンの判定は`TableNamePatterns`が行う） |
| | `OutputObjectType` | 追加オブジェクト（テーブル以外）の出力対象の種別のenum。`parse()`で設定値を解釈する（未指定なら全種別、未知の種別名は例外） |
| | `ExportTargets` | 一括取得する軽量な出力対象の情報（基本情報・テーブル一覧・関連・トリガー・パーティション・関数/シーケンス/型の一覧・手動付帯情報・観点）と、取得した追加オブジェクトの種別の組 |
| | `TableDefinitionContent` | 1テーブル分の出力内容を束ねるrecord（`assemble()`で`TableDetail`と一括取得分から組み立て）。出力先は持たない |
| | `FunctionDefinitionContent` | 関数・プロシージャ1つ分の出力内容（定義本体を含む関数・利用しているテーブル）のrecord。抽出を行わない実行では利用しているテーブルを`NOT_ANALYZED`とする |
| | `ConsistencyNotice` | 出力対象のテーブルと関連・付帯情報・観点を突き合わせた通知1件分の値オブジェクト（種類・メッセージ。重要度は種類が決める） |
| `domain.model.metrics` | `DatabaseMetrics`, `SchemaMetrics` | 出力対象の集計（READMEに載せる、スキーマごとのオブジェクトの数・論理名の記述状況・関連と読み解きの状況）。`DatabaseMetrics.builder(ExportTargets)`が一括取得分から数え、`collectColumns(TableDetail)`でチャンクごとにカラムを足す。取得しなかった種別は`isCounted`で判定する |
| `domain.model.snapshot` | `DatabaseSnapshot`, `TableSnapshot`, `FunctionSnapshot`, `SequenceSnapshot`, `TypeSnapshot` | スキーマのスナップショット（JSON Lines）の1行分を表すrecord群。エンティティからの変換時に、値が無いこと（空文字）をnullへ正規化する（パッケージプライベートの`SnapshotValues`） |
| | `SnapshotKind` | スキーマ単位のJSON Linesファイルに出力するオブジェクト種別（テーブル/関数/シーケンス/型）のenum。行をオブジェクトとして識別する名前（`identify`）を持つ |
| | `DiffResult` | 生成したスキーマのスナップショットとコミット済みのものの比較結果（追加/削除/内容不一致の対象一覧）を表すrecord。対象はオブジェクト（例: `table sample.employee`）またはファイル（例: `database.json`）の識別名 |
| | `ContentDiff` | 内容が一致しないオブジェクト（またはファイル）1件分の差分（対象の表示名 + unified diff形式の行リスト）を表すrecord |
| `domain.model.insight` | `ViewpointsInsight`, `FunctionTableUsagesInsight` | 参考情報（`insights`。スナップショットの事実とは別にAIへ渡す情報）の1ファイル分のrecord群。観点（DBごと）と、関数・プロシージャが利用しているテーブル（スキーマごと。プレビューの機能）。`formatVersion`を持つ |
| `domain.model.document` | `ListDocumentType` | 一覧ドキュメント（テーブル／ER図／関数・プロシージャ／シーケンス／ユーザー定義型／トリガー／観点）の種別のenum。一覧ファイル名・個別定義ディレクトリ名の接頭辞とタイトルを持つ |

### domain.repository（インターフェースのみ。実装はinfrastructure層）

| クラス | 役割 |
|---|---|
| `CatalogRepository` | データベースの情報（`selectDatabase`）・テーブル一覧・外部キー・トリガー・パーティション・関数・シーケンス・型のDB取得IF（DB種別ごとに実装が分かれる）。カラム・インデックス・制約は、指定したテーブル分をテーブルごとの`TableDetail`に組み立てて返す（`selectTableDetails`）。ER図の関連カラム用に、指定したテーブルのカラムだけも取得できる（`selectColumnList`） |
| `SidecarRepository` | サイドカーYAML（手動付帯情報・論理リレーション・観点）読み込みIF |
| `FileRepository` | ファイル操作IF（`writeFile`/`appendFile`/`createDirectory`、パスの状態の問い合わせ用の`exists`/`isDirectory`に加え、差分検知用の`listFiles`/`readFile`、一時ディレクトリ操作用の`createTempDirectory`/`deleteDirectory`、前回の参考情報を消す`deleteFile`を持つ） |

### domain.service

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `domain.service` | `UnifiedDiffGenerator` | 2つの行リストからunified diff形式の差分を生成する。Myers法による自前実装（外部ライブラリに依存しない） |
| `domain.service.export` | `ExportSink` | 取得したスキーマ情報を1つの出力形式で書き出すIF（一括取得分・関数定義・テーブル定義・集計の書き出し） |
| | `MarkdownExportSinkFactory`, `SnapshotExportSinkFactory` | 出力先（とER図のノード上限・テーブル定義書のER図の描画距離）を受け取り、Markdown／スナップショットの`ExportSink`を生成する |
| `domain.service.target` | `ExportTargetConsistency` | 出力対象のテーブルと、外部キー・サイドカー（論理リレーション／付帯情報／観点）を突き合わせる。片側が出力対象外の外部キー・論理リレーションの除外と、実在しないテーブル・カラムへの付帯情報（孤児付帯情報）・どのテーブルにも一致しない観点のパターンの検出を行う。結果は通知（`ConsistencyNotice`）として返し、ログへの出力は呼び出し側が行う |
| `domain.service.path` | `OutputPathResolver` | テーブル定義・一覧・スナップショットの出力パス生成戦略IF。データベース単位ディレクトリ（`{base}/{DB名}/`）の解決（`resolveDatabaseDirectory`）を起点に、分割ページのパスは本体ページのパスから`resolvePageFile`で求める。`--rm-dist`で削除してよい出力先かの判定（`isRemovableOutputDir`）も持つ |
| | `DocumentLocations` | Markdownドキュメントのファイル名とデータベース単位ディレクトリからの相対パス、ドキュメント間の相対リンクの規則を一元的に定める（関数・プロシージャのオーバーロードのファイル名、READMEのファイル名を含む）。`OutputPathResolver`の実装とテンプレートの双方がこの規則を参照する |
| | `SnapshotLocations` | スナップショットのディレクトリ名・ファイル名と相対パスの規則を一元的に定める。`OutputPathResolver`の実装と、比較時のファイル種別の判定の双方がこの規則を参照する |
| | `PathSegments` | DB由来の名前（DB名・スキーマ名・テーブル名等）を、パスの1要素として安全な形（区切り・`..`・Windowsで使えない文字を`~2F`等に置き換えたもの）にする。`*Locations`がパスに名前を使うときは必ず通す |
| | `OutputRoot` | 出力先ベースディレクトリと基本情報の組を表す値オブジェクト（Writer・`OutputPathResolver`へそのまま渡す） |
| `domain.service.tableusage` | `SqlLexer`, `SqlToken`, `SqlTokenKind` | 関数・プロシージャの定義本体を字句に分ける（DBごとのコメント・文字列・ドル引用符・`q'[...]'`の規則。正規表現を使わず1文字ずつ読み、閉じていない要素があっても例外にしない。正規表現を使わないことは`ArchitectureTest`が検査する） |
| | `FunctionTableUsageAnalyzer` | 関数・プロシージャの定義本体から利用しているテーブルを抽出する入口（スキーマごとに生成する）。本体の切り出し・位置の判定の結果の名前を、出力対象のテーブルと照らして解決する（スキーマ修飾・`SET search_path`・Oracleの所有者／実行者権限） |
| | `FunctionBodyLocator`, `PostgresBodyLocator`, `OracleBodyLocator`, `PackageSubprograms` | 定義から解析する本体を切り出す（`FunctionBodyLocator`がDBMSごとの実装を選ぶ。PostgreSQLはドル引用符の中身を字句に分け直す。Oracleはパッケージ本体の最上位のサブプログラムの範囲を`BEGIN`・`CASE`（文と式を区別する）と`END`の対応で求め、条件付きコンパイルの各分岐は分岐の前の状態から読み、オーバーロードを引数名で対応付ける。同じパッケージの字句を使い回す。仕様部の字句が閉じない場合は本体の始まりから分け直す） |
| | `TableReferenceScanner` | 本体の字句から、テーブルを指す位置（`INSERT INTO`・`FROM`の並び・`JOIN`等）の名前と操作、動的SQLを拾う。完全な構文解析はせず、きっかけのキーワードと前後の字句で判定し、`;`で文の状態（`StatementState`）を作り直す |
| | `SqlTokens` | 字句の列に対する、位置を指定した判定（キーワード・記号・名前）と`スキーマ.名前`の読み取り。範囲の外の位置は偽・nullにし、前後を覗く処理で位置の検査を繰り返さない |
| | `SqlNames` | 字句からカタログでの名前を求める（引用符の無い名前はPostgreSQLは小文字・Oracleは大文字へ、ASCIIの英字だけ畳み込む） |
| `domain.service.insight` | `InsightWriter` | 参考情報の書き込み（`snapshot`の兄弟の`insights/`へ。`--check`では使わない）。対象が無い場合はファイル自体を出力しない |
| `domain.service.snapshot` | `SchemaSnapshotWriter` | スキーマのスナップショット（JSON Lines）の書き込み。テーブルはスキーマ単位のファイルへ1行ずつ追記する |
| | `SnapshotDiff` | 生成したスナップショットとコミット済みスナップショットを、オブジェクト単位（追加/削除/内容不一致）で比較する（`--check`モードで使用）。内容が一致しないものは、`SnapshotSerializer.formatForDiff`で整形した上で`UnifiedDiffGenerator`によりunified diffを付ける |
| | `SnapshotSerializer` | スナップショットのrecordとJSON文字列の変換IF（実装はインフラ層）。差分表示用に1項目1行へ整形する`formatForDiff`も持つ |

ドキュメントの種別ごとに、Writer（何を・どの順で・どのファイルに書くか）とそのテンプレート（Markdownの組み立て）を同じサブパッケージに置く。
表の行を含むMarkdownの描画はすべてテンプレートで行い、Writerは描画せず、テンプレートは絞り込み・グラフ計算などのロジックを持たない。

| パッケージ | 主なクラス | 役割 |
|---|---|---|
| `domain.service.writer.tabledefinition` | `TableDefinitionWriter`, `TableDefinitionTemplates`, `TableDefinitionListTemplates` | テーブル一覧・テーブル定義書のMarkdown書き込みとテンプレート |
| `domain.service.writer.erdiagram` | `ErDiagramWriter`, `ErDiagramTemplates` | スキーマ別ER図（全体ER図）とその索引の書き込みとテンプレート。連結成分ごとのグループ分割・描画するか省くかの結果（`RenderingPlan`）に従った出力を含む |
| `domain.service.writer.viewpoint` | `ViewpointWriter`, `ViewpointTemplates` | 観点ページ（所属テーブル同士のER図・所属テーブル・観点外のテーブルとの関連）と観点一覧の書き込みとテンプレート。ER図の描画は`erdiagram`のテンプレートを使う |
| `domain.service.writer.objectlist` | `ObjectListWriter`, `ObjectListTemplates`, `ObjectDefinitionTemplates` | トリガー・関数/プロシージャ・シーケンス・ユーザー定義型の一覧および個別定義の書き込みとテンプレート |
| | `FunctionTableUsageTemplates` | 関数・プロシージャの個別定義の「利用しているテーブル」セクションのテンプレート（抽出の限界を示す文・表・スキーマが決まらない名前の候補） |
| `domain.service.writer.readme` | `ReadmeWriter`, `ReadmeTemplates` | データベース単位ディレクトリ（`{DB名}/`）のREADMEの書き込みとテンプレート。出力される一覧ドキュメントへのリンクと、出力対象の集計（`DatabaseMetrics`）をまとめる |
| `domain.service.writer` | `PagedSectionWriter` | 行数の多い表をページ分割して出力する共通処理。分割ページは本体ページと同じディレクトリに置き、ページ間のリンクはファイル名から導く |
| `domain.service.writer.template` | `MarkdownTemplateSupport`, `MermaidSupport`, `PagedSectionTemplates` | 複数の種別が共有するテンプレート部品（Markdown共通部品、Mermaid記法変換、ページ分割の見出し・リンク） |

## infrastructure層

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `infrastructure.db` | `ConnectionSettings` | 検証済みのDB接続情報。設定ファイルの`database`の値を、環境変数`DBXRAY_DB_PASSWORD`のパスワード・CLI引数の値で上書きし、組み立てる時に検証する（`driver`・`url`は必須、未知のキー・設定ファイルの`password`は誤り） |
| | `MyBatisSqlSessionFactories` | `ConnectionSettings`からMyBatisの`SqlSessionFactory`を生成する（状態を持たない。生成したものはDIコンテナで使い回す） |
| | `DatabaseTypeDetector` | DBへ接続して接続先のDB種別を判定する。DBに接続できない場合・非対応のDBの場合は`UserCorrectableException`を投げる |
| `infrastructure.db.type` | `DatabaseType` | DB種別（postgresql/oracle）と、ドメインのDBMS種別（`Dbms`）・リポジトリ実装クラスの対応enum |
| `infrastructure.db.repository` | `AbstractCatalogRepository` | Oracle/Postgres共通のリポジトリ基底クラス。`SqlSessionFactory`をコンストラクタで受け取る。SQLの失敗は、どのSQLかを添えて包む（DBが返したエラーは原因として保持する） |
| | `OracleCatalogRepository`, `PostgresCatalogRepository` | `CatalogRepository`のDB別実装。対応するSQLは`modules/cli/src/main/resources/mapper/{oracle,postgresql}/catalogMapper.xml` |
| `infrastructure.db.repository.dto` | `DatabaseDto`, `TableDto`, `ColumnDto`, `ConstraintDto`, `ForeignKeyDto`, `IndexDto`, `TriggerDto`, `PartitionDto`, `ViewReferenceDto`, `FunctionDto`, `SequenceDto`, `TypeDto` | MyBatisのResultMap受け皿となるDTO（`toEntity()`で`domain.model`配下のエンティティへ変換される） |
| | `DtoValues`（パッケージプライベート） | DTOからエンティティへの変換時の値の正規化（値が無いことを空文字へ揃える・区切り文字で連結された値をリストへ分解する） |
| `infrastructure.file.repository` | `LocalFileRepository` | `FileRepository`実装（ローカルファイルシステムへの読み書き） |
| | `SidecarYamlRepository` | `SidecarRepository`実装（サイドカーYAML読み込み、SnakeYAML使用）。`tables`（付帯情報）・`relations`（論理リレーション）・`viewpoints`（観点）を解釈する。ファイルが無い・YAMLとして解釈できない場合は`UserCorrectableException`を投げ、個々の記述の誤り（未知のキー等）は読み飛ばして警告する |
| `infrastructure.path` | `DefaultOutputPathResolver` | `OutputPathResolver`のデフォルト実装。解決したパスが起点のディレクトリ（出力先・データベース単位ディレクトリ等）の外を指さないことを確かめる |
| `infrastructure.snapshot` | `JacksonSnapshotSerializer` | `SnapshotSerializer`のJackson実装 |

## config層

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `config` | `ConfigFile` | 設定ファイル（YAML）を読み込み、最上位のキーと値の組として返す（ファイルの読み込みとYAMLとしての解析のみを担い、設定項目の仕様と検証は値を使う側が持つ）。設定ファイルが見つからない・YAMLとして読めない場合は`InvalidConfigurationException`をスローする |
| | `YamlSyntaxErrors` | YAMLの解析の失敗を、ファイルの内容を引用せずに失敗の種類と位置だけで説明する文言へ変換する（`ConfigFile`と`SidecarYamlRepository`が使う） |
| | `InvalidConfigurationException` | 設定の誤り（設定ファイルが見つからない、未知のキー、値が不正等）を表す例外（`UserCorrectableException`の派生）。`ConfigFile`・`DbxrayProperties`・`ConnectionSettings`が投げる |
| `config.module` | `DbxrayModule` | Guiceの束縛定義（IF→実装クラスの対応）のうち、DB種別に依存しないもの。DBへ接続する前に組み立て、入力の検証にも使う。新規リポジトリ/ドメインサービス追加時はここに束縛を追加する |
| | `DatabaseDependentModule` | DB種別が決まってから、`DbxrayModule`のコンテナの子として束縛するもの。接続先の`DatabaseType`と`SqlSessionFactory`をコンストラクタで受け取り、`SqlSessionFactory`を束縛して`CatalogRepository`の実装を選ぶ。それに依存するユースケースも束縛する |

## shared（レイヤーの外）

レイヤーの外に置き、どの層からも依存してよい。自身はJDK以外に依存せず、失敗の分類を伝える例外だけを置く。

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `shared.exception` | `UserCorrectableException` | 利用者が設定・入力・実行環境を見直せば解消する誤り（設定の誤り・サイドカーYAMLの構文誤り・DBに接続できない等）を表す例外。投げるのは入口（CLI引数・設定・出力先の検証）と、利用者の入力・実行環境に触れるインフラ（DBへの接続、サイドカーYAMLの読み込み）だけで、ドメイン層・アプリケーション層では投げない。`FailureReporter`はこの例外かそれ以外かで報告を切り替える |

## リソース（Java外）

| パス | 役割 |
|---|---|
| `modules/cli/src/main/resources/conf/config.yml` | 配布する設定ファイル（DB接続情報・出力対象・出力先等。全項目が未指定） |
| `modules/cli/src/main/resources/mybatis-config.xml` | MyBatisのメイン設定（DB種別ごとのmapper読み込み等） |
| `modules/cli/src/main/resources/mapper/oracle/catalogMapper.xml` | Oracle向けSQL定義 |
| `modules/cli/src/main/resources/mapper/postgresql/catalogMapper.xml` | PostgreSQL向けSQL定義 |
| `modules/cli/src/main/resources/log4j2.xml` | ログ設定（ログファイルへの出力に加え、このツールのWARNログを`[warn]:`付きで標準エラー出力へ出す） |

## テスト

`modules/cli/src/test/java/com/dbxray` 配下は本体パッケージとほぼ1:1で対応する構成
（`application`, `config`, `domain`, `infrastructure`, `presentation`, `testsupport`）。
`testsupport`にはテスト用のビルダー・フィクスチャ等の共通部品を置く
（`MarkdownAssert`、`EntityFixtures`、`ForeignKeyFixtures`など）。
直下の`ArchitectureTest`は、レイヤーの依存方向・`shared.exception`の制約・`domain.model`配下の循環・
接尾辞とパッケージの対応（[命名](#命名)）等の構造の規約をArchUnitで検査する。
