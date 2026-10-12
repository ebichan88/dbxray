package com.dbxray.domain.service.path;

import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.schemaobject.FunctionEntity;
import com.dbxray.domain.model.schemaobject.Functions;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出力するMarkdownドキュメントの配置（ファイル名と、データベース単位ディレクトリからの相対パス）を一元的に定めるクラス<br>
 * 出力先の絶対パス（{@link OutputPathResolver}）と、ドキュメント間の相対リンク（テンプレート）の双方が
 * このクラスの規則を参照することで、ファイル名を変更した場合にパスとリンクが食い違わないようにする。<br>
 * 複数のデータベースを同じ出力先へ出力してもドキュメントが混ざらないよう、
 * DB1つ分のドキュメントはすべて{@code {DB名}/}ディレクトリ配下にまとめる（このクラスが返す相対パスはこのディレクトリからの相対パスとし、
 * {@code {DB名}/}自体の付与は{@link OutputPathResolver}が行う）。配置は次のとおり。一覧・ER図・観点ページ・READMEは{@code {DB名}/}直下に、
 * テーブル定義書・関数等の個別定義書は{@code {スキーマ名}/{区分}/}配下に置く。<br>
 * パスに用いるDB由来の名前（DB名・スキーマ名・テーブル名等）は、出力先の外を指さないよう{@link PathSegments}で置き換える
 */
public final class DocumentLocations {

  private static final String MARKDOWN_EXTENSION = ".md";
  private static final String LIST_FILENAME_PATTERN = "%sList_%s" + MARKDOWN_EXTENSION;
  private static final String ER_DIAGRAM_FILENAME_PATTERN = "erDiagram_%s_%s" + MARKDOWN_EXTENSION;
  private static final String ER_DIAGRAM_GROUP_FILENAME_PATTERN =
      "erDiagram_%s_%s_group%d" + MARKDOWN_EXTENSION;
  private static final String VIEWPOINT_FILENAME_PATTERN = "viewpoint_%s_%s" + MARKDOWN_EXTENSION;
  private static final String README_FILENAME = "README" + MARKDOWN_EXTENSION;
  private static final String PATH_SEPARATOR = "/";

  /** オーバーロードされた関数・プロシージャの個別定義ファイル名で、名前と番号を区切る文字 */
  private static final String OVERLOAD_SEPARATOR = "_";

  /** データベース単位ディレクトリ直下のドキュメントから、そのディレクトリを指す相対パス */
  private static final String FROM_DATABASE_ROOT = "./";

  /** 個別定義書（{@code {スキーマ名}/{区分}/}配下）から、データベース単位ディレクトリを指す相対パス */
  private static final String FROM_DEFINITION = "../../";

  private DocumentLocations() {}

  /**
   * 一覧のファイル名
   *
   * @return {@code {接頭辞}List_{DB名}.md}
   */
  public static String listFile(ListDocumentType type, String dbName) {
    return String.format(LIST_FILENAME_PATTERN, type.getPrefix(), PathSegments.encode(dbName));
  }

  /**
   * スキーマ別ER図のファイル名
   *
   * @return {@code erDiagram_{DB名}_{スキーマ名}.md}
   */
  public static String erDiagramFile(String dbName, String schemaName) {
    return String.format(
        ER_DIAGRAM_FILENAME_PATTERN, PathSegments.encode(dbName), PathSegments.encode(schemaName));
  }

  /**
   * ノード数の上限を超えたため分割したスキーマ別ER図の、1グループ分のファイル名
   *
   * @param groupNo グループ番号（1始まり）
   * @return {@code erDiagram_{DB名}_{スキーマ名}_group{グループ番号}.md}
   */
  public static String erDiagramGroupFile(String dbName, String schemaName, int groupNo) {
    return String.format(
        ER_DIAGRAM_GROUP_FILENAME_PATTERN,
        PathSegments.encode(dbName),
        PathSegments.encode(schemaName),
        groupNo);
  }

  /**
   * 表示名は日本語・空白を含みうるためファイル名に用いず、ファイル名に使える文字に限った識別子を用いる
   *
   * @return {@code viewpoint_{DB名}_{観点の識別子}.md}
   */
  public static String viewpointFile(String dbName, Viewpoint viewpoint) {
    return String.format(VIEWPOINT_FILENAME_PATTERN, PathSegments.encode(dbName), viewpoint.id());
  }

  /**
   * 分割ページは本体ページと同じディレクトリに置く
   *
   * @param pageIndex ページ番号（1始まり）
   * @return 本体ページのファイル名の拡張子の前に{@code _{ページ番号}}を付けたファイル名
   * @throws IllegalArgumentException 本体ページのファイル名がMarkdownの拡張子で終わらない場合
   */
  public static String pageFile(String fileName, int pageIndex) {
    if (!fileName.endsWith(MARKDOWN_EXTENSION)) {
      throw new IllegalArgumentException("Not a markdown file name: " + fileName);
    }
    return fileName.substring(0, fileName.length() - MARKDOWN_EXTENSION.length())
        + "_"
        + pageIndex
        + MARKDOWN_EXTENSION;
  }

