# Contributing

不具合の報告は[Issue](../../issues/new/choose)のテンプレートから、変更の提案はPRでお願いします。
ビルド・テストの方法は[ビルド・テスト](#ビルドテスト)、
コードの規約は[コーディングガイドライン](./docs/development/coding-guidelines.md)、コードの構成は[docs/architecture](./docs/architecture/overview.md)を参照してください。

## ビルド・テスト

### 主なディレクトリ構成

```
dbxray
├─cli              ・・・ このツール本体（Gradleのサブプロジェクト）
│  └─src
│      ├─main            ・・・ javaソースコード（com.dbxray）
│      ├─test            ・・・ 単体テスト（DB不要）
│      └─integrationTest ・・・ 結合テスト（Docker上のPostgreSQL・Oracleを使う）
├─mcp-server       ・・・ スナップショットをAIから検索するMCPサーバー（Gradleのサブプロジェクト）
├─docs
│  ├─usage         ・・・ 利用者向けのドキュメント（READMEから辿るCLIリファレンス・MCPサーバーの使い方）
│  ├─architecture  ・・・ 設計のドキュメント
│  ├─development   ・・・ 開発者向けのドキュメント（コーディングガイドライン）
│  └─sample        ・・・ サンプルDBのDDLと出力のベースライン（結合テストの入力）
├─scripts          ・・・ 配布用zipに同梱する起動スクリプト
├─build.gradle     ・・・ サブプロジェクト共通のビルド設定
└─settings.gradle
```

### ビルド

以下のコマンドを実行することで、`dbxray/modules/cli/build/libs`フォルダ配下に`dbxray.jar`が、
`dbxray/modules/mcp-server/build/libs`フォルダ配下にMCPサーバーの`dbxray-mcp.jar`が作成される

```
gradlew build
```

### テスト

`gradlew build`（`gradlew test`）で実行される単体テストはDBを使わない。MCPサーバー（`mcp-server`）のテストもここに含まれ、
ビルドしたjarを子プロセスで起動してMCPクライアントから呼び出すE2Eテストまでを行う。
mapperのSQLを実DBに対して確かめる結合テストは、Dockerで使い捨てのPostgreSQLを起動するため別のタスクに分けてある（Dockerが必要）。

```
gradlew integrationTest
```

結合テストは`docs/sample/postgres/ddl.sql`を流し込んだDBに対して、各SQLの取得結果と、出力全体がコミット済みのベースライン
（`docs/sample/postgres/output`）と一致することを確かめる（基本情報の作成日は比較しない）。出力仕様を意図して変えた場合は、
ベースラインを出力し直してコミットする。

Oracle用のmapperは、Docker上の使い捨てのOracle Database Free（`gvenzl/oracle-free`。イメージ約1.3GB・メモリ2GB程度）に対して確かめる。
PostgreSQLより重いため、さらに別のタスクに分けてある。

```
gradlew oracleIntegrationTest
```

`docs/sample/oracle/ddl.sql`（PostgreSQL版と同じスキーマ構成をOracleで作るDDL）を流し込んだDBに対して、各SQLの取得結果と、
出力全体がベースライン（`docs/sample/oracle/output`）と一致することを確かめる。
PRではGitHub Actions（`.github/workflows/ci.yml`）でこれらのテストがすべて実行される。

#### カバレッジ

`gradlew build`（`gradlew test`）を実行すると、単体テストのカバレッジ計測（JaCoCo）も行われる。

```
gradlew jacocoTestReport
```

でHTMLレポート（`modules/cli/build/reports/jacoco/test/html/index.html`・`modules/mcp-server/build/reports/jacoco/test/html/index.html`）を生成できる。
また`gradlew build`（＝`check`）には`jacocoTestCoverageVerification`が含まれており、cliのドメイン層
（`com.dbxray.domain`配下）とMCPサーバーの`catalog`（`com.dbxray.mcp.catalog`配下）の
単体テストカバレッジが、それぞれline 95%・branch 85%を下回るとビルドが失敗する
（結合テスト・MCPサーバーのE2Eテストは対象外。基準は各サブプロジェクトの`build.gradle`の`jacocoTestCoverageVerification`で定義）。
PRではGitHub ActionsがカバレッジレポートをArtifactとしてアップロードし、PRへの概要コメントも投稿する。

#### 静的解析

* **SpotBugs**: `gradlew build`（＝`check`）に`spotbugsMain`が含まれており、配布するコード（main）のバグを静的に検出する。
  確度が高い（`reportLevel = HIGH`）指摘が1件でもあるとビルドが失敗する（`gradlew spotbugsMain`だけでも実行できる）。
  指摘は、検査を緩めずコードを直す。直せない誤検知だけを、理由のコメントを添えて`gradle/spotbugs/exclude.xml`に書く
* **CodeQL**: GitHub Actions（`.github/workflows/codeql.yml`）がPRと`main`への反映、および週1回に実行する。
  結果はリポジトリのSecurityタブ（Code scanning）に出る

### Javadoc

以下のコマンドを実行することで、`dbxray/modules/cli/build/docs/javadoc`フォルダ配下にjavadocが作成される（`build`配下はGit管理対象外）

```
gradlew javadoc
```

### 実行方法

`modules/cli/build/libs/conf/config.yml`に必要な設定値を記載した状態で、`modules/cli/build/libs`で以下のコマンドを実行する（`gradlew build`のたびに`modules/cli/src/main/resources/conf`の内容で上書きされるため、手元の設定を残したい場合は別の場所に置いて`--config`で指定する）

```
java -jar .\dbxray.jar
```

## バージョンとリリース

### 版の番号

[Semantic Versioning](https://semver.org/lang/ja/)に従い、`メジャー.マイナー.パッチ`の形で付けます。
基準は、下の[互換性を約束する範囲](#互換性を約束する範囲)を壊すかどうかです。

| 変更 | 1.0.0以降 | 0.x（今） |
|---|---|---|
| 互換性を約束する範囲を壊す変更（破壊的変更） | メジャー版を上げる | マイナー版を上げる |
| 互換性のある機能追加 | マイナー版を上げる | パッチ版を上げる |
| 不具合の修正 | パッチ版を上げる | パッチ版を上げる |

0.xの間は、公開仕様を固めている途中として、破壊的変更をマイナー版で行います。公開仕様が固まった時点で1.0.0にします。
破壊的変更は、0.xの間もリリースノートに移行の手順を書いて知らせます。

### 互換性を約束する範囲

| 対象 | 破壊的変更の例 | 破壊的変更ではない例 |
|---|---|---|
| CLI引数・終了コード | 引数の削除・改名、値の形式の変更、終了コードの意味の変更 | 引数の追加 |
| 設定ファイル（`config.yml`）の項目・値の形式 | 項目の削除・改名・移動、既定値の変更 | 省略できる項目の追加 |
| サイドカーYAMLの記法 | 項目の削除・改名、これまで書けた記述を誤りにする | 省略できる項目の追加 |
| スナップショット・参考情報の形式 | 項目の削除・改名・意味の変更（`formatVersion`も上げる） | 項目の追加 |
| MCPサーバーのツール名・引数・返す項目、リソースのURIの形式 | ツール・引数・返す項目の削除・改名・意味の変更、リソースのURIの形式の変更（会話に書いたURIが読めなくなる） | ツール・省略できる引数・返す項目の追加、リソースの`name`・`description`の表示の調整 |
| Markdownの出力のファイル名・ディレクトリ構成 | ファイル名・配置の変更（ドキュメント間のリンク・外部からのリンクが切れる） | 新しい種類のファイルの追加 |
| 配布zipの構成・jarの名前・起動スクリプト | jar・起動スクリプトの改名・移動 | ファイルの追加 |
| 対応するDBMSの版 | 対応する最も古い版を上げる | 対応する版を増やす |

[プレビューの機能](./docs/usage/cli.md#プレビューの機能--previewオプション)（`--preview`を付けたときだけ動く機能）の出力・挙動は、
上の表の対象（参考情報のファイル・MCPサーバーの返す項目等）であっても互換性の対象にしません。精度・出力の形を固めるまでは予告なく変えます。
プレビューを外す（`--preview`を付けなくても動くようにする）ときは機能追加として扱い、リリースノートで知らせます。

Markdownの出力の中身（見出し・表の列・表示の書式）は、人が読む成果物として互換性の対象にしません。
列の追加や表示の調整は、機能追加・不具合の修正として扱います。
`--check`で差分として検知される変更は、リリースノートの「機能追加・改善」等で分かるようにします。

### PRのラベル

リリースノートは、前の版からマージしたPRを、ラベルごとに分類して自動で作ります（[.github/release.yml](./.github/release.yml)）。
PRには次のラベルのうち当てはまるものを付けます。

| ラベル | 用途 |
|---|---|
| `breaking` | 破壊的変更。PRの本文に移行の手順を書く |
| `preview` | プレビューの機能の追加・変更（`--preview`を付けたときだけ変わるもの） |
| `enhancement` | 機能追加・改善 |
| `bug` | 不具合の修正 |
| `documentation` | ドキュメントだけの変更 |
| `dependencies` | 依存ライブラリ・Actionの更新 |
| `ignore-for-release` | リリースノートに載せない変更（CIの設定の調整等） |

### リリースの手順

版の出所は`build.gradle`の`version`だけです。`--version`（cli）と`serverInfo.version`（MCPサーバー）は、この値をjarのマニフェスト経由で表示します。

1. `build.gradle`の`version`を次の版に上げるPRを作り、mainへマージする
    * 破壊的変更があれば、移行の手順を`.github/release-notes/v{版}.md`に書いて同じPRに含める（自動生成のリリースノートの前に載る）
2. マージしたmainのコミットに、`v`を付けた版のタグを付けてpushする

    ```
    git switch main
    git pull
    git tag v0.2.0
    git push origin v0.2.0
    ```

3. タグのpushで[Releaseワークフロー](./.github/workflows/release.yml)が動き、次の順に進む。途中で失敗した場合はリリースを作らない
    1. タグと`build.gradle`の`version`が一致するかを確かめる
    2. CI（単体テスト・PostgreSQL／Oracleの結合テスト）を実行する
    3. OSごとの配布用zipを作る
    4. zipと`SHA256SUMS`を添付したGitHub Releaseを作る

失敗した場合は、原因を直してから同じ版のタグを付け直します（リリースは作られていないため、版を上げなくてよい）。

```
git push origin :refs/tags/v0.2.0
git tag -d v0.2.0
```

タグに`-`を含む版（`v0.2.0-rc.1`等）はプレリリースになり、READMEが案内する最新版（`releases/latest`）には出ません。
