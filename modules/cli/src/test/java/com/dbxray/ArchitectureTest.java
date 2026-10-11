package com.dbxray;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** コーディングガイドラインの規約のうち、レイヤーの依存方向・置き場所・命名等の機械的に判定できるものを検査するテスト */
public class ArchitectureTest {

  private static final String ROOT = "com.dbxray";

  private static final JavaClasses MAIN_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  @DisplayName("レイヤーの依存方向は presentation → application → domain ← infrastructure に従う")
  void testLayerDependencies() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        // 末尾に..を付けないため直下のパッケージ（エントリーポイント）だけを指し、配下の各層は含まない
        .layer("Entry")
        .definedBy(ROOT)
        .layer("Presentation")
        .definedBy(ROOT + ".presentation..")
        .layer("Application")
        .definedBy(ROOT + ".application..")
        .layer("Domain")
        .definedBy(ROOT + ".domain..")
        .layer("Infrastructure")
        .definedBy(ROOT + ".infrastructure..")
        .layer("Config")
        .definedBy(ROOT + ".config..")
        .whereLayer("Presentation")
        .mayOnlyBeAccessedByLayers("Entry")
        .whereLayer("Application")
        .mayOnlyBeAccessedByLayers("Entry", "Presentation")
        .whereLayer("Domain")
        .mayOnlyBeAccessedByLayers(
            "Entry", "Presentation", "Application", "Infrastructure", "Config")
        .whereLayer("Infrastructure")
        .mayOnlyBeAccessedByLayers("Entry", "Config")
        .whereLayer("Config")
        .mayOnlyBeAccessedByLayers("Entry", "Infrastructure")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("domain.modelの概念ごとのパッケージ同士は循環して依存しない")
  void testDomainModelPackagesAreFreeOfCycles() {
    slices().matching(ROOT + ".domain.model.(*)..").should().beFreeOfCycles().check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("sharedには失敗の分類を伝える例外だけを置き、JDK以外に依存させない")
  void testSharedContainsOnlyJdkDependentExceptions() {
    classes()
        .that()
        .resideInAPackage(ROOT + ".shared..")
        .should()
        .resideInAPackage(ROOT + ".shared.exception")
        .andShould()
        .beAssignableTo(Exception.class)
        .andShould()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", ROOT + ".shared..")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("application・domainはjava.nio.file.Filesを直接呼ばない（FileRepositoryを経由する）")
  void testApplicationAndDomainDoNotUseFilesDirectly() {
    noClasses()
        .that()
        .resideInAnyPackage(ROOT + ".application..", ROOT + ".domain..")
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(Files.class)
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("出力先の配下のパスは、出力先の外を指さないことを確かめるOutputPathResolverの実装でだけ組み立てる")
  void testOutputPathsAreResolvedOnlyByOutputPathResolver() {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".infrastructure.path")
        .should()
        .callMethod(Path.class, "resolve", String.class)
        .orShould()
        .callMethod(Path.class, "resolve", Path.class)
        .orShould()
        .callMethod(Path.class, "resolveSibling", String.class)
        .orShould()
        .callMethod(Path.class, "resolveSibling", Path.class)
        .because(
            "DB由来の名前を含むパスは、OutputPathContainmentTestが出力先の外を指さないことを検査する"
                + "OutputPathResolver（DocumentLocations等の規則）を経由して組み立てる必要があるため")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("関数の定義本体を読むパッケージは正規表現を使わない（DB由来の入力で処理が極端に遅くならないようにするため）")
  void testTableUsageDoesNotUseRegularExpressions() {
    noClasses()
        .that()
        .resideInAnyPackage(
            ROOT + ".domain.service.tableusage..", ROOT + ".domain.model.tableusage..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("java.util.regex..")
        .orShould()
        .callMethod(String.class, "matches", String.class)
        .orShould()
        .callMethod(String.class, "replaceAll", String.class, String.class)
        .orShould()
        .callMethod(String.class, "replaceFirst", String.class, String.class)
        .orShould()
        .callMethod(String.class, "split", String.class)
        .orShould()
        .callMethod(String.class, "split", String.class, int.class)
        .because(
            "定義本体はDBでCREATEの権限を持つ別の人が書ける入力で、正規表現の組み合わせはバックトラックで"
                + "入力の長さに比例しない時間がかかりうる（ReDoS）。1文字ずつ読む字句の解析で読む")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("現在日時は引数なしのnow()で取得せず、DIで受け取るClockから求める")
  void testCurrentTimeIsTakenFromClock() {
    noClasses()
        .should()
        .callMethod(LocalDate.class, "now")
        .orShould()
        .callMethod(LocalDateTime.class, "now")
        .orShould()
        .callMethod(ZonedDateTime.class, "now")
        .orShould()
        .callMethod(OffsetDateTime.class, "now")
        .orShould()
        .callMethod(Instant.class, "now")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("@Injectはjakarta.injectのものを使い、Guiceに依存するのはエントリーポイントとconfigだけ")
  void testOnlyEntryAndConfigDependOnGuice() {
    noClasses()
        .that()
        .resideOutsideOfPackages(ROOT, ROOT + ".config..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.google.inject..")
        .check(MAIN_CLASSES);
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.google.inject.Inject")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("接尾辞が役割を表すクラスは、その役割の置き場所にある")
  void testSuffixesMatchPackages() {
    classes()
        .that()
        .haveSimpleNameEndingWith("Entity")
        .should()
        .resideInAPackage(ROOT + ".domain.model..")
        .check(MAIN_CLASSES);
    classes()
        .that()
        .haveSimpleNameEndingWith("Dto")
        .should()
        .resideInAnyPackage(ROOT + ".infrastructure.db.repository.dto", ROOT + ".presentation.dto")
        .check(MAIN_CLASSES);
    classes()
        .that()
        .haveSimpleNameEndingWith("Templates")
        .should()
        .resideInAPackage(ROOT + ".domain.service.writer..")
        .check(MAIN_CLASSES);
    classes()
        .that()
        .haveSimpleNameEndingWith("Writer")
        .should()
        .resideInAPackage(ROOT + ".domain.service..")
        .check(MAIN_CLASSES);
    classes()
        .that()
        .haveSimpleNameEndingWith("Locations")
        .should()
        .resideInAPackage(ROOT + ".domain.service.path")
        .check(MAIN_CLASSES);
    classes()
        .that()
        .haveSimpleNameEndingWith("Usecase")
        .or()
        .haveSimpleNameEndingWith("Request")
        .should()
        .resideInAPackage(ROOT + ".application")
        .check(MAIN_CLASSES);
  }

  @Test
  @DisplayName("リポジトリのインタフェースはdomain.repositoryに、実装はinfrastructureに置く")
  void testRepositoryInterfacesAndImplementations() {
    classes()
        .that()
        .resideInAPackage(ROOT + ".domain.repository")
        .should()
        .beInterfaces()
        .andShould()
        .haveSimpleNameEndingWith("Repository")
        .check(MAIN_CLASSES);
    classes()
        .that()
        .haveSimpleNameEndingWith("Repository")
        .and()
        .areNotInterfaces()
        .should()
        .resideInAPackage(ROOT + ".infrastructure..repository")
        .check(MAIN_CLASSES);
  }
}
