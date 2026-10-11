---
name: verify
description: >-
  docs/sample/postgres/ddl.sql を使い捨てのPostgreSQL(Docker)に流し込み、jarをビルド・実行して
  実際にテーブル定義書を出力し、結果を確認する手順。catalogMapper.xmlやドメイン層
  （エンティティ・テンプレート・ER図生成など）を変更した後、「動作確認して」「出力結果を確認して」
  「verify」などと言われたとき、またはマッパーSQLや出力ロジックに手を入れた後に必ず使う。
---

# dbxray 動作確認手順（PostgreSQL）

DB接続を要するツールなので、単体テストが通っていても実際のDBに対するSQLが壊れていることがある
（実例: マルチバイトコメントの改行崩れによるSQL構文エラー、文字列リテラルの改行混入による
Markdown表崩れ。詳細は末尾「踏み抜いた地雷」参照）。**マッパーXMLや出力ロジックを変更したら、
必ずこの手順で実DBに対して1回通してから完了とする。**

まずは`./gradlew integrationTest`を実行する。この手順と同じDDL・設定で出力し、ベースライン
（`docs/sample/postgres/output`）との一致までを自動で確かめる（`modules/cli/src/integrationTest`）。
この手順は、結合テストが失敗した出力を目で確かめる場合と、意図した出力の変更に合わせてベースラインを
出力し直す場合に使う。

## 前提

- Dockerが使えること
- JDK 21 が `/usr/lib/jvm/java-21-amazon-corretto` にあること（無ければ `update-alternatives --list java` 等で確認し読み替える）

## 手順

### 1. 使い捨てPostgreSQLを起動してDDLを流し込む

既存のコンテナがあれば作り直す（DDLの変更を確実に反映するため、毎回スキーマをまっさらにする）。

```bash
docker rm -f dbxray-verify-db 2>/dev/null
docker run -d --name dbxray-verify-db \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=testdb \
  -p 15432:5432 postgres:16
# 起動待ち
for i in $(seq 1 30); do docker exec dbxray-verify-db pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
docker cp docs/sample/postgres/ddl.sql dbxray-verify-db:/tmp/ddl.sql
docker exec -e PGPASSWORD=postgres dbxray-verify-db \
  psql -U postgres -d testdb -v ON_ERROR_STOP=1 -f /tmp/ddl.sql
```

`ON_ERROR_STOP=1` で流し込みが失敗したらここで気づける。DDL側の問題とアプリ側の問題を切り分けるため、
まずこのステップ単体で成功することを確認する。

### 2. jarをビルドする

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto
export PATH=$JAVA_HOME/bin:$PATH
./gradlew build --console=plain
```

`modules/cli/build/libs/dbxray.jar` と `modules/cli/build/libs/conf/` 一式が作られる
（`test`タスクも実行されるが数秒で終わる。ユニットテストが落ちたらそこで止めて直す）。

### 3. 設定ファイルを用意する

`build.dependsOn(copyResources)`（`modules/cli/build.gradle`）により、ビルドのたびに `modules/cli/src/main/resources/conf/` の内容で
`modules/cli/build/libs/conf/` が**上書きされる**。設定はビルドの影響を受けない場所に別のファイルとして書き、`--config`で渡す。

`/tmp/verify-postgres.yml` を作成する（パスはリポジトリの絶対パスで書く）:

```bash
cat > /tmp/verify-postgres.yml <<EOF
target:
  schemas: [sample]
output:
  path: $PWD/docs/sample/postgres/output
