package com.dbxray;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.config.ConfigFile;
import com.dbxray.config.InvalidConfigurationException;
import com.dbxray.infrastructure.db.ConnectionSettings;
import com.dbxray.infrastructure.db.DatabaseTypeDetector;
import com.dbxray.infrastructure.db.MyBatisSqlSessionFactories;
import com.dbxray.presentation.ExportSchemaController;
import com.dbxray.presentation.FailureReporter;
import com.dbxray.presentation.dto.DiffCheckResultDto;
import com.dbxray.presentation.dto.ResultDto;
import com.dbxray.presentation.type.ExitStatus;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * DBドキュメント出力処理を呼び出すクラス<br>
 * 処理全体（入力の検証・DBへの接続・DIコンテナの組み立てを含む）で起きた例外を{@link #main}の1箇所で捕捉し、
 * {@link FailureReporter}で報告したうえで、終了状態をプロセスの終了コードへ変換する
 */
public class Dbxray {

  /** 通常実行が失敗した場合の報告の要旨 */
  private static final String EXPORT_FAILURE_SUMMARY = "Failed to output database documents.";

  /** 差分検知（{@code --check}モード）が失敗した場合の報告の要旨 */
  private static final String CHECK_FAILURE_SUMMARY =
      "Failed to check differences between the database and the snapshot.";

  private Dbxray() {}

  /**
   * DBドキュメント出力処理のエントリーポイントメソッド<br>
   * {@code --help}・{@code --version}は表示だけして終了する（設定ファイルの読み込みやDBへの接続は行わない）。<br>
   * 終了コードは、成功（{@code --check}で差分なしを含む）は0、{@code --check}で差分ありは1、失敗は2以上（現在は2のみ）
   *
   * @param args コマンドライン引数（CLI引数・フラグの解析は{@link CliArguments}を参照）
   */
  public static void main(String[] args) {
    final CliArguments cliArguments = CliArguments.parse(args);
    if (cliArguments.isHelp()) {
      System.out.println(CliUsage.help());
      System.exit(ExitStatus.SUCCESS.code());
      return;
    }
    if (cliArguments.isVersion()) {
      System.out.println(CliUsage.version());
      System.exit(ExitStatus.SUCCESS.code());
      return;
    }
    ExitStatus exitStatus;
    try {
      exitStatus = cliArguments.isCheck() ? runCheck(cliArguments) : run(cliArguments);
    } catch (Throwable e) {
      // 例外はここで1箇所にまとめて捕捉する（途中の層では捕捉しない）。JVMのエラー（Error）も捕捉するのは、
      // 捕捉しないとJVMが終了コード1で終了し、--checkの「差分あり」と区別できなくなるため
      new FailureReporter(
              cliArguments.isCheck() ? CHECK_FAILURE_SUMMARY : EXPORT_FAILURE_SUMMARY,
              System.out::println)
          .report(e);
      exitStatus = ExitStatus.FAILURE;
    }
    System.exit(exitStatus.code());
  }

  /** DBドキュメント出力処理実行メソッド */
  private static ExitStatus run(CliArguments cliArguments) {
    System.out.println(
        """
                Starting output of database documents.
                Please wait a moment ...
                """);
    cliArguments.requireKnownArguments();
    final ConfigFile configFile = ConfigFile.load(cliArguments.configPath());
    final ExportSchemaRequest request =
        DbxrayProperties.of(configFile, cliArguments.settingOverrides())
            .toExportSchemaRequest(cliArguments.isRmDist(), cliArguments.previewFeatures());
    final Components components = Components.create();
    components.outputDirectoryValidator().validate(request);
    final ConnectionSettings connectionSettings = connectionSettings(configFile, cliArguments);
    final ResultDto resultDto = createController(components, connectionSettings).execute(request);
    System.out.println(resultDto.resultMessage());
    return ExitStatus.SUCCESS;
  }

  /**
   * DB vs ドキュメントの差分検知処理実行メソッド（{@code --check}モード）
   *
   * @return 終了状態（差分が見つかった場合は{@link ExitStatus#DIFFERENCE_FOUND}）
   */
  private static ExitStatus runCheck(CliArguments cliArguments) {
    if (cliArguments.isRmDist()) {
      System.out.println("Note: --rm-dist is ignored in --check mode.");
    }
    if (cliArguments.isPreview()) {
      System.out.println("Note: --preview has no effect in --check mode.");
    }
    System.out.println(
        """
                Starting check of differences between the database and the snapshot.
                Please wait a moment ...
                """);
    cliArguments.requireKnownArguments();
    final ConfigFile configFile = ConfigFile.load(cliArguments.configPath());
    final CheckDocumentDiffRequest request =
        DbxrayProperties.of(configFile, cliArguments.settingOverrides())
            .toCheckDocumentDiffRequest();
    final Components components = Components.create();
    components.outputDirectoryValidator().validate(request);
    final ConnectionSettings connectionSettings = connectionSettings(configFile, cliArguments);
    final DiffCheckResultDto diffCheckResultDto =
        createController(components, connectionSettings).checkDiff(request);
    System.out.println(diffCheckResultDto.resultMessage());
    return diffCheckResultDto.exitStatus();
  }

  /**
   * 設定ファイルの{@code database}に、環境変数のパスワード・CLI引数の値を重ねたDB接続情報を組み立てるメソッド
   *
   * @throws InvalidConfigurationException DB接続情報に誤りがある場合
   */
  private static ConnectionSettings connectionSettings(
      ConfigFile configFile, CliArguments cliArguments) {
    return ConnectionSettings.merge(
        configFile.scalarSection(DbxrayProperties.DATABASE_SECTION),
        cliArguments.connectionOverrides(),
        System.getenv());
  }

  /**
   * DBへ接続して接続先のDB種別を判定し、DB種別に依存する部品を組み立ててコントローラーを取得するメソッド<br>
   * 入力の検証は、DBへ接続する前に行う。接続した後に検証すると、DBに接続できない環境（接続情報の誤り・DBの停止中）では
   * 接続エラーだけが報告され、それを直して再実行するまで入力の誤りに気付けないため。
   * ただし、DB種別で実装が変わる部品（CatalogRepositoryと、それに依存するユースケース）は、DB種別が接続して初めて分かるため、
   * 接続した後にしか組み立てられない。そこで、DB種別に依存しない部品は検証のために先に{@link Components}から取得し、
   * DB種別に依存する部品は、接続した後にここで足す。<br>
   * {@link SqlSessionFactory}はここで1回だけ生成し、リポジトリで使い回す
   *
   * @param components DB種別に依存しない部品を持つ、依存関係の組み立て役
   * @param connectionSettings 検証済みのDB接続情報
   */
  private static ExportSchemaController createController(
      Components components, ConnectionSettings connectionSettings) {
    final SqlSessionFactory sqlSessionFactory =
        MyBatisSqlSessionFactories.create(connectionSettings);
    return components.controller(DatabaseTypeDetector.detect(sqlSessionFactory), sqlSessionFactory);
  }
}
