package com.dbxray;

import com.dbxray.application.PreviewFeature;
import java.util.Arrays;
import java.util.stream.Collectors;

/** {@code --help}・{@code --version}で表示する文言を組み立てるクラス */
final class CliUsage {

  /** Manifestの{@code Implementation-Version}が無い（jar以外から実行した）場合に表示する値 */
  static final String UNKNOWN_VERSION = "unknown";

  private static final String COMMAND_NAME = "dbxray";

  /** {@code --preview}の機能の一覧の字下げ（オプションの説明の列に揃え、2字下げる） */
  private static final String FEATURE_INDENT = " ".repeat(25);

  private CliUsage() {}

  /**
   * {@code --version}の表示
   *
   * @return コマンド名とバージョン。バージョンが分からない場合は{@value #UNKNOWN_VERSION}
   */
  static String version() {
    final String version = Dbxray.class.getPackage().getImplementationVersion();
    return COMMAND_NAME + " " + (version == null ? UNKNOWN_VERSION : version);
  }

  /** {@code --help}の表示 */
  static String help() {
    return """
        Usage: java -jar dbxray.jar [options]

        Exports database documents (Markdown: table definitions, ER diagrams, object lists) and a schema snapshot from a database.

        Options:
          --check              Check the differences between the database and the committed snapshot
                               (exit code 1 if differences are found)
          --rm-dist            Delete the output directory before writing (ignored with --check)
          --preview            Enable all preview features (no effect with --check). Their
                               output may change without notice. Preview features:
        %s
          --config=<path>      Configuration file to load (default: conf/config.yml)
          --help               Show this help and exit
          --version            Show the version and exit

        Overrides of the configuration file (--<name>=<value>):
        %s

        Exit codes:
          0  Success (with --check: no differences)
          1  Differences found (--check only)
          2  Failure

        See docs/usage/cli.md for details of each option.
        """
        .formatted(
            Arrays.stream(PreviewFeature.values())
                .map(feature -> FEATURE_INDENT + feature.id() + ": " + feature.description())
                .collect(Collectors.joining("\n")),
            CliArguments.overrideArgumentNames().stream()
                .map(name -> "  " + name + "=<value>")
                .collect(Collectors.joining("\n")));
  }
}