annotations: $PWD/docs/sample/postgres/annotations.sample.yml
EOF
```

Markdownに加えて、常に`docs/sample/postgres/output/snapshot/`配下へスキーマのスナップショット
（JSON Lines）も出力される（ベースラインにコミット済み）。

### 4. 実行する

DB接続情報はCLI引数で渡す（設定ファイルの`database`は空でよい）。
`--config`を付けない場合は、カレントディレクトリの`conf/config.yml`を読む（`modules/cli/build/libs`で実行すると、
全項目が未指定の配布用の設定＝全スキーマ対象を拾ってしまう）ため、必ず`--config`で手順3のファイルを指定する。

```bash
java -jar modules/cli/build/libs/dbxray.jar \
  --config=/tmp/verify-postgres.yml \
  --db-driver=org.postgresql.Driver \
  --db-url=jdbc:postgresql://localhost:15432/testdb \
  --db-username=postgres \
  --db-password=postgres
```

`[result]:SUCCESS` が出れば成功。`[result]:FAIL` の場合は次の「原因調査」を参照。

### 5. 出力を確認する

```bash
cd <リポジトリルート>
git status --short docs/sample/postgres/output
git diff docs/sample/postgres/output
```

`docs/sample/postgres/output/` をベースライン（コミット済みの「正しい出力」）として扱っている場合、
`git diff`で見るのが最速。**「基本情報」表の作成日（実行日）は毎回変わるので、その1行だけの差分は
無視してよい**。それ以外の差分（カラム・制約・ER図・多重度など）が意図した変更と一致しているか確認する。
スナップショット（`snapshot/`配下）は作成日を含まないため、意図した変更が無ければ差分ゼロになる。

初回や、意図的に出力仕様を変えた場合は、この出力一式をコミットしてベースラインを更新する
（ユーザーの明示的な許可なくcommitはしないこと）。

### 6. 後片付け

```bash
docker rm -f dbxray-verify-db
```

継続して何度も試す場合はコンテナを残しておいてよい（次回はステップ1で作り直せばよい）。

### 7. プレビューの機能の差分のベースライン（`--preview`）

`--preview`で変わる・増えるファイル（関数・プロシージャの定義書、`insights/{DB名}/{スキーマ名}/functionTableUsages.json`）だけを、
既定のベースラインとは別の差分のベースライン（`docs/sample/postgres/output-preview`）に置いている。結合テストは、既定のベースラインに
差分のベースラインを重ねたものと`--preview`の出力を比べる（`ExportBaseline.assertMatches(baseline, overlay, outputDir)`）。
プレビューの機能の出力や、サンプルの関数を変えた場合は、手順5で既定のベースラインを出力し直した後に、次で差分のベースラインを作り直す。

```bash
# 手順3の設定ファイルのoutput.pathだけを一時ディレクトリに変えて、--previewを付けて出力する
sed "s|path: .*|path: /tmp/preview-out|" /tmp/verify-postgres.yml > /tmp/verify-postgres-preview.yml
rm -rf /tmp/preview-out
java -jar modules/cli/build/libs/dbxray.jar --config=/tmp/verify-postgres-preview.yml --preview \
  --db-driver=org.postgresql.Driver --db-url=jdbc:postgresql://localhost:15432/testdb \
  --db-username=postgres --db-password=postgres
# 既定のベースラインと（作成日を除いて）内容が異なる・既定に無いファイルだけを、差分のベースラインへ写す
python3 - /tmp/preview-out docs/sample/postgres/output docs/sample/postgres/output-preview <<'PY'
import os, re, shutil, sys
gen, base, overlay = sys.argv[1:]
norm = lambda t: re.sub(r'\|\d{4}/\d{2}/\d{2}\|', '|<date>|', t)
shutil.rmtree(overlay, ignore_errors=True)
for root, _, files in os.walk(gen):
    for name in files:
        rel = os.path.relpath(os.path.join(root, name), gen)
        text = open(os.path.join(gen, rel), encoding='utf-8').read()
        b = os.path.join(base, rel)
        if os.path.isfile(b) and norm(open(b, encoding='utf-8').read()) == norm(text):
            continue
        os.makedirs(os.path.dirname(os.path.join(overlay, rel)), exist_ok=True)
        shutil.copyfile(os.path.join(gen, rel), os.path.join(overlay, rel))
