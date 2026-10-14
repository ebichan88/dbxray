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

この内容は、zipの`docs/usage/mcp.json.example`にも入っています。アプリのリポジトリのルートに`.mcp.json`としてコピーして使えます。

他のMCPクライアントでも、`command`に同梱のjava、`args`に上記の引数を指定すれば利用できます。

## クラウドのClaude Codeで使う

Claude Code on the web（claude.ai/code・Claudeアプリ・デスクトップアプリのクラウドセッション）でも、標準入出力のまま使えます。
クラウドのセッションは、リポジトリをcloneしたVMの中でClaude Codeが動くため、MCPサーバーもそのVMの中で子プロセスとして起動されます。
VMはUbuntu 24.04のx86_64のため、使うのはLinux版のzip（`dbxray-linux.zip`）です。

用意するのは次の3つです。

1. **スナップショットを、`.mcp.json`を置くリポジトリに含める。**
   リポジトリを1つだけ指定したセッションでは、そのリポジトリの`.mcp.json`が読み込まれます。複数のリポジトリを加えたセッションでは読み込まれません。
   そのため、テーブル定義書を別のリポジトリで管理している場合は、アプリのリポジトリにも`snapshot/`を置いてください
   （観点や`--preview`の出力を使う場合は、`snapshot/`と同じ階層の`insights/`も置きます）。
2. **クラウド環境（セッションの実行環境の設定）に、セットアップスクリプトと環境変数を設定する。**
3. **アプリのリポジトリに`.mcp.json`をコミットする。** 上の`.mcp.json`の例（`docs/usage/mcp.json.example`）のまま使えます。

### セットアップスクリプト

クラウド環境の設定ダイアログの「Setup script」に、zipを取得して展開するスクリプトを書きます。セッションの開始前に、rootで実行されます。

```bash
#!/bin/bash
base=https://github.com/ebichan88/dbxray/releases/download/v0.4.2
tmp=$(mktemp -d) &&
curl -fsSL -o "$tmp/dbxray-linux.zip" "$base/dbxray-linux.zip" &&
curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" &&
(cd "$tmp" && sha256sum -c --ignore-missing SHA256SUMS) &&
rm -rf /opt/dbxray && mkdir -p /opt/dbxray &&
unzip -q "$tmp/dbxray-linux.zip" -d /opt/dbxray &&
chmod +x /opt/dbxray/runtime/bin/java ||
echo "dbxrayのMCPサーバーの用意に失敗しました" >&2
```

* バージョン（上の例は`v0.4.2`）は、スナップショットを出力した版に合わせて固定してください。`latest`にすると、環境のキャッシュが作り直されたときに
  版が変わり、スナップショットの形式と合わなくなることがあります。
* スクリプトが失敗（終了コードが0以外）するとセッションが開始できなくなるため、上の例は取得に失敗してもメッセージを出して続行します。
  このときはMCPサーバーが起動できないので、クラウド環境の設定とネットワークの許可を確認してください。
* 実行結果はクラウド環境にキャッシュされ、スクリプトを変えたとき・許可するホストを変えたとき・約7日ごとに作り直されます。
* ネットワークの許可は、既定の「Trusted」で足ります（`github.com`・`objects.githubusercontent.com`・`release-assets.githubusercontent.com`を使います）。
  「None」や、既定の一覧を含めないCustomでは取得できません。

### 環境変数

クラウド環境の設定ダイアログの環境変数に、`.env`形式で次を設定します。`.mcp.json`の`${DBXRAY_HOME}`・`${SCHEMA_SNAPSHOT_DIR}`に使われます。

```
DBXRAY_HOME=/opt/dbxray
SCHEMA_SNAPSHOT_DIR=/home/user/<リポジトリ名>/<snapshotディレクトリへのパス>
```

* リポジトリのcloneの場所は、確認した範囲では`/home/user/<リポジトリ名>`です。
* 環境変数はその環境を使う全員が読めますが、ここに置くのはパスだけで、秘密情報ではありません。

### 動作の確認

* クラウドのセッションでは、リポジトリの`.mcp.json`のサーバーは、確認の画面なしで読み込まれます。
* 新しいセッションを開き、AIに`list_schemas`を呼ばせて、DBとスキーマが返ることを確かめてください。
* スナップショットは、セッションの開始時にcloneした内容が読み込まれます。更新したら、新しいセッションを開いてください。
* Ubuntu 24.04のx86_64のVMで、上のスクリプトでzipを取得して展開し、`runtime/bin/java -jar mcp/dbxray-mcp.jar`が標準入出力で応答することを確認しています。
  クラウドのClaude Codeの`.mcp.json`の扱いは、Claude Codeの[MCPの文書](https://code.claude.com/docs/en/mcp)・
  [クラウド環境の文書](https://code.claude.com/docs/en/cloud-environments)に従っています。
* 組織のセルフホストの環境（自前のrunner）は対象外です。

## ツール

AIは、全体像をつかむ → 探す → 定義を見る の順に呼び出します（使い分けは、サーバーが初期化時にAIへ伝えます）。
ここでは一覧だけを示します。引数・結果・注意点の詳細は、仕様ファイル[mcp-server-spec.yaml](./mcp-server-spec.yaml)にあります。
ブラウザで読みやすい表示（ツールの絞り込み付き）は、[GitHub Pages](https://ebichan88.github.io/dbxray/mcp-server-spec.html)で読めます（最新の正式版の時点のもの）。

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
