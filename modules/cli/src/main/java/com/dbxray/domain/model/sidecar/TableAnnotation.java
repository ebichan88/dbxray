package com.dbxray.domain.model.sidecar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1テーブル分の手動付帯情報（サイドカーYAML由来）を保持するrecordクラス<br>
 * DBからリバースした情報とは別に、人間が手動で追記した「テーブル説明」「テーブル備考」 「カラム備考」を保持する。テーブル定義の再生成時にこの情報をマージすることで、
 * 手書き情報が上書きで失われることを防ぐ
 *
 * @param description テーブル説明（複数行可）。未設定の場合は空文字
 * @param remarks テーブル備考（1行想定）。未設定の場合は空文字
 * @param columnRemarks 物理カラム名をキー、カラム備考を値とするマップ（サイドカーYAMLの記述順）
 */
public record TableAnnotation(
    String description, String remarks, Map<String, String> columnRemarks) {

  /** 付帯情報が存在しない場合に利用する空インスタンス */
  public static final TableAnnotation EMPTY = new TableAnnotation("", "", Map.of());

  /**
   * null安全のため、各値がnullの場合は空文字・空マップに正規化する<br>
   * カラム備考は記述順を保って複製する（{@link Map#copyOf}は順序を保たず、孤児付帯情報の警告の順序が実行ごとに変わるため）
   */
  public TableAnnotation {
    description = description == null ? "" : description;
    remarks = remarks == null ? "" : remarks;
    columnRemarks =
        columnRemarks == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(columnRemarks));
  }

  /**
   * 指定した物理カラム名に対応するカラム備考を取得するメソッド
   *
   * @return 対応するカラム備考。存在しない場合は空文字
   */
  public String columnRemark(String physicalColumnName) {
    return columnRemarks.getOrDefault(physicalColumnName, "");
  }

  /**
   * この付帯情報に含まれる物理カラム名のうち、実在するカラムに該当しないもの（＝孤児付帯情報）を抽出するメソッド
   *
   * @param actualColumnNames テーブルに実在する物理カラム名の集合
   * @return 実在カラムに該当しない、付帯情報側の物理カラム名（付帯情報の記述順）
   */
  public List<String> orphanColumnNames(Set<String> actualColumnNames) {
    return columnRemarks.keySet().stream()
        .filter(columnName -> !actualColumnNames.contains(columnName))
        .toList();
  }

  /**
   * 付帯情報が何も設定されていないか判定するメソッド
   *
   * @return 説明・備考・カラム備考のいずれも設定されていない場合はtrue
   */
  public boolean isEmpty() {
    return description.isBlank() && remarks.isBlank() && columnRemarks.isEmpty();
  }
}
