# AGENTS.md

このリポジトリで作業するAIエージェント向けのガイド。

## このリポジトリについて

DBに接続し、Markdown形式のテーブル定義書・ER図を出力するJava(21)製CLIツール。
同じ取得結果から機械可読なスキーマのスナップショット（JSON Lines）も出力し、`--check`モードではDBとの差分を検知する。
スナップショットをAIからMCPのツールで検索できるようにするMCPサーバーも同じリポジトリにある。
機能仕様（出力されるドキュメントの種類、ER図の分割・多重度判定ルール、対応DBMS等）は、利用者向けのドキュメント
（入口の[README.md](./README.md)と、そこから辿る[docs/usage/cli.md](./docs/usage/cli.md)・[docs/usage/mcp-server.md](./docs/usage/mcp-server.md)）に詳しい。
実装に手を入れる前に該当箇所を確認すること。

## ディレクトリ構成

Gradleのマルチプロジェクト構成。

- `modules/cli/` — このツール本体（`modules/cli/src/{main,test,integrationTest}`）
- `modules/mcp-server/` — スナップショットを検索するMCPサーバー。cliのコードには依存せず、接点はスナップショットの形式だけ。
  設計は[docs/architecture/mcp-server.md](./docs/architecture/mcp-server.md)を参照

ルートの`build.gradle`にはサブプロジェクト共通の設定（プラグインのバージョン・Javaのバージョン・Spotless・doclint等）だけを置き、
固有の設定は各サブプロジェクトの`build.gradle`に書く。`docs/`・`scripts/`・`.github/`はリポジトリルートにある。Gradleのラッパーとビルド用の設定（SpotBugsの除外設定等）は`gradle/`に置く。
以降の文書で`src/...`と書いたパスは、特に断りが無ければ`modules/cli/src/...`を指す。

## アーキテクチャドキュメント

Javaのパッケージ構成・レイヤー構成・DI・実行フロー・ドメインモデルは以下にまとめてある。
コードを読み進める前に目を通すとコンテキスト消費を抑えられる。

- [docs/architecture/overview.md](./docs/architecture/overview.md) — レイヤー構成、実行フロー、DB切り替え、
  メモリ効率のための分割取得、サイドカーYAML等、アーキテクチャ全体の設計意図
- [docs/architecture/domain-model.md](./docs/architecture/domain-model.md) — ドメインの概念（テーブル・関連・
  サイドカー・出力対象等）同士の関係図、主なルールを持つ場所、用語集（利用者向けのドキュメント・コード・会話で使う呼び方の対応）
- [docs/architecture/package-structure.md](./docs/architecture/package-structure.md) — 全パッケージ・主要クラスの
  役割一覧（リファレンス）
- [docs/architecture/mcp-server.md](./docs/architecture/mcp-server.md) — MCPサーバー（`modules/mcp-server/`）の構成・ツール・
  スナップショット形式との互換の守り方

## コーディングガイドライン

コードの規約（構造・責務の分け方・ファイルI/Oとパス・例外・入力の検証・ログ・セキュリティ・Javaの書き方・テスト・コメント／Javadoc）は
[docs/development/coding-guidelines.md](./docs/development/coding-guidelines.md)にまとめてある。
**コードを書く・変更する前に必ず読み**、該当する節に従うこと。

特に次は、違反するとビルド・レビューで差し戻しになるか、利用者に害が出るため、作業のたびに確認する（詳細はリンク先）。

- `ArchitectureTest`・カバレッジ基準等の検査に違反したら、検査を緩めずにコードのほうを直す。テストを無効化・削除して通さない。
  （[構造](./docs/development/coding-guidelines.md#構造レイヤー置き場所命名di)・[テスト](./docs/development/coding-guidelines.md#テスト)）
- 新規ロジックを書く前・既存クラスに数行足す前に、過去の逸脱パターンが再発していないか確認する。
  （[責務の分け方](./docs/development/coding-guidelines.md#責務の分け方)）
- ファイルI/Oは`FileRepository`、パスは`OutputPathResolver`・`*Locations`経由にし、`application`層やWriterでパス・ファイル名を組み立てない。
  （[ファイルI/Oとパス](./docs/development/coding-guidelines.md#ファイルioとパス)）
- DBのメタ情報は信頼できない入力として扱い、出力・パスには既存のエスケープ・エンコードを通す。パスワード・接続URL・CLI引数や
  設定値の値を、例外のメッセージ・ログ・表示に含めない。（[セキュリティ](./docs/development/coding-guidelines.md#セキュリティ)・
  [ログ](./docs/development/coding-guidelines.md#ログ)）
- MCPサーバーでは`System.out`への出力や、標準出力へ出すログの設定を追加しない（標準出力はMCPのプロトコルに使う）。
- 捕捉はエントリーポイントの1箇所だけ。途中の層でcatchしてERRORログを出したり、ログを出して再スローしたりしない。
  （[例外](./docs/development/coding-guidelines.md#例外)）
- コメントは「消したら読み手が何を失うか」で判断し、コードから復元できない情報だけを書く。
  （[コメント・Javadoc](./docs/development/coding-guidelines.md#コメントjavadoc)）

## 変更したら行うこと

mapper・ドメイン層・スナップショットの形式・設定項目等を変えたときに、同じ変更で行う作業（結合テストの実行・ベースラインの出力し直し・
ドキュメントの更新・`Components`の組み立て等）は、ガイドラインの[変更の種類ごとに必要な作業](./docs/development/coding-guidelines.md#変更の種類ごとに必要な作業)の表に従う。
作業を終える前にこの表を見直し、該当する行をすべて済ませること。
