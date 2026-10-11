# MCPサーバー（mcp-server）

cliが出力したスキーマのスナップショット（JSON Lines）を、AIからMCP（Model Context Protocol）のツールで検索できるようにする
サブプロジェクト（`modules/mcp-server/`）。DBには接続せず、スナップショットのファイルだけを読む。
ツールのほかに、利用者が会話にテーブルの定義を添付するためのリソースも出す（[リソース](#リソース)）。
別のリポジトリでコードを書くAIが、テーブル定義・JOINの条件（外部キーと論理リレーション）を引けるようにすることが目的。

cliのアーキテクチャ（[overview.md](./overview.md)）とは独立している。cliのコードには依存せず、両者の接点はスナップショットの形式だけ。

初めてコードを読む人向けに、MCPとMCP Java SDKの事前知識、起動から1回のツール呼び出しまでの流れ、ソースごとの役割と繋がりを
図でまとめた読解ガイド（[mcp-server-guide.html](./mcp-server-guide.html)。ブラウザで開く）がある。ガイドはある時点のコードを読んで
書いたもので、クラス名・行数等が食い違う場合はこの文書とコードを正とする。

## 構成と依存の向き

依存の向きは`tool → catalog ← snapshot`・`catalog ← insight`。検索・関連のたどりは`catalog`に置き、MCPのSDKにもJSONにも依存させない
（cliの`presentation → application → domain ← infrastructure`と同じ考え方で、中心のロジックを単体テストしやすくする）。
`snapshot`と`insight`は互いに依存しない（読み込み元のディレクトリが異なる、独立した入力源）。
これらの依存の向きと、標準出力へ書き込まないこと・cliのコードに依存しないことは`ArchitectureTest`（ArchUnit）が単体テストとして検査する。
直下の`UserCorrectableException`だけは、読み込みの失敗を伝えるため`snapshot`・`insight`からも使う。

| パッケージ | 主要クラス | 役割 |
|---|---|---|
| `mcp`（直下） | `McpServerMain` | エントリーポイント。起動引数の検証とスナップショット・参考情報の読み込みの後、stdioのトランスポートでサーバーを起動する |
| | `ServerArguments` | 起動引数（`--snapshot=<ディレクトリ>`）の解釈と検証 |
| | `UserCorrectableException` | 利用者が起動引数・スナップショットを見直せば解消する失敗。起動時に標準エラーへ出して終了コード2で終了する |
| `mcp.catalog` | `SchemaCatalog` | 全オブジェクトを保持し、関心ごとの窓口（`tables()`・`functions()`・`sequences()`・`types()`・`viewpoints()`・`relations()`）を返す。自身が持つのは複数の窓口にまたがる処理（スキーマごとの数・テーブルのまとまり）だけ。観点（`ViewpointEntry`）は`withViewpoints`で組み立て後に合成する |
| | `TableCatalog` | テーブル（ビューを含む）の検索・一覧・名前の解決、カラムの逆引き、トリガーの一覧と、テーブルを走査して求める相互参照（テーブルを参照するビュー、関数を実行するトリガー、シーケンス・型を使うカラム） |
| | `ObjectCatalog` | 関数（`FunctionOverloads`）・シーケンス・ユーザー定義型に共通の、一覧と名前の解決（見つからない場合の候補を含む）。種類を足しても`SchemaCatalog`にメソッドは増えない。型の種別での絞り込み等、種類に固有の条件はツール側で行う |
| | `ViewpointCatalog` | 観点の一覧・識別子での解決と、テーブルから所属する観点の逆引き（`containing`） |
| | `SearchQuery` | テーブルの検索語。空白区切りのAND、NFKC正規化＋小文字化した部分一致、項目ごとの点数（テーブル名＞論理名＞カラム＞説明・備考） |
| | `ColumnQuery`・`ColumnHit` | カラムの逆引きの条件（物理名・論理名の完全一致／部分一致）と、当てはまったカラム（参照先を含む） |
| | `ObjectReference`・`Lookup` | 名前で指定されたオブジェクト（`スキーマ名.名前`も可）と、その解決結果（1つに定まる・複数ある・見つからない）。テーブル以外の種類にも共通で使う |
| | `RelationGraph` | テーブルを頂点、関連（外部キー・論理リレーション）を辺とするグラフ。被参照側の逆引きの索引を持ち、関連の数・関連のたどり・指定したテーブル同士の関連（観点のER図）・最短のJOIN経路の探索を担う。ツールは`SchemaCatalog#relations`から直接呼ぶ |
| | `Relation`・`RelatedTables`・`JoinPath`・`JoinPaths` | テーブル間の関連と、幅優先でたどった結果・2つのテーブルをつなぐ最短経路 |
| | `DiagramScope` | ER図に描くテーブルと関連（関連をたどった結果、または観点の所属テーブル同士の関連） |
| | `ClusterDetector`・`TableClusters`・`TableCluster` | 関連のつながりだけから推測するテーブルのまとまり（`list_table_clusters`の元）。連結成分を1つのまとまりとし、上限を超えるまとまりは被参照の最も多いテーブル（`TableClusters.HUB_MIN_INCOMING`以上から参照される共通のマスタ）をハブとして除いて分け直す。ハブとの間にしか関連の無いテーブルは、被参照の最も多いハブごとにまとめる。呼び出しのたびに求める（範囲・上限が引数で変わるため） |
| | `SqlNames`・`TableColumn` | カラムの型・デフォルト値（`nextval`）・トリガーの関数名に現れるオブジェクトの名前の判定（相互参照に使う）と、カラムとそれを持つテーブル |
| | `TableEntry`・`ColumnEntry`・`RelationEntry`・`TriggerEntry`・`ObjectKey`・`DatabaseEntry` | スナップショットの1行のうち、検索・一覧・逆引き・関連のたどりに使う項目 |
| | `FunctionTableUsageEntry`・`FunctionTableUsages` | 関数・プロシージャが利用しているテーブルの参考情報1件（cliの`--preview`で出力する。状態・テーブルごとの操作・スキーマが決まらない名前の決まり方と候補・動的SQL・抽出元の定義本体のハッシュ）と、スナップショットの関数（キーと引数）から引く窓口（`SchemaCatalog#functionTableUsages`。定義本体のハッシュが`FunctionEntry`と異なる古い参考情報は返さない） |
| | `ViewpointEntry` | 観点の参考情報1件（識別子・表示名・説明・所属テーブル）。スキーマを持たないため`SchemaObject`は実装しない。テーブルから所属する観点は`ViewpointCatalog#containing`で逆引きする（読み込み済みの観点を引くだけで、索引は持たない） |
| | `FunctionEntry`・`FunctionOverloads`・`SequenceEntry`・`TypeEntry` | 関数・シーケンス・ユーザー定義型の1行。関数は同名のもの（オーバーロード）を`FunctionOverloads`にまとめて名前の解決の単位にする。関数は参考情報と照らすため、定義本体のハッシュ（読み込み時に`SnapshotDirectoryReader`が求める）を持つ |
| | `NameFilter` | 関数・シーケンス・型の一覧を、名前の部分一致で絞り込む条件 |
| | `TableFilter`・`TableType` | テーブルの一覧・検索の絞り込み（DB・スキーマ・区分・観点）を1回組み立てて`matches`で問い合わせる値オブジェクトと、テーブルの区分（table/view/materialized_view）。区分の値はツールの入力スキーマの`enum`にも使う |
| | `SchemaSummary` | スキーマごとのオブジェクトの数（`list_schemas`の元）。集計は`SchemaSummary.summarize`が行う。数え方（オーバーロードはそれぞれ・トリガーはテーブルに属するもの）はcliのREADMEの集計（`DatabaseMetrics`）に揃える |
| `mcp.snapshot` | `SnapshotDirectoryReader` | スナップショットのディレクトリ（`tables.jsonl`・`functions.jsonl`・`sequences.jsonl`・`types.jsonl`）を読み込み`SchemaCatalog`を組み立てる。未知の項目は無視し、無いファイルは0件とする（cliの`target.objects`で外せるため） |
| `mcp.insight` | `InsightsDirectoryReader` | 参考情報のディレクトリ（`{DB名}/viewpoints.json`・`{DB名}/{スキーマ名}/functionTableUsages.json`）を読み込み`ViewpointEntry`・`FunctionTableUsageEntry`のリストを組み立てる。渡されたスナップショットのディレクトリの親の兄弟を自前で求めるため、起動引数は増えない。ディレクトリ・ファイルが無い場合は0件とする |
| `mcp.tool` | `TableDefinitionTools` | MCPサーバーへ登録するツールの一覧。ツールは関心ごとのクラス（`SchemaTools`・`ViewpointTools`・`ClusterTools`・`TableTools`・`RelationTools`・`DiagramTools`・`FunctionTools`・`SequenceTools`・`TypeTools`・`TriggerTools`）に分けて定義する |
| | `ToolSpecifications`・`ToolResults`・`ObjectResolver`・`Page` | ツールの定義の組み立て、結果のJSON化、名前の解決とエラーの文言、一覧の範囲（`offset`・`limit`と、一覧の件数の既定値・上限） |
| | `TableOutputBuilder` | `get_table`の1テーブル分の結果の組み立て（スナップショットの1行に、`sections`・`columns`の絞り込みと、参照しているビュー・所属する観点を反映する） |
| | `MermaidErDiagram` | `get_er_diagram`のMermaid記法の組み立て。表記・エスケープはcliの`MermaidSupport`に揃える（cliのコードには依存できないため、同じ規則をmcp-server側にも持つ。ずれは`SampleErDiagramContractTest`で検知する） |
| | `ViewpointResolver` | 引数`viewpoint`（観点のid）の解決と、見つからない場合のエラーの文言 |
| | `ToolArguments` | ツールの引数の読み取りと、入力スキーマで表せない検証（型・範囲・`enum`・未知の引数はSDKが入力スキーマで検証するため、選択肢は完全一致で照合する）。誤りは`InvalidToolArgumentException`としてツールのエラー（`isError`）で返す |
| | `TableDefinitionResources`・`TableResourceUri` | 利用者が会話に添付するリソース。全テーブル（ビューを含む）を具体的なリソースとして列挙し、読むと`get_table`と同じJSONを返す。URIは`dbxray://{DB名}/{スキーマ名}/{テーブル名}`（各区間はパーセントエンコード）。詳細は[リソース](#リソース) |

## ツール

| ツール | 主な引数 | 返すもの |
|---|---|---|
| `list_schemas` | なし | DB（DBMS種別・メジャーバージョン。古いcliのスナップショットでは省く）ごとのスキーマと、スキーマごとのオブジェクトの数 |
| `list_viewpoints` | `database`（任意） | 観点（業務ドメイン別にテーブルをまとめる切り口。参考情報）の識別子・表示名・説明・所属テーブル数 |
| `list_table_clusters` | `schema`・`database`・`maxClusterSize`（2〜500、既定30）・`excludeViewpointTables`・`limit`・`offset`（任意） | 関連のつながりから推測したテーブルのまとまり（被参照の多い順のテーブル・代表のテーブル・まとまりが参照するハブ・まとまりのテーブルを含む観点）、まとまりを分けるために除いたハブ、関連の無いテーブルの数。推測であり観点ではないため、観点が未宣言の範囲の手がかりや`viewpoints:`の雛形に使う。`excludeViewpointTables`で観点に所属するテーブルを範囲から除ける。行数等の統計は使わない（実データに由来する値を扱わない方針。#78の⑮） |
| `search_tables` | `query`、`schema`・`database`・`limit`・`viewpoint`（任意） | 一致したテーブルの概要（名前・論理名・区分・説明）と、一致した項目（`matchedIn`）。`viewpoint`（観点のid）を指定すると所属テーブルだけに絞り込む |
| `list_tables` | `schema`・`database`・`type`・`includeDescription`・`limit`・`offset`・`viewpoint`・`orderBy`（任意） | テーブルの概要（名前・論理名・区分）と関連の数（`incoming`・`outgoing`・`impact`）の一覧。`viewpoint`で観点の所属テーブルだけに絞り込め、`orderBy`で関連の数の多い順に並べられる。関連の数は読み込み時に`RelationGraph`で1回求める（`impact`は参照元を3段までたどる。段数の上限は`RelationCounts.IMPACT_DEPTH`） |
| `get_table` | `table`（配列も可。最大10件）、`schema`・`database`・`sections`・`columns`（任意） | スナップショットの1行（cliが項目を追加すれば、そのまま返る。ビューの参照先`referencedTables`を含む）に、テーブルを参照しているビュー（`referencedByViews`。ビューの`referencedTables`から逆引きする）と、テーブルが所属する観点（`viewpoints`。`id`・`name`を宣言順）を加えたもの。`sections`・`columns`で項目・カラムを絞れる（`viewpoints`は絞り込みの対象外で、所属する観点があれば常に返す）。`table`に配列を指定すると`{"tables":[...]}`でまとめて返す（`columns`は1件指定時のみ使える） |
| `find_columns` | `column`、`match`・`schema`・`database`・`limit`・`offset`（任意） | 当てはまったカラム（テーブル・型・PK・NOT NULL・デフォルト値・参照先） |
| `get_related_tables` | `table`、`depth`（1〜3）・`direction`（outgoing/incoming/both）等（任意） | 関連（どのカラム同士か・外部キーか論理リレーションか・多重度・段数）と、関連に現れたテーブルの概要 |
| `find_join_path` | `from`・`to`、`maxLength`（1〜6）・`limit`等（任意） | 2つのテーブルをつなぐ最短の経路（たどる順のテーブルと、各段の関連）。同じ長さの経路はすべて（`limit`まで）返す |
| `get_er_diagram` | `table`か`viewpoint`（どちらか一方）、`depth`・`direction`（`table`のときだけ）等（任意） | ER図のMermaid記法（`erDiagram`）と、図に描いたテーブル。`table`は`get_related_tables`と同じ範囲を、`viewpoint`は所属テーブルと所属テーブル同士の関連（`RelationGraph#among`）を描く。描くテーブルは`DiagramTools.MAX_NODES`（cliの`erDiagramMaxNodes`の既定値）まで |
| `list_functions`・`get_function` | `query`等（任意）／`function`、`includeDefinition`（任意） | 関数・プロシージャのシグネチャ（種別・引数・戻り値・言語）。`get_function`はオーバーロードをまとめ、関数を実行するトリガーも返す。`includeDefinition`指定時は定義本体も返す（オーバーロードの本体が同じ場合は1つにまとめ、長い場合は切り詰める）。参考情報がある場合はオーバーロードごとに利用しているテーブル（`tableUsage`）も返す |
| `list_sequences`・`get_sequence` | `query`等（任意）／`sequence` | シーケンスの名前・所有カラム／スナップショットの1行と、採番に使うカラム（`usedByColumns`） |
| `list_types`・`get_type` | `query`・`category`等（任意）／`type` | ユーザー定義型の名前・種別／スナップショットの1行（ENUMの値の一覧等）と、型を使うカラム（`usedByColumns`） |
| `list_triggers` | `schema`・`database`等（任意） | テーブルをまたいだトリガーの一覧（テーブル・タイミング・イベント・実行単位・関数） |

- 結果はJSONの文字列で返す。値が無い項目（空文字・空リスト）は出力しない。
- テーブル名は大文字小文字を区別しない。同名のテーブルが複数ある・見つからない場合は、候補を示すツールのエラーを返し、
  AIが引数を直して呼び直せるようにする。
- 未知の引数・範囲外の値は、既定値へ黙って置き換えずにツールのエラーにする（cliの入力の検証と同じ方針）。
  型・範囲・`enum`・未知の引数は、ハンドラを呼ぶ前にSDKが入力スキーマ（JSON Schema）で検証し、英語の文言でツールのエラー（`isError`）を返す
  （`validateToolInputs`の既定）。これを正とし、`ToolArguments`は入力スキーマで表せない検証（空白の除去後の空・件数の上限・選択肢の照合・
  名前の解決等）だけを行う。数字の文字列を整数として読む等の寛容な読み取りはしない（入力スキーマの型に合わない値はSDKが拒否するため）。
  `get_table`の`columns`にテーブルに無いカラムを指定した場合も、カラムの一覧を示すエラーにする。
- 一覧を返すツールは`limit`・`offset`で範囲を指定し、続きがあれば`nextOffset`を返す。AIのコンテキストを圧迫しないよう、
  件数の上限を設ける。
- 関数・プロシージャの定義本体はAIのコンテキストを圧迫するため、既定では返さない（`get_function`の`includeDefinition`で返す）。
- オブジェクト間の相互参照（関数↔トリガー、シーケンス・型↔カラム）はスナップショットに無いため、
  呼び出しのたびに求める。名前の比較は大文字小文字を区別せず、スキーマ修飾の無い名前は参照元と同じスキーマとみなす。
- ツールの使い分け（どれから呼ぶか）は、初期化時にサーバーからAIへ渡す説明（`instructions`）に書く。
- `get_table`の`table`のように入力スキーマの型を文字列・配列のどちらも許す引数（`stringOrArrayProperty`）は、JSONの配列のほか
  カンマ区切りの文字列も受け付ける（配列を文字列にして渡すMCPクライアントがあるため）。型が配列だけの引数（`sections`・`columns`）に
  文字列を渡すと、SDKが入力スキーマの検証で拒否する。単一の文字列（カンマを含まない）は1件の配列として読む。
- 全ツールに`title`（短い日本語の表示名）を付ける。MCPクライアントのツール一覧・許可の確認画面での表示に使われる。

## リソース

利用者がMCPクライアントの`@`（Claude Codeのターミナル版）や「コンテキストの追加」（VS Code）で、テーブルの定義を会話に添付できるように、
全テーブル（ビューを含む）を具体的なリソース（`resources/list`）として出す（`TableDefinitionResources`）。
ツール（AIが呼ぶ）とは別の経路で、利用者が指名したテーブルの定義を、AIの判断に依らず会話へ載せる。

| 項目 | 内容 |
|---|---|
| URI | `dbxray://{DB名}/{スキーマ名}/{テーブル名}`。各区間はパーセントエンコードする（DB由来の名前は`/`・空白・日本語を含みうるため） |
| `name` | `論理名 (スキーマ名.物理名)`。論理名が無い場合は`スキーマ名.物理名`。論理名・物理名のどちらでも絞り込めるようにする |
| `description` | `name / DB名 / 区分 / テーブルの説明`（120文字で切り詰め）。Claude Codeの候補は、URIが途中で切れ（`dbxray:dbxray://testdb…`）、表示に`name`より`description`を優先するため、先頭に`name`を入れる |
| 中身 | `get_table`を絞り込み無しで呼んだ場合と同じJSON（スナップショットの1行に、参照しているビュー・所属する観点を加えたもの）。読まれたときに組み立てる |

* `name`・`description`は、DB由来の信頼できない文字列を含むため、制御文字・改行・書字方向を変える制御文字を空白に置き換えて1行にする（MCPクライアントの一覧の表示を乱さないため）。
* `title`も出すが、MCPクライアントの絞り込みの対象ではない（Claude Codeは`name`・`description`・URI、VS Codeは`name`・URIを対象にする）。
* 起動時に返す一覧は、テーブル数に比例して大きくなる（3,000テーブルで約0.7MB。1ページで返し、応答は約60ミリ秒）。
  数万テーブルのDBは確かめていない。課題になった場合は、件数の上限の設定を検討する。

### MCPクライアントでの確認結果

3,000テーブルのスナップショットを使い、実機で確かめた。

| クライアント | 結果 |
|---|---|
| Claude Code（ターミナル。2.1.x） | `@`の候補に出る。論理名・物理名で絞り込める。選んで送信すると、AIがツールを呼ばずに定義が自動で添付される。AIがリソースの一覧取得のツールを呼んでも、大きな出力は全文をファイルへ保存し、先頭だけが文脈に入る（AIの文脈はあふれない） |
| VS Code（標準チャット） | 「コンテキストの追加…」→「MCP リソース」に出る。論理名・物理名で絞り込め、3,000件でも速い。選ぶと添付される |
| Claude Code（VS Code拡張） | `@`の候補にMCPのリソースが出ない。URIを手で打って送っても自動添付はされず、AIが`ReadMcpResourceTool`で読むため、この使い方では利点がない |

Claude Codeの`@`は、`@サーバー名:`を付けずに`@受注明細`のように打つのが確実（サーバー名の部分が全件に一致し、論理名の部分が効かなくなるため）。

### URIテンプレートと補完を出さない理由

URIテンプレート（`resources/templates/list`）と、変数の補完（`completion/complete`）も試作して確かめたが、採用しなかった。

* Claude Codeは、テンプレートを`@`の候補に出し、変数も補完するが、補完して作ったURIは自動では添付しない（添付できるのは`resources/list`にあるURIだけ）。
  AIがツールで読むことになり、`get_table`で名前を指定するのと実質同じで、利用者の指名で確実に載るというテンプレートの利点が無い。
* 具体的なリソースの一覧は、3,000テーブルでも取得・絞り込みが速く、列挙で足りる。
* 補完の候補は文字列しか返せず、論理名を並べられない（論理名で入力しても、物理名の候補が並ぶ）。

## stdioでの動作

- 標準出力はMCPのプロトコル専用。利用者向けの表示（読み込んだテーブル数・起動時の誤り）は標準エラーへ出す。
  SDKのログ（SLF4J）も`slf4j-simple`で標準エラーへ出す（WARN以上）。
- 標準エラーはロケールに関わらずUTF-8で出す（MCPクライアントがログとして取り込むため）。
- スナップショットは起動時に全件読み込む。更新した場合はサーバーを再起動する（MCPクライアントから再接続する）。
- 起動時の誤り（`--snapshot`の誤り、ディレクトリ・`database.json`が無い、対応していない`formatVersion`、
  JSONとして読めない行）は、何を直せばよいか（ファイル名・行番号を含む）を標準エラーへ出し、終了コード2で終了する。
- ツールの呼び出し・リソースの読み出しは、標準入力を読むスレッドで1つずつ直列に実行する（`McpServer.sync(...)`の`immediateExecution(true)`）。
  MCP Java SDK（`mcp-core:2.0.1`）の既定（ツール・リソースの読み出しをboundedElasticの複数スレッドに並行実行）は、応答の書き込み先への同時書き込みで
  応答が止まる不具合があり（[java-sdk#686](https://github.com/modelcontextprotocol/java-sdk/issues/686)。修正はSDKのmainに入ったが
  未リリース）、MCPクライアントは独立したツールを並行に呼ぶため日常的に起こる。ツールはメモリ上の検索だけで速いため、直列実行による
  実用上の影響は小さい。SDKの修正入りの版がリリースされたら、`immediateExecution`を残すかを判断する。

## 配布

配布用zip（`.github/workflows/release.yml`）の`mcp/dbxray-mcp.jar`として、cliと同じzipに同梱する。

- Java実行環境（`runtime`）はcliと共有する。jlinkに含めるモジュールは、cliとMCPサーバーの両方のjarを`jdeps`に渡して合算する。
- jarはzipの直下ではなく`mcp/`に置き、同梱した依存ライブラリのライセンスの一覧もcliと分ける。
  jarのパスはMCPクライアントの設定に書かれるため、互換性を約束する範囲に含める（CONTRIBUTING.mdの「互換性を約束する範囲」）。
- MCPクライアントからは同梱のjavaを直接起動してもらう（起動スクリプトは`pause`や画面表示をするためMCPには使えない）。
  利用手順は[docs/usage/mcp-server.md](../usage/mcp-server.md)に書く。

## スナップショット形式との互換

mcp-serverはcliのスナップショットのrecordを共有せず、読み込み用の型を自前で持つ（cliのrecordがエンティティに依存しているため）。
形式のずれは次の仕組みで検知する。

- `SampleSnapshotContractTest`が、cliのベースライン（`docs/sample/postgres/output/snapshot`）を読み込んで検索・関連のたどりを確かめる。
  cliの出力形式を変えるとベースラインを出力し直すため、読み込み側の追従漏れがこのテストで分かる。
- 項目の追加は、未知の項目を無視するため互換。互換性の無い変更をした場合はcliの`DatabaseSnapshot.FORMAT_VERSION`を上げ、
  mcp-serverの`SnapshotDirectoryReader.SUPPORTED_FORMAT_VERSION`を追従させる。

## 参考情報（insights）の読み込み

参考情報（観点等。cliの[参考情報（insights）](./overview.md#参考情報insights)を参照）はスナップショットとは別の
ディレクトリに置かれるが、起動引数は`--snapshot`のみで増やさない。

- `InsightsDirectoryReader`が、渡された`--snapshot`のディレクトリの**親の兄弟**（`{親}/insights/`）を自前で求めて読む。
  cliの出力先（`output.path`）配下で`snapshot/`と`insights/`が常に兄弟になる配置規則に依存する
- `McpServerMain`が`SnapshotDirectoryReader`で組み立てた`SchemaCatalog`に、`withViewpoints`で観点を、
  `withFunctionTableUsages`で関数・プロシージャが利用しているテーブル（cliの`--preview`で出力するスキーマごとのファイル）を合成する。
  後者はスナップショットの関数とキー（DB名・スキーマ名・名前）と引数で対応付ける。スキーマ名はディレクトリ名（パスに使えない文字を置き換えたもの）ではなくファイルの中の値を使う。
  参考情報はcliで`--rm-dist`を付けずに出力し直すと前回のものが残るため、参考情報の`definitionHash`（抽出元の定義本体のSHA-256）が
  スナップショットの定義本体のハッシュと異なるものは対応付けない（`definitionHash`を持たない参考情報は照らさずに対応付ける）
- ディレクトリ・DBごとのファイルが無い場合は0件とする（観点を1つも宣言していない場合、cliの版が古く参考情報を
  まだ出力しない場合等）。起動時の誤りにはしない（スナップショットが1つも無い場合とは扱いが異なる）
- 互換性はスナップショットと同じ仕組み（`SampleInsightsContractTest`によるベースラインの確認、
  cliの`ViewpointsInsight.FORMAT_VERSION`・`FunctionTableUsagesInsight.FORMAT_VERSION`とmcp-serverの`InsightsDirectoryReader.SUPPORTED_FORMAT_VERSION`の追従）に乗せる。
  関数・プロシージャが利用しているテーブルはプレビューの機能のため、既定のベースラインではなく`--preview`で出力した差分のベースライン
  （`docs/sample/postgres/output-preview`）で確かめる

## テスト

| テスト | 対象 |
|---|---|
| `catalog`配下 | 検索の順位・AND・正規化、一覧、カラムの逆引き、名前の解決、相互参照、関連のたどり（向き・段数・自己参照・スナップショットに無い参照先）、JOIN経路の探索、関連のまとまり（ハブの除去・ハブとの間にしか関連の無いテーブルの寄せ方・範囲外との関連の扱い）、観点の一覧・解決・テーブルの絞り込み・テーブルからの逆引き |
| `SnapshotDirectoryReaderTest` | 読み込みと、起動時の誤り（ファイル名・行番号を含むメッセージ） |
| `SampleSnapshotContractTest` | ベースラインとの契約（上記） |
| `SampleErDiagramContractTest` | 観点のER図が、cliの観点ページ（ベースライン）のER図と同じ記述になること（表記・エスケープのずれの検知） |
| `InsightsDirectoryReaderTest` | 読み込み（兄弟ディレクトリの解決を含む）と、起動時の誤り |
| `SampleInsightsContractTest` | ベースラインとの契約（観点の所属テーブルを含む。スナップショットのテーブルから所属する観点を逆引きできること＝両者のキーが一致すること） |
| `tool`配下 | ツールの結果のJSON・エラー・引数の検証、リソースの一覧（名前・説明・URI・表示用文字列の制御文字の除去）と読み出し |
| `ArchitectureTest` | パッケージの依存の向き（[構成と依存の向き](#構成と依存の向き)）、`catalog`がJDK以外に依存しないこと、標準出力（`System.out`・`FileDescriptor.out`・`System.setOut`）へ書き込まないこと、cliのコードに依存しないこと |
| `McpServerProcessTest` | 配布するjar（shadowJar）を子プロセスで起動し、MCPクライアントからstdioで呼び出すE2E。マニフェスト・依存の同梱（ServiceLoaderの登録を含む）・標準出力の汚れを確かめる。応答を待たずに続けてツールを呼び出しても止まらないこと（`immediateExecution`の回帰）、型・範囲・未知の引数がSDKの入力スキーマの検証で拒否されること（ツールのテストはハンドラを直接呼ぶため通らない）、リソースの一覧・読み出し・応答を待たずに続けて読んでも止まらないことも確かめる |

`catalog`には単体テストのカバレッジの最低基準（line 95%・branch 85%）を設け、`./gradlew build`で検査する。
検索・関連のたどりのルールを持つ中心のロジックのため（cliのドメイン層と同じ扱い）。E2Eテストは子プロセスで動くため計測の対象外。
