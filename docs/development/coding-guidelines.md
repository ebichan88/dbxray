# コーディングガイドライン

cli（`modules/cli/`）とMCPサーバー（`modules/mcp-server/`）のコードを書く・レビューするときの規約。人とAIエージェントの両方が対象。
規約の背景にある設計（レイヤー構成・実行フロー等）は[docs/architecture](../architecture/overview.md)、
ビルド・テストの実行方法やリリースは[CONTRIBUTING.md](../../CONTRIBUTING.md)を参照。

規約は、機械的に判定できるものはビルドで検査し、それ以外はレビューで確認する。

| 検査 | 対象 | 実行 |
|---|---|---|
| Spotless（google-java-format） | 書式・importの順序・未使用のimport | `./gradlew spotlessCheck`（直すときは`spotlessApply`） |
| `ArchitectureTest`（ArchUnit） | cli：レイヤーの依存方向・置き場所・命名・禁止API<br>MCPサーバー：パッケージの依存の向き・標準出力への書き込み・cliへの依存 | `./gradlew :cli:test`・`./gradlew :mcp-server:test` |
| doclint | Javadocの書式の誤り（タグの誤り・参照先の無いリンク等） | `./gradlew javadoc` |
| JaCoCo | cliのドメイン層・MCPサーバーの`catalog`のカバレッジ（line 95%・branch 85%） | `./gradlew build` |
| `OutputPathContainmentTest`・`MarkdownInjectionTest` | 出力先の外へのパス・Markdownへの注入 | `./gradlew :cli:test` |

検査に違反したら、検査を緩めずにコードのほうを直す。

## 目次

