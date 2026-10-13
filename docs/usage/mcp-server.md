# AIからテーブル定義を調べる（MCPサーバー）

出力した[スキーマのスナップショット](./cli.md#スキーマのスナップショットjson-lines)と[参考情報](./cli.md#参考情報insights)を、AIからMCP
（Model Context Protocol）のツールで検索できるようにするサーバーです。テーブル定義書のリポジトリとは別のリポジトリでコードを書くとき、
AIが正しいテーブル名・カラム・JOINの条件（外部キーと、サイドカーYAMLで宣言した論理リレーション）を調べられるようになります。

* DBには接続しません。スナップショット・参考情報のファイルを読むだけなので、DBの接続情報をAIに渡す必要はありません。
* AIが調べるだけでなく、利用者が`@`等でテーブルの定義を会話に添付することもできます（[リソース](#リソース会話にテーブルの定義を添付する)）。
* 配布用zipの`mcp/dbxray-mcp.jar`がサーバー本体です。同梱のJava実行環境（`runtime`）で動くため、Javaのインストールは不要です。

## MCPサーバー動作イメージ

<img width="1348" height="772" alt="MCPサーバー動作イメージ" src="https://github.com/user-attachments/assets/4cb1a424-ec1b-4a2f-9965-1863b39e0a21" />

## 準備

1. このツールでテーブル定義書を出力し、`output.path`配下の`snapshot/`（観点を宣言している場合や`--preview`を付けた場合は`insights/`も）をGit等で共有する（テーブル定義書と一緒にコミットしておく等）
2. AIを使う人が、そのリポジトリを手元にcloneし、zipを展開しておく

## 起動方法

MCPクライアント（Claude Code・Claude Desktop等）が、標準入出力で通信するサーバーとして起動します。自分で起動しておく必要はありません。

```
<展開先>/runtime/bin/java -jar <展開先>/mcp/dbxray-mcp.jar --snapshot=<output.path配下のsnapshotディレクトリ>
```

Windowsでは`<展開先>\runtime\bin\java.exe`を指定します。

| 引数 | 必須 | 内容 |
|---|---|---|
| `--snapshot=<ディレクトリ>` | ○ | このツールの出力先（`output.path`）配下の`snapshot`ディレクトリ。複数のDBのスナップショットを含んでいてもよい |

* 上記以外の引数・`--snapshot`の重複は誤りとして扱います。
* 引数・スナップショットに誤りがある場合（ディレクトリが無い、`output.path`そのものを指定した、新しい形式のスナップショット、
  マージの衝突等でJSONとして読めない行がある等）は、何を直せばよいか（ファイル名・行番号を含む）を標準エラーに出し、終了コード`2`で終了します。
  MCPクライアントでサーバーの起動に失敗した場合は、クライアントのログで確認してください。
* スナップショットは起動時に読み込みます。スナップショットを更新（`git pull`等）した場合は、MCPクライアントからサーバーに再接続してください。

## MCPクライアントの設定例

Claude Codeの場合、次のコマンドで登録できます（Linux／macOSの例）。

```
claude mcp add dbxray -- /opt/dbxray-linux/runtime/bin/java -jar /opt/dbxray-linux/mcp/dbxray-mcp.jar --snapshot=/work/schema-docs/output/snapshot
```

チームで共有する場合は、アプリのリポジトリに`.mcp.json`をコミットし、各自の環境で異なるパスを環境変数で与えます
（`DBXRAY_HOME`はzipの展開先、`SCHEMA_SNAPSHOT_DIR`はcloneしたテーブル定義書のリポジトリの`snapshot`ディレクトリ）。

```json
{
  "mcpServers": {
    "dbxray": {
      "command": "${DBXRAY_HOME}/runtime/bin/java",
      "args": ["-jar", "${DBXRAY_HOME}/mcp/dbxray-mcp.jar", "--snapshot=${SCHEMA_SNAPSHOT_DIR}"]
    }
  }
}
```

他のMCPクライアントでも、`command`に同梱のjava、`args`に上記の引数を指定すれば利用できます。

## ツール

| ツール | 主な引数 | 内容 |
|---|---|---|
| `list_schemas` | なし | スナップショットに含まれるDB（DBMS種別・メジャーバージョン）・スキーマと、スキーマごとのテーブル・ビュー・マテリアライズドビュー・関数（プロシージャを含む）とそのうちのプロシージャ・シーケンス・ユーザー定義型・トリガーの数を返す。数え方は定義書のREADMEの集計と同じ |
| `list_viewpoints` | `database` | 観点（業務ドメイン別にテーブルをまとめる切り口。[参考情報](./cli.md#参考情報insights)）の識別子・表示名・説明・所属テーブル数を返す |
| `list_table_clusters` | `schema`・`database`・`maxClusterSize`（2〜500、既定30）・`excludeViewpointTables`（既定false）・`limit`（既定20、最大100）・`offset` | 外部キー・論理リレーションでつながるテーブルのまとまりを推測して返す。まとまりが`maxClusterSize`を超える間は、被参照の最も多いテーブル（3テーブル以上から参照される共通のマスタ）をハブとして除いて分け直し、ハブとの間にしか関連の無いテーブルは被参照の最も多いハブごとにまとめる。各まとまりは、被参照の多い順のテーブル（先頭が代表のテーブル。1つのまとまりにつき100件まで）・まとまりが参照するハブ・まとまりのテーブルを含む観点を返す。どのテーブルとも関連を持たないテーブルは数（`unrelatedTables`）だけを返す。関連のつながりだけから求めた推測で、人が宣言した観点ではない。観点が未宣言の範囲の手がかりや、`viewpoints:`を宣言する雛形に使う（`excludeViewpointTables`を指定すると、観点に所属するテーブルを除いて求める）。関連が少ないDB（FK制約が無く、論理リレーションも未宣言）では、ほとんどのテーブルが関連の無いテーブルになる |
| `search_tables` | `query`（必須）、`schema`・`database`・`limit`・`viewpoint`（観点のid）・`erRole`（`junction`） | テーブル名・論理名・説明・カラム名・カラムの論理名を部分一致で検索し、一致の強い順に概要を返す。空白区切りの複数語はすべてを含むものだけを返す。`viewpoint`を指定すると、その観点の所属テーブルだけに絞り込む。多対多をつなぐ中間テーブルには`erRole`（`junction`）が付き、`erRole`に`junction`を指定すると中間テーブルだけに絞り込む |
| `list_tables` | `schema`・`database`・`type`（`table`／`view`／`materialized_view`）・`includeDescription`・`limit`（既定100、最大500）・`offset`・`viewpoint`（観点のid）・`orderBy`（`name`／`incoming`／`outgoing`／`impact`、既定`name`）・`erRole`（`junction`） | テーブル（ビューを含む）の名前・論理名・区分と関連の数を返す。関連の数は、外部キー・論理リレーションで自テーブルを参照しているテーブルの数（`incoming`）、自テーブルが参照しているテーブルの数（`outgoing`）、参照元を3段までたどって届くテーブルの数（`impact`。変更の影響範囲）で、自己参照とスナップショットに含まれないテーブルは数えない。既定は名前の順で、`orderBy`に`incoming`・`outgoing`・`impact`を指定するとその数の多い順に並べる（関連の無いテーブルも0として含む）。`viewpoint`を指定すると、その観点の所属テーブルだけに絞り込む。多対多をつなぐ中間テーブルの行には`erRole`（`junction`）が付き、`erRole`に`junction`を指定すると中間テーブルだけに絞り込む（中間テーブルの判定は`get_table`の説明を参照） |
| `get_table` | `table`（必須。配列で複数指定できる。最大10件）、`schema`・`database`・`sections`・`columns` | テーブル（ビューを含む）の定義を返す（スナップショットの1行）。`sections`（`columns`・`indexes`・`constraints`・`foreignKeys`・`logicalRelations`・`triggers`・`definition`・`referencedTables`・`referencedByViews`）で返す項目を、`columns`で返すカラムを絞り込める。`table`に複数指定した場合は`{"tables":[...]}`でまとめて返す（`columns`は1件指定時のみ使える）。トリガー（`triggers`）は、Oracleの場合は本体のPL/SQL（`body`）も含む。ビューの場合は参照するテーブル（`referencedTables`）を、テーブルを参照しているビューがある場合はそのビュー（`referencedByViews`）を返す。テーブルが観点に所属する場合は、所属する観点（`viewpoints`。`id`・`name`を宣言順）も返す。多対多をつなぐ中間テーブルの場合は`erRole`（`junction`）とつなぐテーブル（`junctionOf`。スナップショットに含まれないテーブルも含む）を、中間テーブルを介して多対多の関係にあるテーブルには、相手のテーブルと中間テーブル（`manyToMany`。`table`・`via`。スナップショットにあるテーブルだけ）を返す。`manyToMany`は20件までで、超える場合は全体の件数を`manyToManyTotal`で示す。中間テーブルは、区分が`table`で、主キーの列を過不足なく覆う2本以上の外部キー（論理リレーションを含む）の組み合わせがあり、そのどの外部キーも外すと覆えなくなるテーブル（主キー以外の列の有無・他のテーブルからの被参照は問わない。テナントIDのように複数の外部キーが列を共有していてもよい）で、スナップショットの主キー・外部キーの構成から機械的に求めた推測。参照先1行に対して参照元が高々1行の外部キー（1対1）は、構成する外部キーに数えない。主キーとは別に一意索引で組を守る形（`id`＋一意索引）は判定しない。JOINの条件は`find_join_path`で調べる |
| `find_columns` | `column`（必須）、`match`（`exact`／`partial`、既定`exact`）・`schema`・`database`・`limit`（既定50、最大500）・`offset` | カラムの物理名・論理名から、そのカラムを持つテーブルを逆引きする。型・PK・NOT NULL・デフォルト値と、外部キー・論理リレーションの参照先も返す |
| `find_similar_tables` | `table`か`columns`（設計中のカラム名の配列。最大100件）のどちらか一方（必須）、`schema`・`database`・`viewpoint`（観点のid）・`minShared`（共通のカラム数の下限。1〜20、既定3）・`minSimilarity`（類似度の下限（%）。10〜100、既定50）・`limit`（既定10、最大50） | 構造（カラム名）が似ているテーブルを探す。新しいテーブルを作る前に同じ目的の既存テーブルが無いか確かめるときや、履歴・バックアップ・複製のテーブルを見つけるときに使う。`table`には既存のテーブルを指定し、同じDBから探す（同名のテーブルが複数のスキーマにある場合は`スキーマ名.テーブル名`の形で指定する）。`schema`・`viewpoint`を指定すると、探す範囲をそのスキーマ・観点の所属テーブルに絞り込む（`table`を指定した場合も同じで、基準のテーブルの特定には使わない）。類似度（`similarity`。%。100はカラムが同じ場合だけ）、種別（`kind`。`identical`: カラムが同じ／`superset`: 相手が基準の全カラムを持ち追加がある／`subset`: 相手は基準の一部だけ／`overlap`: それ以外）、共通のカラム数（`sharedColumns`）、基準にだけある・相手にだけあるカラム（`onlyInBase`・`onlyInOther`。20件まで。省いた数は`onlyInBaseOmitted`・`onlyInOtherOmitted`）を返す。`table`を指定した場合は、同名で型の異なるカラム（`typeMismatches`。`difference`は、型が異なる場合は`type`、長さ・精度等だけが異なる場合は`size`）と、基準と外部キー・論理リレーションで直接つながるテーブルの印（`relatedToBase`）も返す。条件を満たすテーブルが無い場合は、`minShared`・`minSimilarity`を小さくする方法を`message`で示す |
| `get_related_tables` | `table`（必須）、`schema`・`database`・`depth`（1〜3、既定1）・`direction`（`outgoing`／`incoming`／`both`、既定`both`） | 外部キーと論理リレーションをたどり、つながるテーブルと、どのカラム同士でつながるか・多重度を返す。参照される側（被参照）からもたどれる |
| `find_join_path` | `from`・`to`（必須。`スキーマ名.テーブル名`も可）、`database`・`maxLength`（1〜6、既定4）・`limit`（1〜20、既定5） | 2つのテーブルをつなぐ最短のJOIN経路を、外部キーと論理リレーションを向きを問わずたどって返す。同じ長さの経路が複数ある場合はすべて（`limit`まで）返す |
| `get_impact` | `table`（必須。ビュー・マテリアライズドビューも指定できる）、`schema`・`database`・`section`（続きを取りたいリスト。概要の同じ名前のリスト`direct`／`indirect`／`views`／`triggers`／`usages`／`unverified`）・`offset`・`limit`（既定100、最大500。`offset`・`limit`は`section`を指定した場合だけ使える） | テーブルを変更・削除したときの影響範囲を1回で返す。影響を受けるもの（`affected`）は、`referencedBy`（外部キー・論理リレーションで参照しているテーブル。`direct`は段1の関連で、自己参照を含み、カラム・種別・多重度を付ける。`indirect`は3段までの間接参照で、段数と経由したテーブルを付ける。`indirectTotal`は間接参照の総数）、`views`（参照しているビュー・マテリアライズドビュー。ビューを参照するビューも含み、`via`で経由したビューを示す）、`triggers`（自テーブルのトリガー）、`functions`（自テーブル、またはそのビューを使う関数・プロシージャ。`usages`は関数と経路（自テーブルか、使っているビューか）ごとの行で、操作C・R・U・Dと、関数を実行するトリガー`calledByTriggers`を付ける。`coverage`は調べがついた範囲の件数、`unverified`は自テーブルを使うかどうか定義本体を読む必要がある関数）。自テーブルが依存するもの（`dependsOn`）は、カラムのデフォルト値の`nextval`が使うシーケンスと、カラムの型に使われているユーザー定義型。`section`を指定しない場合は、各リストを50件までを決まった順で返し、超えたリストは総数と続きの取り方を`more`（`section`・`total`・`nextOffset`）で示す（間接参照の総数`indirectTotal`だけは、超えなくても常に返す）。`section`を指定すると、そのリストだけを`offset`・`limit`で読み進める（結果は`section`・`total`・`nextOffset`・`items`で、`items`の形は概要の各リストと同じ）。影響範囲を調べるときは最初に`section`なしで呼び、詳細は`get_related_tables`・`get_function`・`get_table`で調べる |
| `get_er_diagram` | `table`か`viewpoint`（観点のid）のどちらか一方（必須）、`database`。`table`を指定した場合は`schema`・`depth`（1〜3、既定1）・`direction`（`outgoing`／`incoming`／`both`、既定`both`）も使える | ER図をMermaid記法（`erDiagram`）で返す（`mermaid`）。`table`を指定すると、`get_related_tables`と同じ範囲（たどった関連と、関連に現れたテーブル）を描く。`viewpoint`を指定すると、観点の所属テーブルと、所属テーブル同士の関連を描く（観点の外のテーブルとの関連は描かない。関連の無い所属テーブルも箱だけ描く）。表記は定義書のER図と同じで、箱は「テーブル名（論理テーブル名）」と関連をつなぐカラム、外部キーは実線・論理リレーションは破線。図に描くテーブルが80件を超える場合はエラーを返す。Mermaidを描画できるMCPクライアント（チャットの画面等）では、AIがコードブロックに入れて示すと図として表示される |
| `list_functions` | `query`・`schema`・`database`・`limit`（既定100、最大500）・`offset` | 関数・プロシージャの名前・種別・引数・戻り値・言語を返す（定義本体は返さない）。`query`は名前の部分一致 |
| `get_function` | `function`（必須）、`schema`・`database`・`includeDefinition` | 関数・プロシージャのシグネチャ（種別・引数・戻り値・言語）を、同名のもの（オーバーロード）をまとめて返す。関数を実行するトリガー（`calledByTriggers`）も返す。`includeDefinition`を指定すると定義本体も返す（既定false。オーバーロードの本体が同じ場合は1つにまとめ、長い場合は切り詰める）。cliを`--preview`付きで実行した参考情報がある場合は、オーバーロードごとに利用しているテーブル（`tableUsage`。後述）も返す |
| `list_sequences` | `query`・`schema`・`database`・`limit`（既定100、最大500）・`offset` | シーケンスの名前と所有カラムを返す |
| `get_sequence` | `sequence`（必須）、`schema`・`database` | シーケンスの定義（増分・最小値・最大値・キャッシュ・開始値・循環の有無・所有カラム）と、デフォルト値（`nextval`）で採番に使うカラム（`usedByColumns`）を返す |
| `list_types` | `query`・`category`（PostgreSQLは`ENUM`／`COMPOSITE`／`DOMAIN`／`RANGE`、Oracleは`OBJECT`／`VARRAY`／`NESTED TABLE`）・`schema`・`database`・`limit`（既定100、最大500）・`offset` | ユーザー定義型の名前と種別を返す |
| `get_type` | `type`（必須）、`schema`・`database` | ユーザー定義型の定義（ENUMの値の一覧・複合型の属性等）と、型を使うカラム（`usedByColumns`）を返す |
| `list_triggers` | `schema`・`database`・`limit`（既定100、最大500）・`offset` | トリガーを、テーブル・タイミング・イベント・実行単位・実行される関数とともに、テーブルをまたいで返す |

* テーブル名は大文字小文字を区別しません。`スキーマ名.テーブル名`の形でも指定できます。
* 同名のテーブルが複数のスキーマにある場合・見つからない場合は、候補を示すエラーを返します（AIが引数を直して呼び直します）。
* `viewpoint`に存在しない観点のidを指定した場合は、宣言されている観点を示すエラーを返します。
* `get_er_diagram`に`table`と`viewpoint`の両方を指定した場合や、`viewpoint`と一緒に`schema`・`depth`・`direction`を指定した場合はエラーを返します。
* 一覧を返すツールは、件数が`limit`を超える場合に続きの`offset`（`nextOffset`）を返します。
* `find_similar_tables`の類似度は、カラム名（大文字小文字・全角半角を区別しない）の集合を比べた構造の類似で、同じ意味のテーブルとは限りません。
  多くのテーブルが持つ`id`・`created_at`のような汎用のカラムは、持つテーブルが多いほど重みを下げて数えます。重みは、`schema`・`viewpoint`で範囲を絞っても、範囲のDBの全テーブルで数えます（DBのテーブルが少ない場合は、汎用のカラムの重みが十分に下がりません）。
  比べる相手は区分が`table`のテーブルです。基準と外部キー・論理リレーションで直接つながるテーブルも含め、`relatedToBase`で区別します（親子は互いのキーを持つため似て見えることがあり、履歴・拡張のテーブルは基準を参照していることが多いため）。
  `table`を指定した場合は、別のDBのテーブルを含めません。
* `get_impact`の`functions`は、cliの[プレビューの機能](./cli.md#関数プロシージャが利用しているテーブルプレビュー)（`--preview`）で出力した参考情報`functionTableUsages.json`から求めた参考値で、**互換性の約束の対象外**です（結果の`functions.preview: true`、`notes`、ツールの説明でも伝えます）。
    * 載っていなくても使われていないとは限りません。動的SQL・呼び出している関数の中・出力対象外のテーブルの参照は含みません。
    * `functions.coverage`は、同じDBの関数・プロシージャ（オーバーロードはそれぞれ）の参考情報の状態を数えます。`noInsight`は参考情報が無い関数（cliを`--preview`付きで実行して出力する）、`stale`は定義が変わった後の古い参考情報の関数（`--preview`付きで出力し直す）、`unsupported`は解析しなかった関数（対象外の言語・難読化等）、`withDynamicSql`・`incomplete`は解析済みでも取りこぼしがある関数です。
    * `functions.unverified`は、参考情報はあるが取りこぼしのある関数のうち、`usages`に載らなかったオーバーロードを1件ずつ挙げます（`function`・`arguments`・`reason`。`reason`は`dynamicSql`・`incomplete`・`unsupported`で、1つのオーバーロードに複数当てはまる場合はこの順で先のものを示します）。自テーブルを使うかどうかを参考情報からは確かめきれない関数なので、`get_function`で定義本体を確かめてください。テーブルとの関係は分からないため、同じDBの該当する関数をすべて挙げます（50件を超えた分は`section: unverified`で読みます）。
    * 参考情報が無い（`noInsight`）・古い（`stale`）関数は、`unverified`に挙げず、`coverage`の件数と`notes`だけで伝えます。関数を1つずつ読むより、cliを`--preview`付きで出力し直すほうが確実なためです（`--preview`を付けずに出力した場合は、全関数が該当します）。
    * `functions.usages`の1行は、関数と経路（自テーブルか、使っているビューか）の組です。自テーブルとビューの両方を使う関数は2行になり、`more`の総数もこの行数です。同名のオーバーロードは1行にまとめ、`overloads`に一致したオーバーロードの引数を示します。
    * スキーマが決まらない名前（`search_path`等）でしか一致しなかった関数は、`match: possible`で示し、確定扱いにしません。確定で一致したオーバーロードと、候補でだけ一致したオーバーロードが混ざる関数は、`possible`にならず、候補のオーバーロードの操作も`operations`に含みます（確定の操作のように見えます）。関数がテーブルを参照するビューを使っている場合も拾い、`via`に使っているビューを示します。名前は大文字小文字を区別して照らします。
    * Oracleのトリガーは関数名を持たず本体を直接持ち、本体の中の参照は参考情報の対象外です。`coverage.triggersWithoutFunction`にその数を示し、トリガーが触るテーブル（ビューのINSTEAD OFトリガーが書き込むテーブル等）は、このツールでは分かりません。
    * ビュー・関数の影響はテーブル単位です（ビューや関数がどのカラムを使うかは、スナップショットに含まれません）。`dependsOn`は影響を受けるものではなく、自テーブルが使うものです。シーケンスは`nextval`のデフォルト値から拾うため、IDENTITY列等で採番するシーケンスは含みません。
* `target.objects`で出力対象から外した種別は0件になります。
* 関数・プロシージャの定義本体は、AIのコンテキストを圧迫するため既定では返しません（`get_function`の`includeDefinition`で返します）。
* `get_function`の`tableUsage`は、cliの[プレビューの機能](./cli.md#関数プロシージャが利用しているテーブルプレビュー)（`--preview`）で出力した参考情報`functionTableUsages.json`から返します。
  参考情報が無い関数と、参考情報の抽出元の定義本体がスナップショットの定義本体と一致しない関数（`--rm-dist`を付けずに対象のスキーマを減らした場合等で、参考情報だけが前回のまま残り、その後に定義が変わった場合）には項目ごと付けません。

    ```json
    "tableUsage": {
      "tables": [
        {"table": "sample.employee", "operations": "U"},
        {"table": "project", "schemaResolution": "search_path", "schemaCandidates": ["sample"], "operations": "R"}
      ],
      "dynamicSql": ["EXECUTE"]
    }
    ```

    * 定義本体から機械的に抽出した参考値で、呼び出している関数の中と動的SQLの中の参照は含みません（ツールの説明でAIにも伝えます）。
    * スキーマが決まらない名前は、`table`にスキーマを付けず、何で決まるか（`schemaResolution`）と候補（`schemaCandidates`）を返します。候補が1つでも、そのスキーマに決めつけないようツールの説明でAIに伝えます。
        * `search_path`: PostgreSQLで、実行時の`search_path`で決まる
        * `current_user`: Oracleの実行者権限（`AUTHID CURRENT_USER`）で、実行するユーザーで決まる
        * `synonym`: Oracleの定義者権限で、所有者のスキーマに無いため、シノニム経由で参照している可能性がある
    * 返すのは出力対象のテーブルと一致したものだけです。利用しているテーブルが見つからなかった場合は`"tables": []`を返しますが、テーブルに触れないことを意味しません（出力対象外のテーブル・名前の違うシノニム・呼び出し先の関数・動的SQLの中の参照は含まないため。ツールの説明でAIにも伝えます）。定義本体を最後まで読めなかった場合は`"incomplete": true`、Oracleで同名のサブプログラムの本体をまとめて抽出した場合は`"overloadsMerged": true`を付けます。
    * 解析しなかった場合は`{"status": "unsupported_language", "language": "plpython3u"}`のように状態だけを返します（`no_definition`・`wrapped`・`subprogram_not_found`もあります）。
* `get_table`の`sections`を指定しても、テーブル名・論理名・区分・説明・備考・所属する観点・ER上の区分（`erRole`・`junctionOf`・`manyToMany`）は常に返します。`columns`を指定した場合は、`sections`に関わらず指定したカラムを返します。

## リソース（会話にテーブルの定義を添付する）

ツールはAIが呼び出して調べますが、利用者が「このテーブル」と指名して、定義を会話に添付することもできます。
スナップショットに含まれる全テーブル（ビューを含む）がMCPのリソースとして並び、論理名・物理名のどちらでも絞り込めます。

* リソースの名前は`論理名 (スキーマ名.物理名)`、URIは`dbxray://{DB名}/{スキーマ名}/{テーブル名}`です。
  添付される内容は、`get_table`で返す定義（JSON）と同じです。
* AIに調べさせると、AIが探す名前を取り違えることがあります。名前が分かっているテーブルは、添付で指名すると確実に定義が載ります。

| MCPクライアント | 操作 |
|---|---|
| Claude Code（ターミナル） | 入力欄に`@`に続けて`受注明細`や`order_det`と打つと候補が出ます。矢印キーで選び、Tabで確定して、質問と一緒に送信します |
| VS Code（標準チャット） | チャットの「コンテキストの追加…」→「MCP リソース」を選び、入力欄で絞り込んで選びます |

* Claude Codeでは、`@受注明細`のようにサーバー名を付けずに打つと、確実に絞り込めます。
* VS Code拡張版のClaude Codeでは、確認した範囲では、`@`の候補にリソースが出ません。このときは、AIに`get_table`で調べさせてください。
* 数千テーブルでも使えます（3,000テーブルで確認）。
