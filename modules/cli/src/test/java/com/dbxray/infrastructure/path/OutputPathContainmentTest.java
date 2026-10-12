package com.dbxray.infrastructure.path;

import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.BaseInfoEntity;
import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.document.ListDocumentType;
import com.dbxray.domain.model.snapshot.SnapshotKind;
import com.dbxray.domain.model.table.TableEntity;
import com.dbxray.domain.model.table.TableType;
import com.dbxray.domain.model.viewpoint.Viewpoint;
import com.dbxray.domain.service.path.OutputPathResolver;
import com.dbxray.domain.service.path.OutputRoot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * DB由来の名前に出力先の外を指す文字列が入っても、{@link OutputPathResolver}が返すパスが出力先の外を指さないことのテスト<br>
 * パスを返すメソッドをリフレクションで列挙するため、メソッドを追加すると自動的に検査の対象になる。
 * 引数の型に対応する値が無い場合は失敗するので、{@link #ARGUMENTS}に危険な名前を含む値を足すこと
 */
public class OutputPathContainmentTest {

  /** 出力先の外を指しうる名前（パストラバーサル・絶対パス・Windowsの区切りとドライブ指定・空・制御文字） */
  private static final List<String> HOSTILE_NAMES =
      List.of(
          "..",
          ".",
          "../../../../tmp/pwn",
          "/tmp/evil",
          "a\\..\\..\\b",
          "C:evil",
          "",
          "~",
          "a\u0000b",
          "x\n../../y");

  /** 利用者が指定する出力先そのものを解決するため、DB由来の名前を受け取らないメソッド */
  private static final Set<String> NOT_TARGETS = Set.of("resolveOutputBaseDir");

  /** 利用者が指定しうる出力先（"."は正規化すると空のパスになる） */
  private static final List<Path> BASE_DIRS =
      List.of(Path.of("output"), Path.of("."), Path.of(""), Path.of("output").toAbsolutePath());

  /** 引数の型ごとに、出力先と危険な名前から値を作る */
  private static final Map<Class<?>, BiFunction<Path, String, Object>> ARGUMENTS =
      Map.of(
          OutputRoot.class,
          (base, name) ->
              new OutputRoot(base, new BaseInfoEntity(name, Dbms.POSTGRESQL, 16, LocalDate.EPOCH)),
          TableEntity.class,
          (base, name) -> new TableEntity(name, name, name, name, TableType.TABLE, ""),
          String.class,
          (base, name) -> name,
          ListDocumentType.class,
          (base, name) -> ListDocumentType.FUNCTION,
          SnapshotKind.class,
          (base, name) -> SnapshotKind.TABLE,
          Viewpoint.class,
          (base, name) -> Viewpoint.of("vp", name, "", List.of("*")),
          Path.class,
          (base, name) -> base.resolve("tableList_db.md"),
          int.class,
          (base, name) -> 1);

  private final OutputPathResolver resolver = new DefaultOutputPathResolver();

  @TestFactory
  @DisplayName("パスを返すすべてのメソッドが、危険な名前でも出力先の配下のパスを返す")
  Stream<DynamicTest> testAllResolvedPathsStayInsideOutputDirectory() {
    return Arrays.stream(OutputPathResolver.class.getMethods())
        .filter(method -> method.getReturnType() == Path.class)
        .filter(method -> !NOT_TARGETS.contains(method.getName()))
        .flatMap(
            method ->
                BASE_DIRS.stream()
                    .flatMap(
                        base ->
                            HOSTILE_NAMES.stream()
                                .map(
                                    name ->
                                        DynamicTest.dynamicTest(
                                            method.getName()
                                                + "(base="
                                                + base
                                                + ", name="
                                                + name.replace("\n", "\\n")
                                                + ")",
                                            () ->
                                                assertInsideOutputDirectory(method, base, name)))));
  }

  private void assertInsideOutputDirectory(Method method, Path base, String name) throws Exception {
    final Object[] args =
        Arrays.stream(method.getParameterTypes())
            .map(
                type -> {
                  final BiFunction<Path, String, Object> factory = ARGUMENTS.get(type);
                  assertNotNull(
                      factory,
                      "No hostile argument for parameter type "
                          + type.getName()
                          + " of "
                          + method.getName()
                          + ". Add one to ARGUMENTS.");
                  return factory.apply(base, name);
                })
            .toArray();
    final Path resolved;
    try {
      resolved = (Path) method.invoke(resolver, args);
    } catch (InvocationTargetException e) {
      throw new AssertionError(method.getName() + " failed for name: " + name, e.getCause());
    }
    assertTrue(
        resolved.toAbsolutePath().normalize().startsWith(base.toAbsolutePath().normalize()),
        method.getName() + " escaped the output directory: " + resolved);
  }
}