- [構造（レイヤー・置き場所・命名・DI）](#構造レイヤー置き場所命名di)
- [責務の分け方](#責務の分け方)
- [ファイルI/Oとパス](#ファイルioとパス)
- [例外](#例外)
- [入力の検証](#入力の検証)
- [ログ](#ログ)
- [セキュリティ](#セキュリティ)
- [Javaの書き方](#javaの書き方)
- [テスト](#テスト)
- [コメント・Javadoc](#コメントjavadoc)
- [変更の種類ごとに必要な作業](#変更の種類ごとに必要な作業)

## 構造（レイヤー・置き場所・命名・DI）

- cliのレイヤーの依存方向は`presentation → application → domain ← infrastructure`。規約の意図は
  [overview.mdの「レイヤー構成」](../architecture/overview.md#レイヤー構成)を参照。
  `ArchitectureTest`は、依存方向に加えて、`shared.exception`の制約、`domain.model`配下のパッケージの循環
  （向きそのものは[domain-model.md](../architecture/domain-model.md)に従う）、`java.nio.file.Files`・引数なしの`now()`・Guiceへの依存、
  接尾辞とパッケージの対応、関数の定義本体を読むパッケージ（`domain.service.tableusage`・`domain.model.tableusage`）で正規表現を使わないことを検査する。
- クラス名の接尾辞は[package-structure.mdの「命名」](../architecture/package-structure.md#命名)の表に揃える。
  名前の本体は[domain-model.mdの用語集](../architecture/domain-model.md)の用語（利用者向けのドキュメントで使っている呼び方）に揃え、
  同じものに別の名前を付けない。
- ドメインの概念（`domain.model`のクラス）を追加・改名・削除した場合や、ルールを持つ場所を移した場合は、
  `docs/architecture/domain-model.md`の図・ルール表・用語集も同じ変更で更新する。
- DB種別（Oracle/PostgreSQL）固有のSQLは`modules/cli/src/main/resources/mapper/{oracle,postgresql}/catalogMapper.xml`に分離されている。
  両DBで挙動を揃える変更は両方のmapperを確認・修正する。
- MCPサーバーはcliのコードに依存しない。接点はスナップショットの形式だけ
  （互換の守り方は[mcp-server.md](../architecture/mcp-server.md)）。
  MCPサーバーの`ArchitectureTest`は、パッケージの依存の向き（`tool → catalog ← snapshot`・`catalog ← insight`）、
  `catalog`がJDK以外に依存しないこと、標準出力への書き込み、cliへの依存を検査する。

### DI（Guice）

- 新しいリポジトリ実装やドメインサービスを追加した場合は、`config/module/DbxrayModule.java`に束縛を追加する。
  接続先の`SqlSessionFactory`と、DB種別で実装が変わる`CatalogRepository`だけは、DB接続後に組み立てる子のコンテナ用の
  `config/module/DatabaseDependentModule.java`に置く。
- コンストラクタには`jakarta.inject.Inject`を付ける（Guiceのアノテーションには依存しない）。
- インターフェースを持たない具象クラス（ユースケース等）は、`@Inject`付きコンストラクタがあればジャストインタイム束縛で解決されるため束縛しない。
- 束縛漏れは`DbxrayModuleTest`（実際にDIコンテナを組み立てるテスト）で検知できる。

## 責務の分け方

新規ロジックを書く前・既存クラスに数行足す前に、次の「小さな責務の混在」が再発していないか確認する（過去に実際に見つかった逸脱パターン）。

- **同じ値をループのたびに組み立てない。** `TableNamePatterns.of(...)`のような値オブジェクトをストリームの`.filter()`内やループ内で
  毎回組み立てていたら、呼び出し前に1回だけ組み立てて使い回す値オブジェクトへ切り出す（例: `domain.model.target.TableScope`）。
- **同じ引数群を層ごとに分解・再構築しない。** 同じ引数群が3層以上（エントリーポイント→コントローラー→ユースケース等）を
  そのまま渡っていたら、requestレコードにまとめる（例: `application.ExportSchemaRequest`/`CheckDocumentDiffRequest`）。
  渡す先で使われない引数が混ざっていないかも見る。
- **設定値を生のまま深い層へ渡さない。** 空白の除去・型への変換・検証は入口で1回だけ行い、設定誤りはDBへの問い合わせや
  出力先の削除より前に検知する（例: `DbxrayProperties`・`application.TargetSelection#of`）。
- **インフラ層に業務ルールを持たせない。** Repository実装は読み込みと型変換に専念し、デフォルト値の決定・名前の自動生成・
  識別子の解析といったルールはドメイン層（エンティティ・値オブジェクト・enum）のメソッドに持たせる
  （例: `ForeignKeyEntity#resolveLogicalRelationName`、`Cardinality#DEFAULT_FOR_LOGICAL_RELATION`）。
- **エンティティに実行時設定を渡さない。** スキーマ/テーブルの絞り込みリストのような実行時設定を、エンティティのメソッド引数で
  直接受け取らない。絞り込みは呼び出し側で値オブジェクトとして1回組み立て、エンティティへは`matches(entity)`のように問い合わせる。
- **エンティティにSQLの都合の形を持ち込まない。** 区切り文字で連結した文字列・NULLの代わりの空白等は、DTOからの変換時に
  ドメインの表現（`List`・空文字・enum）へ揃える。表示用の連結・空欄の描画はテンプレートで行う。
  出力ファイル名のような配置の規則も、エンティティやSQLに持たせず`DocumentLocations`で決める。
- **判定結果をドメインサービスからログへ直接出さない。** 突き合わせ・検証等の判定結果（利用者に知らせる警告等）は値として返し
  （例: `domain.model.target.ConsistencyNotice`）、どこへどう出力するかは呼び出し側が決める（書き込みの進捗を示すデバッグログは対象外）。
- **実行のたびに変わる値をDBから取得しない。** 日付等はDIで受け取る`java.time.Clock`から求める
  （テストで固定できるようにするため。引数なしの`now()`は`ArchitectureTest`が検出する）。
- **WriterでMarkdownを組み立てない。** 行・セクションの組み立ては`domain.service.writer`配下のテンプレートクラス
  （`*Templates`。種別ごとのサブパッケージにWriterと同居する）に委ね、Writerは「何を・どの順で・どのファイルに書くか」の段取りに専念する。

## ファイルI/Oとパス

- ファイルI/O（読み書き・一覧取得・一時ディレクトリ作成／削除等）は必ず`domain.repository.FileRepository`経由で行う。
- 出力先パスの組み立てやデフォルト値解決（未指定時のフォールバック等）は必ず`domain.service.path.OutputPathResolver`経由で行い、
  `application`層でパス文字列を直接組み立てない。
- Markdownのファイル名やドキュメント間の相対リンクは、テンプレート・Writerで`String.format`等により直接組み立てず、
  `domain.service.path.DocumentLocations`の規則を使う（スナップショットの配置は`domain.service.path.SnapshotLocations`）。

既存の抽象化を素通りする実装が増えると、テストが実ディスクI/Oに依存し始め、レイヤーの意図も崩れる。
新規ロジックを追加する前に、まずこれらのIFで足りないか確認する。DB由来の名前をパスに使うときの規則は[セキュリティ](#セキュリティ)を参照。

## 例外

設計は[overview.mdの「例外の扱いと終了コード」](../architecture/overview.md#例外の扱いと終了コード)を参照。

- 利用者が設定・入力・実行環境を見直せば解消する失敗は`shared.exception.UserCorrectableException`（設定ファイルの誤りは派生の
  `config.InvalidConfigurationException`）で表し、何を直せばよいかをメッセージに書いて投げる。それ以外（I/O・SQL・不具合）は
  非検査例外のまま伝える。
- ドメイン層に検査例外は使わず、呼び出し側に判断を委ねたい結果は値で返す。
- `UserCorrectableException`を投げるのは、入口（CLI引数・設定・出力先の検証）と、利用者の入力・実行環境に触れるインフラ
  （DBへの接続、サイドカーYAMLの読み込み）だけ。ドメイン層・アプリケーション層では投げない。利用者が直せる入力の誤りは、
  ユースケースを呼ぶ前に入口で検証する（ユースケースの中で見つかる設定の誤りがあれば、その検証を入口へ移す）。
- 捕捉はエントリーポイント（`Dbxray.main()`）の1箇所だけで、捕捉した例外の報告は`presentation.FailureReporter`が行う。
- 途中の層でcatchしてよいのは、検査例外を包む・利用者が直せる誤りへ置き換える・フォールバックする場合だけ。
  - 包むときは`cause`を渡す（原因のメッセージがファイルの内容を引用する場合を除く。[ログ](#ログ)の「出さないもの」を参照）。
  - tryの範囲は置き換えたい呼び出しだけに絞る。`catch (Exception e)`で広く包むと、別の失敗まで同じ文言になり原因も表示から消える。
  - catchしてログを出してから再スローしない。

## 入力の検証

設計は[overview.mdの「入力の検証」](../architecture/overview.md#入力の検証)を参照。
対象は設定ファイル・CLI引数・DB接続情報・サイドカーYAMLで、仕様は`docs/usage/cli.md`の各節に記載する。

- 設定項目・引数を追加・変更した場合は、`docs/usage/cli.md`の仕様の表と、検証する場所（`DbxrayProperties`・
  `CliArguments`等）を同じ変更で更新する。
- 未指定（キーの省略・空）は既定値。未知のキー・引数や解釈できない値は、既定値へ黙って置き換えずに失敗にする。
- サイドカーYAMLの個々の記述の誤りは、読み飛ばして警告する（WARNログはコンソールにも出る）。

## ログ

cliのログの設計（レベルごとの用途・設定・出力先）は[overview.mdの「ログ」](../architecture/overview.md#ログ)を参照。
MCPサーバーはログファイルを持たず、標準エラーへの表示だけ。表示する内容には下の「出さないもの」を同じく当てはめる。

- **レベル**
  - ERRORは`FailureReporter`だけが出す（途中の層でcatchしてERRORを出さない）。
  - WARNは「処理は続けたが、利用者が確認しないと誤った出力に気付けない」事柄だけに使う。WARNは画面にも出るため、
    確認の要らないものに使うと警告が読まれなくなる。
  - INFOは1回の実行で数件に収まる、調査の起点になる事実（接続先の版・件数・処理時間等）。テーブル・ファイルの件数に比例して
    増えるものはDEBUG以下にする。
- **書式**
  - 英語で、要旨を先に書き、値は末尾の`[key=value, ...]`にまとめる（例: `Exported schema objects. [tables=13, functions=9]`）。
  - log4j2のプレースホルダ（`{}`）を使い、文字列連結で組み立てない。
  - どこを直せばよいかが分かる位置（ファイルのパス・何件目の定義か・テーブル名）を添える。
  - 1つの誤りに対して警告を重ねて出さない（欠けた項目を「形式の誤り」としても報告する等）。
- **出さないもの**
  - パスワード、接続URL、CLI引数・設定値の値（書き誤った引数にパスワードが入りうる）。
  - 設定ファイル・サイドカーYAMLの内容を引用するライブラリの例外メッセージ。そのまま出さず、`config.YamlSyntaxErrors`のように種類と位置だけにする。
  - DB由来の文字列は、改行を含んでいても`log4j2.xml`の`%enc{%m}{CRLF}`が置き換えるため、呼び出し側でのエスケープは要らない
    （パターンからこの指定を外さない）。
- **置き場所**：ドメイン層ではログを出さず、判定結果を値（`ConsistencyNotice`等）で返して呼び出し側が出す（[責務の分け方](#責務の分け方)）。
- **既定の設定**：このツールのロガーの既定はINFO、ルート（ライブラリ）はWARNのままにする。MyBatisは実行したSQL（DEBUG）と取得した行
  （TRACE。DBのメタ情報そのもの）をこのツールのパッケージ配下のロガーへ出すため、既定を下げるとログが大きくなるうえ機密を含みうる。
  調査用の詳細は、利用者が`dbxray.log.level`で明示的に上げる。
- **MCPサーバーの標準出力**：MCPサーバーは標準出力をMCPのプロトコルに使う。`System.out`への出力や、標準出力へ出すログの設定を追加しない（表示は標準エラーへ）。`System.out`等への書き込みは`ArchitectureTest`が検出するが、ログの設定は検出しない。
- ログの仕様（レベル・記録する内容・調査時の上げ方）を変えたら、`docs/usage/cli.md`の「ログ」の節も同じ変更で更新する。
- 利用者に知らせる警告を足したら、テストでメッセージを確かめる（[テスト](#テスト)）。

## セキュリティ

DBのメタ情報（DB名・スキーマ名・テーブル名・コメント・関数やビューの定義等）は、ツールを実行する人とは別の人
（DBでCREATE・COMMENTの権限を持つ人）が書ける、信頼できない入力として扱う。サイドカーYAMLは利用者自身が書くファイルのため、
説明文（`description`）はMarkdownとしてそのまま出力してよい。出力・ログ・ファイル操作に触れる変更では、次を確認する。

- **パス**：DB由来の名前をファイル名・ディレクトリ名に使うときは、`*Locations`（`domain.service.path`）の規則に
  `PathSegments.encode`を通して組み立て、絶対パスは`OutputPathResolver`の実装（`within`で出力先の外を指さないことを確かめる）で解決する。
  `Path.resolve`等をそれ以外の場所で呼ぶと`ArchitectureTest`が失敗する。`OutputPathResolver`にメソッドを足すと、
  `OutputPathContainmentTest`が危険な名前（`..`・絶対パス・`\`・`C:`等）で自動的に検査する
  （引数の型が新しい場合は、テストの`ARGUMENTS`に危険な値を足す）。
- **Markdown・Mermaid**：DB由来の文字列は、表の行なら`MarkdownTemplateSupport.row`（書式で組み立てた行は`escapeTableRow`）、
  見出し・リストの項目なら`escapeInline`、コードブロックは`codeFence`、コードスパンは`codeSpan`を通し、Mermaidの図は
  `MermaidSupport`のメソッドで組み立てる（生のHTMLやコードブロックを閉じる行が出力されないようにするため）。
  DB由来の文字列を受け取るテンプレートを足したら、`MarkdownInjectionTest`の`renderedDocuments`にも足す。
- **秘密情報**：パスワードは環境変数（または`--db-password`）でだけ受け取り、設定ファイルには書かせない（`url`への埋め込みも誤りにする）。
  例外のメッセージ・ログ・画面の表示に、CLI引数・設定値・接続URL等の**値**をそのまま含めない（書き誤りの引数にパスワードが入りうる。
  誤りの報告は`FailureReporter`がログファイルにも残す）。
- **正規表現**：関数・プロシージャの定義本体を読む`domain.service.tableusage`・`domain.model.tableusage`では、正規表現を使わない
  （`java.util.regex`、`String`の`matches`・`replaceAll`・`replaceFirst`・`split`・`splitWithDelimiters`、`Scanner`）。
  定義本体はDBでCREATEの権限を持つ人が書ける入力で、バックトラックにより入力の長さに比例しない時間がかかりうる（ReDoS）ため、
  `SqlLexer`のように1文字ずつ読む。`ArchitectureTest`が違反を検知する。
- **削除**：ディレクトリの削除は`--rm-dist`の出力先だけで行い、削除してよいかは`OutputPathResolver.isRemovableOutputDir`で判定する。
  削除の対象を広げる変更をしない。
  ファイルの削除は、`--preview`を付けない実行が、プレビューの機能が出力した前回の参考情報`functionTableUsages.json`を消す1箇所だけ（`InsightWriter`。パスは`OutputPathResolver`で解決したもの）。
- **依存ライブラリ**：更新はDependabot（`.github/dependabot.yml`）が提案する。依存を足す・上げるときは、既知の脆弱性
  （GitHub Advisory Database等）が無い版を選ぶ。
- **デシリアライズ・SQL**：YAMLは`SafeConstructor`で読み、JSONのデシリアライズで型情報からクラスを生成する設定
  （デフォルトタイピング）を使わない。mapperのSQLは`#{}`でバインドし、`${}`で文字列を埋め込まない。
- 上記に触れる変更では、PRを作る前に`/security-review`（Claude Codeのコマンド）を実行し、指摘を確認する。

## Javaの書き方

書式はSpotless（google-java-format。importはstatic importを第1グループ、それ以外をASCII辞書順の1グループ）に任せ、手で揃えない。
コミットの前に`./gradlew spotlessApply`を実行する。そのうえで、既存のコードは次の書き方に揃えている。

- **値はrecordで表す。** エンティティ・値オブジェクト・requestはrecordにする。コレクションを持つrecordは、コンパクトコンストラクタで
  `List.copyOf`等により防御的に複製し、外から変更できないようにする。
- **`Optional`は戻り値にだけ使う。** 「見つからない」「解釈できない」を返すメソッド（例: `Tables#find`・`TableKey#parse`）に使い、
  フィールド・引数には使わない。
- **集合の操作はファーストクラスコレクションに持たせる。** エンティティの`List`を受け取って同じ検索・振り分けを各所で書かず、
  `Tables`・`ForeignKeys`等の複数形のクラスにメソッドを足す（[命名](../architecture/package-structure.md#命名)）。
- **時刻は`Clock`から求める。** 引数なしの`now()`を使わない（[責務の分け方](#責務の分け方)）。
- **意味の読めない数字・文字を式に埋めない。** `pos += 2`・`repeat(2)`・`length() - open >= 2`のように、何の長さ・位置かが
  式から読めないものは、名前を付けた変数・定数にするか、`"q'".length()`のように長さの元の文字列で書く。
  添字の0・1のような自明なものは対象外。
- **`boolean`を返すメソッドは状態を変えない。** 位置の更新や`incomplete`の記録のような副作用を伴うなら、判定（`has…`・`is…`）と
  実行（`scan…`・`skip…`）に分ける（例: `SqlLexer#hasAlternativeQuoteDelimiter`と`scanAlternativeQuote`）。
- **テストのためだけの口を本番コードに入れない。** 検証用の引数・フラグ・メソッドを本番のクラスに足さず、テスト側のヘルパーで包む。

## テスト

テストの配置は[package-structure.mdの「テスト」](../architecture/package-structure.md#テスト)を参照。

- **種類と実行**：単体テスト（`modules/cli/src/test`・`modules/mcp-server/src/test`。DB不要）は`./gradlew test`、PostgreSQLの結合テスト（`modules/cli/src/integrationTest`）は
  `./gradlew integrationTest`、Oracleの結合テストは`./gradlew oracleIntegrationTest`で実行する（いずれもDockerが必要。詳細はCONTRIBUTING.md）。
- **ライブラリ**：JUnit 5のアサーション（`org.junit.jupiter.api.Assertions`）を使う。モックライブラリは使わず、ドメインの
  インタフェース（`FileRepository`等）を実装した手書きの偽物（例: 各テストの`InMemoryFileRepository`）で差し替える。
  インタフェース越しに差し替えられない依存があれば、テストの書き方より先に依存の向きを見直す。
- **名前**：テストメソッドにはJavadocを書かず、`@DisplayName`に意図を書く。「対象のメソッド: 条件と期待する結果」の形を基本とする
  （例: `execute: ユースケースが正常終了した場合はSUCCESSの結果を返す`）。テストクラスには1行の要旨のJavadocを書く。
- **共通部品**：フィクスチャ・アサーションは`testsupport`（`EntityFixtures`・`ForeignKeyFixtures`・`MarkdownAssert`等）にあるものを使い、
  複数のテストで要るものはここに足す。Markdownの出力は`MarkdownAssert.assertMarkdownEquals`で改行コードを揃えて全体を比べる。
- **ログ**：利用者に知らせる警告を足したら、`testsupport.CapturedLogs`でメッセージを確かめるテストを書く。
- **安全性の検査への登録**：DB由来の文字列を受け取るテンプレートは`MarkdownInjectionTest`の`renderedDocuments`に、
  新しい型の引数を取る`OutputPathResolver`のメソッドは`OutputPathContainmentTest`の`ARGUMENTS`に足す（[セキュリティ](#セキュリティ)）。
- **カバレッジ**：cliのドメイン層とMCPサーバーの`catalog`は、単体テストでline 95%・branch 85%を下回るとビルドが失敗する。
  基準に届かないときは、基準を下げずにテストを足すか、到達しない分岐を消す。
- **結合テストとベースライン**：結合テストは、各SQLの取得結果と、出力全体がベースライン（`docs/sample/{postgres,oracle}/output`）と
  一致することを確かめる。出力を意図して変えた場合だけ、`verify`スキル（`.claude/skills/verify/SKILL.md`）に従って確認したうえで
  ベースラインを出力し直す。
- テストを無効化（`@Disabled`等）・削除して通すことはしない。

## コメント・Javadoc

コードを書いた・変更した直後に、書いたコメント1つずつについて「これを消したら読み手は何を失うか」を自問する。
失うものが無ければ書かない・消す。書いてよいのは、コードを読んでも復元できない情報だけ。

- 書いてよい例
  - なぜこの値・この順序・このAPIなのか（判断の理由。処理内容の言い換えではない）
  - 変更すると何が壊れるか（前提条件・不変条件）
  - 外部ライブラリ・DBMS・処理系の非自明な仕様、直感に反する挙動
  - ファイル名・パスの命名規則の実例（`OutputPathResolver`/`DocumentLocations`のJavadocにある
    「例: `{base}/{DB名}/...`」等、命名規則そのものはコードから読み取れないため残す）
- 書かない。書きたくなったら該当箇所に置く
  - コードの実況（WHAT） → 名前で示す。コメントで繰り返さない
  - Javadocの`@param`/`@return`が型・メソッド名の言い換えになっているもの → 型・シグネチャから
    わからないこと（単位・境界値・nullや未指定時の扱い等）だけに削るか、無ければ`@param`/`@return`ごと書かない
    （この「言い換えを削る」対象は`@param`/`@return`単位の記述であり、クラス・インタフェース・レコード
    自体の先頭1行は対象外。型の先頭サマリは、名前とほぼ同義でも省略しない。同じ関心を持つ型が並ぶ場所
    （例: `domain.service.writer`配下のWriterクラス、`domain.repository`配下のRepositoryインタフェース）で、
    ある型にだけサマリが無いという状態を作らない）
  - `@since`/`@version`/`@author`タグ → 付けない（単著かつ外部公開APIではないため情報価値が無い。Git履歴で代替する）
  - 作業経緯・変更履歴・issue/PR番号 → コミットメッセージ・PR
  - 当たり前のこと、やらないことの無限列挙 → 削除（意図的に外した設計判断はコミットメッセージに書く）
  - 呼び出し元・呼び出し先や他クラスの実装の説明 → 削除（そのクラス・メソッド自身の責務ではない）
  - 「なぜこの実装が良いか」という設計の弁護 → コミットメッセージ
  - コメントアウトしたコード → 削除（必要ならGit履歴から戻す）
- Javadocを書く対象は宣言の種類で決める（`modules/cli/src/main`・`modules/mcp-server/src/main`。上の「書かない」はどの種類にも当てはまる）。
  可視性は外側の型で絞った後のもので判断する（privateなネストクラスのメソッドはprivate扱い）。

  | 対象 | 扱い | 書く内容 |
  |---|---|---|
  | 型（ネスト・privateを含む全型）、`package-info.java` | 必須 | 1行の要旨 |
  | インタフェース・抽象メソッド | 必須 | 契約（境界値、null・空の扱い、順序、投げる例外） |
  | 具象のpublic・protected・パッケージプライベートメソッド | 必須 | 要旨。`@param`/`@return`は型・名前から分からない情報があるものだけ |
  | `@Override`メソッド | 必須 | `/** {@inheritDoc} */`（契約を持つ親を辿れるようにする）。実装固有の補足があれば続けて書く |
  | コンストラクタ | 書かない | 検証・正規化・複製をするコンパクトコンストラクタと、引数に名前から分からない制約がある場合（例外クラス等）だけ書く。「コンストラクタ」とだけ書いた要旨は書かない |
  | privateメソッド、フィールド・定数、enum定数 | 情報があれば | 判断の理由・前提条件・値の由来や単位等、名前から分からないことがある場合だけ |
  | recordのコンポーネント（型のJavadocの`@param`） | 情報があれば | 意味・未指定時の扱い・単位等、名前から分からないものだけ。全コンポーネントを並べなくてよい |

  テストメソッド・テストクラスの扱いは[テスト](#テスト)を参照。
  Javadocの書式の誤りは`./gradlew javadoc`（doclint）で検知できる（記述漏れは上の表で許しているため検査しない）。
- コメントを書く前に、名前を直す・値に定数名を付ける・長い説明が要る塊を関数やクラスに分ける、
  のいずれかで説明自体を不要にできないか検討する。
- 残す場合は1文を短く区切り結論を先に書く。1コメント1話題とし、日本語の文は句点で終える。

## 変更の種類ごとに必要な作業

何を変えたかによって、同じ変更（同じPR）で行う作業が決まっている。PRテンプレートの「動作確認」「確認事項」はこの表に対応する。

| 変えたもの | 同じ変更で行うこと |
|---|---|
| `catalogMapper.xml`・ドメイン層（エンティティ・テンプレート・ER図生成ロジック等） | `./gradlew integrationTest`を実行する。mapperのSQLを変えた場合は、変えた取得結果を確かめるテストを`PostgresCatalogRepositoryIT`に足す |
| 出力（ベースライン`docs/sample/postgres/output`との差分が出る） | `verify`スキルに従って出力結果を確認し、意図した変更であればベースラインを出力し直す。READMEの「出力例」（観点「人事管理」のER図・employeeのカラム情報）に貼ったものが出力と食い違っていないかも見る。プレビューの機能（`--preview`）の出力やサンプルの関数が変わる場合は、差分のベースライン`docs/sample/{postgres,oracle}/output-preview`も作り直す（`verify`スキルの手順7） |
| Oracle用mapper、またはOracleの出力が変わりうるドメイン層 | `./gradlew oracleIntegrationTest`（メモリを2GB程度使う）を実行し、変えた取得結果を確かめるテストを`OracleCatalogRepositoryIT`に足す。ベースラインは`docs/sample/oracle/output`（`verify`スキルのOracleの節） |
| 片方のDBのmapper | もう片方のmapperも確認し、挙動を揃える |
| スナップショットの形式（`domain.model.snapshot`のrecord） | `./gradlew :mcp-server:test`を実行する（`SampleSnapshotContractTest`がベースラインを読む）。互換性の無い変更なら`DatabaseSnapshot.FORMAT_VERSION`を上げ、`SnapshotDirectoryReader.SUPPORTED_FORMAT_VERSION`を追従させる |
| ER図のMermaidの表記（`MermaidSupport`） | `./gradlew :mcp-server:test`を実行する（MCPサーバーの`get_er_diagram`が同じ表記を自前で持ち、`SampleErDiagramContractTest`が観点ページのベースラインと比べる） |
| 設定項目・CLI引数 | `docs/usage/cli.md`の仕様の表と、検証する場所（`DbxrayProperties`・`CliArguments`等）を更新する |
| ログの仕様 | `docs/usage/cli.md`の「ログ」の節を更新する |
| ドメインの概念（`domain.model`のクラス）・ルールを持つ場所 | `docs/architecture/domain-model.md`の図・ルール表・用語集を更新する |
| リポジトリ実装・ドメインサービスの追加 | Guiceの束縛を追加する（[DI](#diguice)） |
| Javadoc | `./gradlew javadoc`を実行する |
| DB由来の文字列の出力・パス・秘密情報・ファイルの削除・依存ライブラリ | [セキュリティ](#セキュリティ)の項目を確認し、PRを作る前に`/security-review`を実行する |
| [CONTRIBUTING.mdの「互換性を約束する範囲」](../../CONTRIBUTING.md#互換性を約束する範囲)を壊す変更 | PRに`breaking`ラベルを付け、PRの本文に移行の手順を書く |

`build.gradle`の`version`は、リリースのときにだけ上げる（手順はCONTRIBUTING.mdの「リリースの手順」）。
