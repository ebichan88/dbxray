package com.dbxray.domain.service.path;

import com.dbxray.domain.model.database.BaseInfoEntity;
import java.nio.file.Path;

/**
 * 出力先ベースディレクトリとデータベース基本情報の組み合わせを表す値オブジェクト<br>
 * {@code ExportSink}の実装が書き込みを行うたびに組み立て、
 * {@code *Writer}・{@link OutputPathResolver}へそのまま渡す。両者は常に対で必要とされるにもかかわらず、4層以上にわたって分解・再構築されながら渡されていたため、1つの値オブジェクトにまとめた
 *
 * @param baseDir 出力先のベースディレクトリパス
 */
public record OutputRoot(Path baseDir, BaseInfoEntity baseInfo) {}
