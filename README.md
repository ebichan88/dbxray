# dbxray

[![build](https://github.com/ebichan88/dbxray/actions/workflows/ci.yml/badge.svg)](https://github.com/ebichan88/dbxray/actions/workflows/ci.yml)
![release](https://img.shields.io/github/v/release/ebichan88/dbxray)
![license](https://img.shields.io/github/license/ebichan88/dbxray)
![Java](https://img.shields.io/badge/-Java%2021-b07219.svg?logo=openjdk&logoColor=white&style=flat)
![PostgreSQL](https://img.shields.io/badge/-PostgreSQL-555.svg?logo=postgresql&style=flat)
![Oracle](https://img.shields.io/badge/-Oracle-f80000.svg?logo=oracle&style=flat)

目次:

- [Overview](#overview)
- [Getting Started](#getting-started)
- [やりたいこと別の案内](#やりたいこと別の案内)
- [License](#license)

詳しいドキュメント:

- [CLIリファレンス](./docs/usage/cli.md) … 設定ファイル・サイドカーYAML・CLI引数・`--check`・終了コード・ログ・出力される内容の詳細
- [MCPサーバー](./docs/usage/mcp-server.md) … AIからテーブル定義を調べる
- [CONTRIBUTING.md](./CONTRIBUTING.md) … ビルド・テスト・リリースの手順（開発者向け）

## Overview

dbxray（旧名: exportTableDefinition）は、DBに接続し、テーブル一覧・各テーブルの定義書・ER図などをMarkdown形式で出力するツールです。

### 出力例

出力例は[サンプルのDB](./docs/sample/postgres/ddl.sql)（PostgreSQL）から出力したものの抜粋です。

#### ER図（観点「人事管理」）

外部キーの関係を、GitHubなどでそのまま図として表示できるMermaid記法で出力します
（出典: [観点「人事管理」のページ](./docs/sample/postgres/output/testdb/viewpoint_testdb_personnel.md)）。

```mermaid
erDiagram
    sample_department["department"]
    sample_employee["employee（従業員）"]
    sample_employee_history["employee_history（従業員履歴）"]
    sample_employee_profile["employee_profile"]
    sample_parking_spot["parking_spot"]
    sample_department ||--o{ sample_employee : "employee_department_id_fkey"
    sample_employee |o--o{ sample_employee : "employee_manager_id_fkey"
    sample_parking_spot |o--o| sample_employee : "employee_parking_spot_id_fkey"
    sample_employee ||--o{ sample_employee_history : "employee_history_employee_id_fkey"
    sample_employee ||--o| sample_employee_profile : "employee_profile_employee_id_fkey"
    sample_department {
        integer department_id PK
    }
    sample_employee {
        integer employee_id PK "従業員ID"
        integer department_id FK "所属部署ID"
        integer manager_id FK "上長の従業員ID"
        integer parking_spot_id FK "駐車場ID"
    }
    sample_employee_history {
        integer employee_id FK
    }
    sample_employee_profile {
        integer employee_id PK, FK
    }
    sample_parking_spot {
        integer parking_spot_id PK
    }
```

#### テーブル定義書（employeeテーブル）

カラム・インデックス・制約・外部キー情報などを、テーブルごとのMarkdownに出力します。以下はカラム情報の先頭6行です
（出典: [employeeの定義書](./docs/sample/postgres/output/testdb/sample/table/employee.md)）。

| No. | 論理名 | 物理名 | データ型 | 桁数/精度 | PK | Not Null | デフォルト | 備考 |
|:---|:---|:---|:---|:---|:---|:---|:---|:---|
|1|従業員ID|employee_id|integer||○|○|nextval('sample.employee_employee_id_seq'::regclass)||
|2|従業員コード|employee_code|character varying(10)|10||○|||
|3|従業員名|employee_name|character varying(50)|50||○|||
|4|所属部署ID|department_id|integer|||○|||
|5|上長の従業員ID|manager_id|integer||||||
|6|駐車場ID|parking_spot_id|integer||||||

#### 出力されるものの一覧

出力されるものの全体像は以下のとおりです（各項目の詳細は[出力される内容の詳細](./docs/usage/cli.md#出力される内容の詳細)を参照）。

| 出力物 | 内容 |
|---|---|
| README（`{DB名}/README.md`） | 生成された全ドキュメントへのリンクをまとめた索引と、スキーマごとのオブジェクトの数・論理名の記述状況・関連の状況の集計。データベースごとに1つ生成される |
| テーブル一覧（`tableList_{DB名}.md`） | 対象スキーマ・テーブルの一覧と、各テーブル定義書・関連ドキュメントへのリンク |
| 各テーブル定義書 | カラム・インデックス・制約・外部キー情報・被参照情報など（PostgreSQLの場合はトリガー情報も） |
| ER図 | テーブル間の外部キー関係を表すMermaid記法の図（テーブル単位・スキーマ単位の2種類） |
| 観点ごとのページ・観点一覧 | サイドカーYAMLで宣言した業務ドメイン別の観点（「受注管理」等）ごとのER図と所属テーブル。[詳細](./docs/usage/cli.md#観点viewpoints) |
| 追加オブジェクトの一覧・個別ページ | 関数・プロシージャ、シーケンス、ユーザー定義型（ENUM等） |
| スキーマのスナップショット | 上記と同じ情報を機械可読なJSON Lines形式で構造化したもの。[詳細](./docs/usage/cli.md#スキーマのスナップショットjson-lines) |
| 参考情報 | スキーマの事実ではないが、AIへ渡したい情報（観点等）。[詳細](./docs/usage/cli.md#参考情報insights) |

あわせて、出力したスナップショット・参考情報をAI（Claude Code等）から検索できるようにするMCPサーバーを同梱しています。[詳細](./docs/usage/mcp-server.md)

出力サンプル: [README](./docs/sample/postgres/output/testdb/README.md) / [テーブル一覧](./docs/sample/postgres/output/testdb/tableList_testdb.md)

### 対象DBMS

| DBMS | 推奨バージョン | 備考 |
|---|---|---|
|PostgreSQL|13以上|パーティション表の外部キー・トリガーが親から子へ複製されたものを見分けるため|
|Oracle|12c以上|Oracle固有の扱いは[Oracleの場合](./docs/usage/cli.md#oracleの場合)を参照|

## Getting Started

[Releases](../../releases/latest)から実行可能な形式一式をダウンロードしてすぐに使えます。

Java実行環境（runtimeフォルダ）を同梱しているため、Javaを未インストールの状態で実行できます。

### 入手方法

1. [Releases](../../releases/latest)からOSに合ったzipをダウンロードする（過去の版は[リリースの一覧](../../releases)から入手できます）
    * Windows（x64）: `dbxray-windows.zip`
    * Linux（x64）: `dbxray-linux.zip`
    * macOS（Apple Silicon）: `dbxray-macos.zip`
2. （任意）同じリリースの`SHA256SUMS`で、ダウンロードしたzipが壊れていない・改ざんされていないことを確かめる
    * Linux／macOS: `sha256sum -c SHA256SUMS --ignore-missing`（macOSで`sha256sum`が無い場合は`shasum -a 256 -c SHA256SUMS --ignore-missing`）
    * Windows（PowerShell）: `Get-FileHash dbxray-windows.zip`の値が、`SHA256SUMS`の該当行と一致することを確かめる
3. 好きな場所に展開する
    * macOS: 同梱のJava実行環境は署名していないため、そのままではGatekeeperに実行を止められます。展開したフォルダで`xattr -dr com.apple.quarantine .`を実行してから起動してください

バージョンの付け方（どの変更で版が上がるか）は、[CONTRIBUTING.md](./CONTRIBUTING.md#バージョンとリリース)を参照してください。
使っている版は`--version`で確かめられます（[コマンドライン引数](./docs/usage/cli.md#コマンドライン引数)を参照）。

### zipファイルの構成

```
dbxray-windows
│  run.bat／run.sh                             ・・・ ダブルクリックで実行する起動ファイル（Linux／macOSの場合は`run.sh`）
│  dbxray.jar                                  ・・・ 実行可能形式Jarファイル
│  README.md                                   ・・・ このファイル
│  LICENSE                                     ・・・ このツールのライセンス（MIT）
│  THIRD-PARTY-NOTICES.txt                     ・・・ Jarファイルに同梱した依存ライブラリのライセンス
├─docs
│  └─usage                                     ・・・ CLIリファレンス・MCPサーバーの使い方
├─mcp
│     dbxray-mcp.jar                            ・・・ MCPサーバー（`docs/usage/mcp-server.md`を参照）
│     THIRD-PARTY-NOTICES.txt                   ・・・ MCPサーバーに同梱した依存ライブラリのライセンス
├─runtime                                       ・・・ 同梱のJava実行環境（ライセンスは`runtime/legal`）
└─conf
   └─config.yml                                ・・・ 設定ファイル
```

### 設定

1. `conf/config.yml`の`database`に、接続先DBの情報を記載する（[config.ymlの記載内容](./docs/usage/cli.md#configyml-の記載内容)を参照）
   * パスワードは`conf/config.yml`には書けません。環境変数`DBXRAY_DB_PASSWORD`で渡してください（[パスワードの指定](./docs/usage/cli.md#パスワードの指定)を参照）
2. 必要に応じて、同じファイルの出力の対象（`target`）・出力先（`output`）等を編集する（未編集でも全スキーマ・全テーブルが`./output`配下に出力される）

### 実行

* Windows: `run.bat`をダブルクリックする
* Linux／macOS: ターミナルから`./run.sh`を実行する（`chmod +x run.sh`が必要な場合があります）

コンソール画面が開いて処理が進み、完了すると`conf/config.yml`の`output.path`（未指定の場合は実行フォルダ直下の`output`フォルダ）にMarkdown形式のテーブル定義書が出力される。
処理結果はコンソールの`[result]:SUCCESS`／`[result]:FAIL`で確認できます（失敗したときは[終了コード・失敗時の表示](./docs/usage/cli.md#終了コード失敗時の表示)を参照）。

## やりたいこと別の案内

| やりたいこと | 参照先 |
|---|---|
| 出力するスキーマ・テーブル、出力先を指定する | [config.ymlの記載内容](./docs/usage/cli.md#configyml-の記載内容) |
| DBのコメントに無い説明、外部キー制約の無い関連、業務の観点を書き足す | [サイドカーYAML](./docs/usage/cli.md#サイドカーyamlannotations) |
| CI等で、接続情報・出力先を引数で渡す | [CLI引数による上書き](./docs/usage/cli.md#cli引数による上書き) |
| マイグレーション後のドキュメントの更新漏れをCIで検知する | [`--check`モード](./docs/usage/cli.md#db-vs-ドキュメントの差分検知--checkモード) |
| 削除したテーブルの定義書を出力先に残さない | [`--rm-dist`オプション](./docs/usage/cli.md#出力先ディレクトリの事前クリーンアップ--rm-distオプション) |
| 失敗の原因を調べる | [終了コード・失敗時の表示](./docs/usage/cli.md#終了コード失敗時の表示)・[ログ](./docs/usage/cli.md#ログ) |
| AIからテーブル定義を調べる | [MCPサーバー](./docs/usage/mcp-server.md) |
| ソースからビルドする・変更を提案する | [CONTRIBUTING.md](./CONTRIBUTING.md) |

## License

[MIT License](./LICENSE)

配布用zipに同梱している依存ライブラリのライセンスはzip内の`THIRD-PARTY-NOTICES.txt`（MCPサーバーは`mcp/THIRD-PARTY-NOTICES.txt`）を、Java実行環境のライセンスは`runtime/legal`を参照してください。