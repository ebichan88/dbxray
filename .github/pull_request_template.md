## 概要

<!-- 何を変えたか、なぜ変えるかを書いてください。関連するIssueがあれば `Closes #123` の形式で書いてください。 -->

## 変更内容

<!-- 主な変更点を箇条書きで書いてください。意図して外した設計判断があれば、その理由も書いてください。 -->

-

## 破壊的変更

<!-- 互換性を約束する範囲（CONTRIBUTING.mdの「互換性を約束する範囲」）を壊す変更があるかを選んでください。
     ある場合は、PRに`breaking`ラベルを付け、利用者が行う移行の手順を書いてください（リリースノートに載せます）。 -->

- [ ] 無い
- [ ] ある（移行の手順: ）

## 動作確認

<!-- 実行したものにチェックを入れてください。実行していない場合は、その理由を書いてください。 -->

- [ ] `./gradlew build`（単体テスト・Spotless・カバレッジ基準）
- [ ] `./gradlew javadoc`（Javadocを変えた場合）
- [ ] `./gradlew integrationTest`（mapper・ドメイン層を変えた場合）
- [ ] `./gradlew oracleIntegrationTest`（Oracle用mapperを変えた場合、またはOracleの出力が変わりうる場合）
- [ ] `./gradlew :mcp-server:test`（スナップショットの形式を変えた場合）

## 確認事項

<!-- 該当しない項目は、チェックを入れずに「対象外」と書くか、行ごと削除してください。 -->

- [ ] 出力が変わる場合、`verify`スキルの手順で結果を確認し、ベースライン（`docs/sample/{postgres,oracle}/output`）を出力し直した
- [ ] mapperのSQLを変えた場合、PostgreSQL・Oracleの両方のmapperを確認し、取得結果を確かめるテストを`*CatalogRepositoryIT`に足した
- [ ] 設定項目・CLI引数を変えた場合、`docs/usage/cli.md`の仕様と入力の検証（`DbxrayProperties`・`CliArguments`等）を更新した
- [ ] ドメインの概念を追加・改名・削除した場合、`docs/architecture/domain-model.md`を更新した
- [ ] スナップショットの形式を互換性なく変えた場合、`DatabaseSnapshot.FORMAT_VERSION`を上げ、`SnapshotDirectoryReader.SUPPORTED_FORMAT_VERSION`を追従させた
- [ ] 新しいリポジトリ実装・ドメインサービスを追加した場合、またコンストラクタの引数を変えた場合、`Components`の組み立てを直した
- [ ] DB由来の文字列の出力・パス・秘密情報・ファイルの削除・依存ライブラリに触れた場合、コーディングガイドライン（docs/development/coding-guidelines.md）の「セキュリティ」の項目を確認した

## 補足

<!-- レビューで見てほしい点、スクリーンショット、出力の差分の例などがあれば書いてください。 -->