PY
```

差分のベースラインにスナップショット（`snapshot/`）が入った場合は、プレビューの機能がスナップショットを変えている（仕様に反する）ので、出力側を直す。
MCPサーバーの契約テスト（`SampleFunctionTableUsagesContractTest`）も差分のベースラインを読む。Oracleも同じ手順で`docs/sample/oracle/output-preview`を作る。

## Oracleの場合

Oracle用mapper（`mapper/oracle/catalogMapper.xml`）を変えた場合は、まず`./gradlew oracleIntegrationTest`を実行する
（`docs/sample/oracle/ddl.sql`を流し込んだOracle Database Freeに対して、取得結果とベースライン`docs/sample/oracle/output`との一致を確かめる）。
ベースラインを出力し直す場合は、PostgreSQLの手順1・3・4・6を次のように読み替える（手順2・5は同じ）。

```bash
# 1. 起動とDDLの流し込み。イメージは初期化スクリプトをCDBのルートで実行するため、PDBへ切り替えてからDDLを流す
docker rm -f dbxray-verify-oracle 2>/dev/null
printf 'alter session set container = FREEPDB1;\n@/opt/sample/ddl.sql\n' > /tmp/oracle-init.sql
docker run -d --name dbxray-verify-oracle \
  -e ORACLE_PASSWORD=oracle -p 11521:1521 \
  -v "$PWD/docs/sample/oracle/ddl.sql:/opt/sample/ddl.sql:ro" \
  -v /tmp/oracle-init.sql:/container-entrypoint-initdb.d/init.sql:ro \
  gvenzl/oracle-free:23-slim-faststart
# 起動待ち（数十秒）。DDLが失敗するとコンテナが終了するので、その場合はdocker logsでORA-を確認する
for i in $(seq 1 90); do docker logs dbxray-verify-oracle 2>&1 | grep -q 'DATABASE IS READY TO USE' && break; sleep 2; done

# 3. 設定ファイル（Oracleのスキーマ名は大文字。ベースラインはサイドカー無しで出力しているためannotationsは書かない）
cat > /tmp/verify-oracle.yml <<EOF
target:
  schemas: [SAMPLE]
output:
  path: $PWD/docs/sample/oracle/output
EOF

# 4. 実行（リポジトリ直下で）
rm -rf docs/sample/oracle/output
java -jar modules/cli/build/libs/dbxray.jar \
  --config=/tmp/verify-oracle.yml \
  --db-driver=oracle.jdbc.OracleDriver \
  --db-url=jdbc:oracle:thin:@//localhost:11521/FREEPDB1 \
  --db-username=sample --db-password=sample

# 6. 後片付け
docker rm -f dbxray-verify-oracle
```

## 原因調査（`[result]:FAIL` になったら）

まずコンソールの`[errmsg]`（どのSQLで失敗したか。`Failed to select: …selectColumnInfo`等）と
`[cause]`（DBが返したエラー。PSQLExceptionのメッセージ等）を見る。スタックトレースは実行したディレクトリの
`var/log/dbxray.log`（リポジトリ直下で実行した場合は`var/log/`）に記録される
（`FailureReporter`が想定外の失敗をスタックトレース付きでログへ出す）。
実際に組み立てられたSQL文と合わせて調べたい場合は、該当のMyBatisステートメントを直接叩く
使い捨てJavaプログラムを書くのが早い。

```java
// /tmp/repro/Repro.java など、modules/cli/build/libs の jar をクラスパスに使う
SqlSessionFactory factory =
    MyBatisSqlSessionFactories.create(
        ConnectionSettings.of(
            Map.of(
                "driver", "org.postgresql.Driver",
                "url", "jdbc:postgresql://localhost:15432/testdb",
                "username", "postgres",
                "password", "postgres")));
