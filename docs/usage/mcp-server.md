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

AIは、全体像をつかむ → 探す → 定義を見る の順に呼び出します（使い分けは、サーバーが初期化時にAIへ伝えます）。
ここでは一覧だけを示します。引数・結果・注意点の詳細は、仕様ファイル[mcp-server-spec.yaml](./mcp-server-spec.yaml)にあります。

| 目的 | ツール | 内容 | 主な引数 |
|---|---|---|---|
| 全体像をつかむ | `list_schemas` | DB・スキーマと、種類ごとのオブジェクトの数 | なし |
| | `list_viewpoints` | 観点（業務ドメイン別にテーブルをまとめる切り口）の一覧 | |
| | `list_table_clusters` | 関連のつながりから推測した、テーブルのまとまり | `maxClusterSize` |
| 探す | `search_tables` | キーワードでテーブルを検索 | `query` |
| | `list_tables` | テーブルの一覧（関連の数付き。絞り込み・並べ替え可） | `type`・`viewpoint`・`orderBy` |
| | `find_columns` | カラム名から、そのカラムを持つテーブルを逆引き | `column` |
| | `find_similar_tables` | カラム名の構造が似たテーブルを探す（重複した設計・履歴テーブルの発見） | `table`か`columns` |
| 定義を見る | `get_table` | テーブル（ビューを含む）の定義 | `table`・`sections`・`columns` |
| 関連をたどる | `get_related_tables` | 外部キー・論理リレーションでつながるテーブル | `table`・`depth`・`direction` |
| | `find_join_path` | 2つのテーブルをつなぐ最短のJOIN経路 | `from`・`to` |
| | `get_er_diagram` | ER図（Mermaid記法） | `table`か`viewpoint` |
| 影響を調べる | `get_impact` | テーブルを変更・削除したときの影響範囲 | `table` |
| 関数・シーケンス・型・トリガー | `list_functions`・`get_function` | 関数・プロシージャの一覧／シグネチャ・定義本体・利用しているテーブル | `query`／`function` |
| | `list_sequences`・`get_sequence` | シーケンスの一覧／定義・採番に使うカラム | `query`／`sequence` |
| | `list_types`・`get_type` | ユーザー定義型の一覧／定義・型を使うカラム | `query`・`category`／`type` |
| | `list_triggers` | テーブルをまたいだトリガーの一覧 | |

* ほとんどのツールに、DBが複数ある場合の`database`と、スキーマで絞り込む`schema`があります。一覧を返すツールは、`limit`・`offset`で範囲を指定し、続きがあれば`nextOffset`を返します。
* テーブル名は大文字小文字を区別しません。同名のテーブルが複数のスキーマにある場合・見つからない場合は、候補を示すエラーを返します（AIが引数を直して呼び直します）。
* 仕様ファイルの引数の名前・型・範囲は、MCPサーバーがクライアントへ返すツール定義とテストで照合しているため、実際の動作と食い違いません。

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