  /**
   * テーブル定義書の、データベース単位ディレクトリからの相対パス
   *
   * @return {@code {スキーマ名}/{テーブル区分}/{物理テーブル名}.md}
   */
  public static String tableDefinitionFile(TableEntity table) {
    return String.join(
        PATH_SEPARATOR,
        PathSegments.encode(table.schemaName()),
        table.tableType().getName(),
        PathSegments.encode(table.physicalTableName()) + MARKDOWN_EXTENSION);
  }

  /**
   * 同じスキーマに同名の関数・プロシージャ（オーバーロード）が複数存在する場合は、ファイル名が重複しないよう作成順の番号を付ける（例: {@code calc_1},
   * {@code calc_2}）。存在しない場合は関数・プロシージャ名をそのまま用いる
   */
  public static String functionDefinitionName(FunctionEntity function) {
    if (!function.isOverloaded()) {
      return function.functionName();
    }
    return function.functionName() + OVERLOAD_SEPARATOR + function.overloadIndex();
  }

  /**
   * 個別定義のファイルが同じになる関数・プロシージャを求めるメソッド<br>
   * オーバーロードに付けた番号が別の関数の名前と重なると（{@code calc}のオーバーロードの{@code calc_1}と、
   * 別の関数{@code calc_1}）、後に書いたほうが先に書いたファイルを上書きする
   *
   * @return 個別定義ファイルの相対パス（{@link #schemaObjectFile}）ごとの、そのファイルを書く関数・プロシージャ（取得順）。
   *     ファイルが同じになるものだけを含み、無ければ空
   */
  public static Map<String, List<FunctionEntity>> functionsSharingDefinitionFile(
      Functions functions) {
    final Map<String, List<FunctionEntity>> byFile = new LinkedHashMap<>();
    functions
        .asList()
        .forEach(
            function ->
                byFile
                    .computeIfAbsent(
                        schemaObjectFile(
                            function.schemaName(),
                            ListDocumentType.FUNCTION,
                            functionDefinitionName(function)),
                        file -> new ArrayList<>())
                    .add(function));
    byFile.values().removeIf(sharing -> sharing.size() < 2);
    byFile.replaceAll((file, sharing) -> List.copyOf(sharing));
    return Collections.unmodifiableMap(byFile);
  }

  /**
   * 関数・シーケンス・型の個別定義を置くディレクトリの、データベース単位ディレクトリからの相対パス
   *
   * @param kind オブジェクトの区分（{@link ListDocumentType#FUNCTION}／
   *     {@link ListDocumentType#SEQUENCE}／{@link ListDocumentType#TYPE}）
   * @return {@code {スキーマ名}/{区分}}
   */
  public static String schemaObjectDirectory(String schemaName, ListDocumentType kind) {
    return String.join(PATH_SEPARATOR, PathSegments.encode(schemaName), kind.getPrefix());
  }

  /**
   * 関数・シーケンス・型の個別定義ファイルの、データベース単位ディレクトリからの相対パス
   *
   * @param kind オブジェクトの区分（{@link ListDocumentType#FUNCTION}／
   *     {@link ListDocumentType#SEQUENCE}／{@link ListDocumentType#TYPE}）
   * @param name 個別定義ファイル名（拡張子を除く）
   * @return {@code {スキーマ名}/{区分}/{名前}.md}
   */
  public static String schemaObjectFile(String schemaName, ListDocumentType kind, String name) {
    return schemaObjectDirectory(schemaName, kind)
        + PATH_SEPARATOR
        + PathSegments.encode(name)
        + MARKDOWN_EXTENSION;
  }

  /**
   * データベース単位ディレクトリの、出力ベースディレクトリからの相対パス
   *
   * @return {@code {DB名}}
   */
  public static String databaseDirectory(String dbName) {
    return PathSegments.encode(dbName);
  }

  /**
   * データベース単位ディレクトリにまとめたドキュメントへのリンクを集約するREADMEのファイル名
   *
   * @return {@code README.md}
   */
  public static String readmeFile() {
    return README_FILENAME;
  }

  /**
   * データベース単位ディレクトリ直下のドキュメントから、指定したドキュメント（同ディレクトリからの相対パス）へのリンク
   *
   * @return 相対リンク（例: {@code ./tableList_testdb.md}）
   */
  public static String linkFromDatabaseRoot(String relativePath) {
    return FROM_DATABASE_ROOT + relativePath;
  }

  /**
   * 個別定義書から、指定したドキュメント（データベース単位ディレクトリからの相対パス）へのリンク
   *
   * @return 相対リンク（例: {@code ../../tableList_testdb.md}）
   */
  public static String linkFromDefinition(String relativePath) {
    return FROM_DEFINITION + relativePath;
  }
}
