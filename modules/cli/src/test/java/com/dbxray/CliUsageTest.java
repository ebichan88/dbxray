package com.dbxray;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.application.PreviewFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CliUsage の--help・--versionの表示内容に関するテスト */
public class CliUsageTest {

  @Test
  @DisplayName("help: フラグと、上書きできるすべての引数名を示す")
  void testHelpListsArguments() {
    String help = CliUsage.help();

    for (String flag :
        new String[] {
          "--check", "--rm-dist", "--preview", "--config=<path>", "--help", "--version"
        }) {
      assertTrue(help.contains(flag), flag);
    }
    for (String name : CliArguments.overrideArgumentNames()) {
      assertTrue(help.contains(name + "=<value>"), name);
    }
  }

  @Test
  @DisplayName("help: プレビューの機能は、PreviewFeatureの名前と説明から示す")
  void testHelpListsPreviewFeatures() {
    String help = CliUsage.help();

    for (PreviewFeature feature : PreviewFeature.values()) {
      assertTrue(help.contains(feature.id() + ": " + feature.description()), feature.id());
    }
  }

  @Test
  @DisplayName("version: コマンド名で始まり、Manifestが無い環境でもバージョン欄を表示する")
  void testVersion() {
    assertTrue(CliUsage.version().startsWith("dbxray "));
    assertFalse(CliUsage.version().isBlank());
  }
}
