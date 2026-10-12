package com.dbxray;

import com.dbxray.application.CheckDocumentDiffRequest;
import com.dbxray.application.ExportSchemaRequest;
import com.dbxray.domain.repository.FileRepository;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.shared.exception.UserCorrectableException;
import jakarta.inject.Inject;
import java.nio.file.Path;

/**
 * 設定された出力先ディレクトリ（設定ファイルの{@code output.path}）を、DBへ接続する前に検証するクラス<br>
 * 出力先の誤りは、DBからの取得を待った後の書き込みや、{@code --rm-dist}による削除の時点ではなく、入口で報告する。
 *
 * <ul>
 *   <li>既存のファイル（ディレクトリではないもの）を指す場合は誤りとする。{@code --rm-dist}ではそのファイルが削除され、
 *       指定しなければ書き込み時に失敗するため。存在しないパスは、書き込み時に作成するため誤りとしない
 *   <li>{@code --rm-dist}指定時に、ルート・ホームディレクトリ・カレントディレクトリ自体など、削除すると被害が甚大なディレクトリを指す場合は誤りとする
 * </ul>
 *
 * 出力先パスの解決（未指定時の既定値）と、削除してよいディレクトリかの判定は{@link OutputPathResolver}に、
 * パスの状態の問い合わせは{@link FileRepository}に委ねる
 */
class OutputDirectoryValidator {

  private final OutputPathResolver outputPathResolver;
  private final FileRepository fileRepository;

  @Inject
  OutputDirectoryValidator(OutputPathResolver outputPathResolver, FileRepository fileRepository) {
    this.outputPathResolver = outputPathResolver;
    this.fileRepository = fileRepository;
  }

  /**
   * DBドキュメント出力（通常実行）の出力先を検証するメソッド
   *
   * @throws UserCorrectableException 出力先が既存のファイルを指す場合や、{@code --rm-dist}で削除してはならないディレクトリの場合
   */
  void validate(ExportSchemaRequest request) {
    final Path outputBaseDir = requireDirectoryOrAbsent(request.outputPath());
    if (request.rmDist() && !outputPathResolver.isRemovableOutputDir(outputBaseDir)) {
      throw new UserCorrectableException(
          "Refusing to run --rm-dist because output.path resolves to an unsafe directory. "
              + "Specify a dedicated output directory in output.path. [outputBaseDir="
              + outputBaseDir.toAbsolutePath().normalize()
              + "]");
    }
  }

  /**
   * 差分検知（{@code --check}モード）の比較対象の出力先を検証するメソッド
   *
   * @throws UserCorrectableException 出力先が既存のファイルを指す場合
   */
  void validate(CheckDocumentDiffRequest request) {
    requireDirectoryOrAbsent(request.outputPath());
  }

  /**
   * 出力先ベースディレクトリを解決し、既存のファイル（ディレクトリではないもの）を指していないことを確かめるメソッド
   *
   * @param outputPath 設定された出力先のパス（未指定可）
   * @return 出力先ベースディレクトリ
   * @throws UserCorrectableException 既存のファイルを指す場合
   */
  private Path requireDirectoryOrAbsent(String outputPath) {
    final Path outputBaseDir = outputPathResolver.resolveOutputBaseDir(outputPath);
    if (fileRepository.exists(outputBaseDir) && !fileRepository.isDirectory(outputBaseDir)) {
      throw new UserCorrectableException(
          "output.path points to an existing file, not a directory. "
              + "Specify a directory (or a path that does not exist yet) in output.path. "
              + "[outputBaseDir="
              + outputBaseDir.toAbsolutePath().normalize()
              + "]");
    }
    return outputBaseDir;
  }
}