try (SqlSession session = factory.openSession()) {
    session.selectList(
        "com.dbxray.domain.repository.postgresql.CatalogRepository.<問題のid>",
        Map.of("schemaList", List.of("sample"), "tableList", List.of()));
} catch (Exception e) {
    e.printStackTrace(); // Caused by: PSQLException ... と、MyBatisが添えるSQL文を合わせて確認できる
}
```

```bash
cd /tmp/repro
javac -cp <リポジトリルート>/modules/cli/build/libs/dbxray.jar Repro.java
java -cp .:<リポジトリルート>/modules/cli/build/libs/dbxray.jar Repro
```

PSQLExceptionの`Position:`はUTF-8バイトオフセットなので、日本語コメントが混じるSQLでは
Javaの文字インデックスとズレる。位置を厳密に特定したいときは、`MappedStatement#getBoundSql`
で実際に組み立てられたSQL文字列を取得し、UTF-8バイト列に変換してからオフセットを引く。

## 踏み抜いた地雷（同じ轍を踏まないためのメモ）

`catalogMapper.xml`（PostgreSQL/Oracle双方）は長いSQLを可読性のために手動で
折り返しているが、この折り返しが**SQLの意味を変えてしまう**箇所が過去に存在した。
マッパーXMLを編集するときは以下を守ること。

1. **`--`の単一行コメントを複数の物理行にまたがせない。**
   `-- コメント` の直後に改行を入れると、そこでコメントは終わってしまい、次の行の文字列が
   （コメントのつもりでも）生のSQLとして解釈される。日本語コメントが長くなる場合は
   必ず `/* ... */` のブロックコメントを使う（複数行にまたがっても安全）。
   実際にこれが原因で `selectForeignKeyInfo`（PostgreSQL）が
   `syntax error at or near "pg_catalog"` で例外を吐き、外部キーを1つでも持つDBに対して
   **一切テーブル定義書を出力できなくなっていた**（本skill作成時に発見・修正済み）。
2. **文字列リテラル（`'...'`）を複数の物理行にまたがせない。**
   `'PRIMARY\n        KEY'` のように改行を挟むと、値そのものに改行＋インデントの空白が
   混入し、生成されたMarkdownの表セルが壊れる（表の途中で改行されて別の行として見えてしまう）。
   `selectConstraintInfo`の`'PRIMARY KEY'`がこれで壊れていた（修正済み）。
   同様の理由でOracle側マッパーの`'CREATE INDEX '`も修正済み。
3. 上記1点目のパターンで、Oracle側マッパー（`mapper/oracle/catalogMapper.xml`）の
   `selectForeignKeyInfo`にも同種の壊れたコメントが3箇所あり、目視で同様に修正した
   （その後`oracleIntegrationTest`で実DBに対して確かめた）。

4. **resultMapの`<arg javaType>`でプリミティブ型を指定するときは`_int`・`_boolean`のように`_`を付ける。**
   MyBatisの型エイリアスでは`int`は`Integer`、`boolean`は`Boolean`を指すため、DTO（record）の
   コンポーネントが`int`・`boolean`だとコンストラクタが見つからず、`Failed to select: ...`で失敗する
   （関数のオーバーロード番号を追加した際に実際に踏んだ。単体テストはDTOを直接生成するため検知できない）。

5. **OracleのLONG型の列（`ALL_IND_EXPRESSIONS.COLUMN_EXPRESSION`・`ALL_TAB_COLUMNS.DATA_DEFAULT`・`ALL_VIEWS.TEXT`等）には、
   `SUBSTR`等の関数も`LISTAGG`も適用できない**（ORA-00932）。関数索引を持つDBで`selectIndexInfo`がこれで落ちていた。
   `DBMS_XMLGEN`でXMLに書き出す方法は、LONGの値をエスケープしないため`&`や`<`を含むと壊れる（ORA-31011）。
   Oracle用mapperの`longValueFunction`（SQL内で定義するPL/SQLの関数）で読むか、`_VC`の付いたVARCHAR2版の列
   （`ALL_CONSTRAINTS.SEARCH_CONDITION_VC`等）があればそれを使う。

この手順（実DBに対して1回通す）を省略すると、上記のような「単体テストは通るが実行すると
即エラー」という不具合を見逃す。
