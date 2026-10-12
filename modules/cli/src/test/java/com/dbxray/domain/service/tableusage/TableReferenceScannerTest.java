package com.dbxray.domain.service.tableusage;

import static com.dbxray.domain.service.tableusage.ScanCase.both;
import static com.dbxray.domain.service.tableusage.ScanCase.ora;
import static com.dbxray.domain.service.tableusage.ScanCase.pg;
import static org.junit.jupiter.api.Assertions.*;

import com.dbxray.domain.model.database.Dbms;
import com.dbxray.domain.model.tableusage.CrudOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** TableReferenceScanner のテーブルの位置・操作・動的SQLの判定のテスト */
class TableReferenceScannerTest {

  static List<ScanCase> insertCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("INSERT INTO employee VALUES (1, 'a')", "employee:C"));
    cases.addAll(
        both("INSERT INTO sample.employee (id, name) VALUES (1, 'a')", "sample.employee:C"));
    cases.addAll(both("insert into t(a,b) values(1,2)", "t:C"));
    cases.addAll(both("INSERT INTO t (a, b) SELECT a, b FROM u", "t:C, u:R"));
    cases.addAll(both("INSERT INTO t SELECT * FROM t", "t:CR"));
    cases.addAll(
        both(
            "INSERT INTO sample.audit_log (table_name) SELECT 'x' FROM sample.employee e,"
                + " sample.department d WHERE e.id = d.id",
            "sample.audit_log:C, sample.employee:R, sample.department:R"));
    cases.addAll(
        both(
            "INSERT INTO t SELECT * FROM u WHERE NOT EXISTS (SELECT 1 FROM v WHERE v.id = u.id)",
            "t:C, u:R, v:R"));
    cases.addAll(both("INSERT INTO t VALUES (1) RETURNING id INTO v_id", "t:C"));
    cases.addAll(both("INSERT INTO t VALUES ((SELECT max(id) FROM u) + 1)", "t:C, u:R"));
    cases.add(pg("INSERT INTO t DEFAULT VALUES", "t:C"));
    cases.add(pg("INSERT INTO t AS x VALUES (1)", "t:C"));
    cases.add(pg("INSERT INTO t VALUES (1) ON CONFLICT DO NOTHING", "t:C"));
    cases.add(pg("INSERT INTO t VALUES (1) ON CONFLICT (id) DO UPDATE SET a = excluded.a", "t:CU"));
    cases.add(
        pg(
            "INSERT INTO t AS x VALUES (1) ON CONFLICT ON CONSTRAINT t_pkey DO UPDATE SET a = x.a +"
                + " 1 WHERE x.a < (SELECT max(a) FROM u)",
            "t:CU, u:R"));
    cases.add(pg("INSERT INTO t SELECT g FROM generate_series(1, 10) g", "t:C"));
    cases.add(ora("INSERT INTO t x VALUES (1)", "T:C"));
    cases.add(
        ora("INSERT ALL INTO a VALUES (1) INTO b (x) VALUES (2) SELECT * FROM c", "A:C, B:C, C:R"));
    cases.add(
        ora(
            "INSERT FIRST WHEN x > 1 THEN INTO a VALUES (x) ELSE INTO b VALUES (x) SELECT x FROM c",
            "A:C, B:C, C:R"));
    cases.add(ora("INSERT INTO t@remote VALUES (1)", "-"));
    cases.add(ora("INSERT INTO sample.t@remote.example.com VALUES (1)", "-"));
    cases.add(ora("INSERT INTO t VALUES v_rec", "T:C"));
    cases.add(ora("INSERT INTO (SELECT a, b FROM t WHERE a > 0) VALUES (1, 2)", "T:C"));
    return cases;
  }

  static List<ScanCase> updateCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("UPDATE employee SET salary = salary * 1.1 WHERE id = 1", "employee:U"));
    cases.addAll(both("UPDATE sample.employee e SET e.salary = 0", "sample.employee:U"));
    cases.addAll(both("UPDATE t SET a = (SELECT max(b) FROM u WHERE u.id = t.id)", "t:U, u:R"));
    cases.addAll(both("UPDATE t SET a = 1 WHERE id IN (SELECT id FROM u)", "t:U, u:R"));
    cases.addAll(both("UPDATE t SET a = 1, b = 2 WHERE id = 1", "t:U"));
    cases.addAll(both("SELECT a INTO v FROM t WHERE id = 1 FOR UPDATE", "t:R"));
    cases.addAll(both("SELECT a FROM t FOR UPDATE OF t.a NOWAIT", "t:R"));
    cases.addAll(both("UPDATE t SET a = 1 WHERE CURRENT OF c", "t:U"));
    cases.add(pg("UPDATE ONLY t SET a = 1", "t:U"));
    cases.add(pg("UPDATE a SET x = b.x FROM b WHERE a.id = b.id", "a:U, b:R"));
    cases.add(
        pg("UPDATE a SET x = 1 FROM b, c WHERE a.id = b.id AND b.id = c.id", "a:U, b:R, c:R"));
    cases.add(pg("UPDATE a SET x = 1 FROM b JOIN c ON b.id = c.id", "a:U, b:R, c:R"));
    cases.add(pg("SELECT * FROM t FOR NO KEY UPDATE", "t:R"));
    cases.add(pg("SELECT * FROM t FOR UPDATE SKIP LOCKED", "t:R"));
    cases.add(pg("UPDATE t SET (a, b) = (SELECT x, y FROM u) RETURNING a INTO v", "t:U, u:R"));
    cases.add(ora("UPDATE (SELECT a FROM t) SET a = 1", "T:U"));
    cases.add(
        ora(
            "UPDATE (SELECT a.x, b.y FROM a JOIN b ON a.id = b.id WHERE b.z IN (SELECT z FROM c))"
                + " SET x = y",
            "A:U, B:R, C:R"));
    cases.add(ora("UPDATE t SET x = 1 RETURNING id INTO v_id", "T:U"));
    cases.add(ora("UPDATE t SET ROW = v_rec WHERE id = v_rec.id", "T:U"));
    cases.add(
        pg(
            "UPDATE perms SET update = delete FROM roles r WHERE r.id = perms.id",
            "perms:U, roles:R"));
    return cases;
  }

  static List<ScanCase> deleteCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("DELETE FROM employee WHERE id = 1", "employee:D"));
    cases.addAll(both("DELETE FROM sample.employee", "sample.employee:D"));
    cases.addAll(both("DELETE FROM t WHERE id IN (SELECT id FROM u)", "t:D, u:R"));
    cases.addAll(
        both("DELETE FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.id = t.id)", "t:D, u:R"));
    cases.addAll(both("DELETE FROM t WHERE CURRENT OF cur", "t:D"));
    cases.add(pg("DELETE FROM ONLY t", "t:D"));
    cases.add(pg("DELETE FROM t AS x WHERE x.id = 1", "t:D"));
    cases.add(pg("DELETE FROM a USING b WHERE a.id = b.id", "a:D, b:R"));
    cases.add(pg("DELETE FROM a USING b, c WHERE a.id = b.id", "a:D, b:R, c:R"));
    cases.add(pg("DELETE FROM a USING b JOIN c USING (id) WHERE a.id = b.id", "a:D, b:R, c:R"));
    cases.add(pg("DELETE FROM a USING (SELECT id FROM b) s, c WHERE a.id = s.id", "a:D, b:R, c:R"));
    cases.add(pg("DELETE FROM t RETURNING id INTO v", "t:D"));
    cases.add(ora("DELETE FROM (SELECT * FROM t WHERE a = 1)", "T:D"));
    cases.add(ora("DELETE (SELECT * FROM t, u WHERE t.id = u.id)", "T:D, U:R"));
    cases.add(ora("DELETE employee WHERE id = 1", "EMPLOYEE:D"));
    cases.add(ora("DELETE FROM t x WHERE x.id = 1", "T:D"));
    cases.add(ora("DELETE sample.t", "SAMPLE.T:D"));
    cases.add(ora("DELETE FROM t RETURNING id BULK COLLECT INTO v_ids", "T:D"));
    return cases;
  }

  static List<ScanCase> mergeCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(
        both(
            "MERGE INTO a USING b ON (a.id = b.id) WHEN MATCHED THEN UPDATE SET x = b.x WHEN NOT"
                + " MATCHED THEN INSERT (id, x) VALUES (b.id, b.x)",
            "b:R, a:CU"));
    cases.addAll(
        both(
            "MERGE INTO sample.a t USING (SELECT * FROM b WHERE b.flag = 1) s ON (t.id = s.id) WHEN"
                + " MATCHED THEN UPDATE SET t.x = s.x",
            "b:R, sample.a:U"));
    cases.addAll(
        both(
            "MERGE INTO a USING b ON (a.id = b.id) WHEN NOT MATCHED THEN INSERT VALUES (b.id)",
            "b:R, a:C"));
    cases.add(
        pg(
            "MERGE INTO a t USING b s ON t.id = s.id WHEN MATCHED AND s.del THEN DELETE WHEN"
                + " MATCHED THEN UPDATE SET x = s.x WHEN NOT MATCHED THEN INSERT VALUES (s.id)",
            "b:R, a:CUD"));
    cases.add(
        pg(
            "MERGE INTO a USING b ON a.id = b.id WHEN NOT MATCHED THEN INSERT DEFAULT VALUES",
            "b:R, a:C"));
    cases.add(pg("MERGE INTO a USING b ON a.id = b.id WHEN MATCHED THEN DELETE", "b:R, a:D"));
    cases.add(pg("MERGE INTO a USING b ON a.id = b.id WHEN MATCHED THEN DO NOTHING", "b:R"));
    cases.add(
        pg("MERGE INTO a USING b ON a.id = b.id WHEN MATCHED THEN DELETE RETURNING *", "b:R, a:D"));
    cases.add(
        pg(
            "MERGE INTO a USING b ON a.id = b.id WHEN MATCHED THEN UPDATE SET x = 1; INSERT INTO c"
                + " VALUES (1) ON CONFLICT (id) DO UPDATE SET x = 1",
            "b:R, a:U, c:CU"));
    cases.add(
        ora(
            "MERGE INTO a t USING b s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.x = s.x"
                + " DELETE WHERE t.x = 0 WHEN NOT MATCHED THEN INSERT (t.id) VALUES (s.id)",
            "B:R, A:CUD"));
    cases.add(
        ora(
            "MERGE INTO a USING dual ON (a.id = p_id) WHEN NOT MATCHED THEN INSERT (id) VALUES"
                + " (p_id)",
            "DUAL:R, A:C"));
    return cases;
  }

  static List<ScanCase> truncateCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("TRUNCATE TABLE a", "a:D"));
    cases.addAll(both("TRUNCATE TABLE sample.a", "sample.a:D"));
    cases.add(pg("TRUNCATE a, b CASCADE", "a:D, b:D"));
    cases.add(pg("TRUNCATE ONLY a RESTART IDENTITY", "a:D"));
    cases.add(pg("TRUNCATE TABLE ONLY a, ONLY b", "a:D, b:D"));
    cases.add(ora("TRUNCATE TABLE a REUSE STORAGE", "A:D"));
    return cases;
  }

  static List<ScanCase> selectCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("SELECT * FROM a, b, c WHERE a.id = b.id", "a:R, b:R, c:R"));
    cases.addAll(both("SELECT 1 - 2 FROM t", "t:R"));
    cases.addAll(both("SELECT a - -1 FROM t WHERE x = - 1", "t:R"));
    cases.addAll(both("SELECT * FROM a x, b y", "a:R, b:R"));
    cases.addAll(both("SELECT * FROM sample.employee", "sample.employee:R"));
    cases.addAll(both("SELECT * FROM db.sample.employee", "sample.employee:R"));
    cases.addAll(
        both("SELECT * FROM a JOIN b ON a.id = b.id LEFT JOIN c ON b.id = c.id", "a:R, b:R, c:R"));
    cases.addAll(
        both(
            "SELECT * FROM a INNER JOIN b ON a.id = b.id RIGHT OUTER JOIN c ON 1 = 1 FULL JOIN d"
                + " ON 1 = 1 CROSS JOIN e NATURAL JOIN f",
            "a:R, b:R, c:R, d:R, e:R, f:R"));
    cases.addAll(both("SELECT * FROM a JOIN b ON a.id = b.id, c", "a:R, b:R, c:R"));
    cases.addAll(
        both("SELECT * FROM a JOIN b ON a.id = b.id AND b.x IN (1, 2), c", "a:R, b:R, c:R"));
    cases.addAll(both("SELECT * FROM (SELECT * FROM a) x JOIN b ON 1 = 1", "a:R, b:R"));
    cases.addAll(both("SELECT * FROM (SELECT * FROM a) x, b", "a:R, b:R"));
    cases.addAll(both("SELECT * FROM (a JOIN b ON a.id = b.id)", "a:R, b:R"));
    cases.addAll(
        both("SELECT * FROM x JOIN (b JOIN c ON b.id = c.id) ON x.id = b.id", "x:R, b:R, c:R"));
    cases.addAll(both("SELECT * FROM ((a JOIN b ON 1 = 1) JOIN c ON 1 = 1)", "a:R, b:R, c:R"));
    cases.addAll(both("SELECT * FROM x, (a JOIN b ON 1 = 1)", "x:R, a:R, b:R"));
    cases.addAll(both("SELECT * FROM ((SELECT * FROM a) x JOIN b ON 1 = 1)", "a:R, b:R"));
    cases.addAll(
        both(
            "SELECT * FROM x JOIN (WITH w AS (SELECT 1 FROM a) SELECT * FROM w) y ON 1 = 1",
            "x:R, a:R"));
    cases.addAll(both("SELECT (SELECT count(*) FROM a) FROM b", "a:R, b:R"));
    cases.addAll(
        both(
            "SELECT * FROM a WHERE id IN (SELECT id FROM b) AND EXISTS (SELECT 1 FROM c)",
            "a:R, b:R, c:R"));
    cases.addAll(
        both("SELECT a FROM t UNION SELECT a FROM u UNION ALL SELECT a FROM v", "t:R, u:R, v:R"));
    cases.addAll(both("SELECT * FROM a WHERE x = 1 ORDER BY a.x, b", "a:R"));
    cases.addAll(
        both(
            "SELECT * FROM a JOIN b ON CASE WHEN a.x = 1 THEN b.y ELSE b.z END = 1, c",
            "a:R, b:R, c:R"));
    cases.addAll(
        both(
            "SELECT * FROM a JOIN b ON CASE WHEN a.x = 1 THEN CASE WHEN b.y = 2 THEN 1 ELSE 2 END"
                + " ELSE 3 END = 1, c",
            "a:R, b:R, c:R"));
    cases.addAll(both("SELECT * FROM a, start, b", "a:R, start:R, b:R"));
    cases.addAll(both("SELECT * FROM a JOIN b ON a.x = start, c", "a:R, b:R, c:R"));
    cases.addAll(
        both("SELECT * FROM a JOIN b ON limit < b.y AND b.z > offset, c", "a:R, b:R, c:R"));
    cases.add(pg("SELECT * FROM a AS limit, b", "a:R, b:R"));
    cases.addAll(both("SELECT x, y FROM a GROUP BY x, y HAVING count(*) > 1", "a:R"));
    cases.addAll(both("SELECT * FROM a ORDER BY x FETCH FIRST 10 ROWS ONLY", "a:R"));
    cases.addAll(both("SELECT count(*) INTO v FROM t", "t:R"));
    cases.addAll(both("SELECT a, b INTO v1, v2 FROM t WHERE id = 1", "t:R"));
    cases.addAll(both("SELECT t.update, t.delete, t.insert, t.from FROM t", "t:R"));
    cases.addAll(both("SELECT update_date, insert_user, deleted FROM t", "t:R"));
    cases.addAll(both("SELECT t.delete FROM t", "t:R"));
    cases.addAll(
        both(
            "SELECT p.id, p.update, p.delete FROM perms p JOIN roles r ON p.id = r.id",
            "perms:R, roles:R"));
    cases.addAll(both("INSERT INTO log SELECT p.id, p.delete FROM perms p", "log:C, perms:R"));
    cases.addAll(both("SELECT * FROM f(1)", "-"));
    cases.addAll(both("SELECT * FROM sample.f(1) x JOIN t ON 1 = 1", "t:R"));
    cases.addAll(both("SELECT EXTRACT(YEAR FROM hired_date) FROM employee", "employee:R"));
    cases.addAll(both("SELECT TRIM(BOTH ' ' FROM name) FROM t", "t:R"));
    cases.add(pg("SELECT * FROM \"Mixed Case\"", "Mixed Case:R"));
    cases.add(ora("SELECT * FROM \"Mixed Case\"", "Mixed Case:R"));
    cases.add(pg("SELECT * FROM \"sample\".\"T\"", "sample.T:R"));
    cases.add(ora("SELECT * FROM \"sample\".\"T\"", "sample.T:R"));
    cases.addAll(both("SELECT * FROM a WHERE (x, y) IN (SELECT x, y FROM b)", "a:R, b:R"));
    cases.add(pg("SELECT * FROM a AS x, b AS y", "a:R, b:R"));
    cases.add(pg("SELECT * FROM a WHERE x IS DISTINCT FROM y", "a:R"));
    cases.add(pg("SELECT * FROM a WHERE x IS NOT DISTINCT FROM (SELECT y FROM b)", "a:R, b:R"));
    cases.add(pg("SELECT SUBSTRING(name FROM 2 FOR 3) FROM t", "t:R"));
    cases.add(pg("SELECT OVERLAY(name PLACING 'x' FROM 2) FROM t", "t:R"));
    cases.add(pg("SELECT * FROM a LIMIT 10 OFFSET 5", "a:R"));
    cases.add(pg("SELECT * FROM a, LATERAL f(a.id) x", "a:R"));
    cases.add(
        pg(
            "SELECT * FROM a JOIN LATERAL (SELECT * FROM b WHERE b.id = a.id) x ON true",
            "a:R, b:R"));
    cases.add(pg("SELECT * FROM a CROSS JOIN LATERAL f(a.x) y", "a:R"));
    cases.add(pg("SELECT * FROM unnest(arr) u", "-"));
    cases.add(pg("SELECT * FROM generate_series(1, 10) g JOIN t ON t.id = g", "t:R"));
    cases.add(pg("SELECT * FROM employee(1)", "-"));
    cases.add(pg("SELECT * FROM a WHERE x = ANY (SELECT y FROM b)", "a:R, b:R"));
    cases.add(pg("SELECT * FROM (VALUES (1), (2)) v (x), t", "t:R"));
    cases.add(pg("SELECT * FROM U&\"Emp\" JOIN u&\"d\\0061t\" ON 1 = 1", "Emp:R, d\\0061t:R"));
    cases.add(pg("SELECT a::text FROM t WHERE b = $1", "t:R"));
    cases.add(pg("SELECT * FROM t WINDOW w AS (PARTITION BY a), u", "t:R"));
    cases.add(pg("SELECT * FROM ONLY t", "t:R"));
    cases.add(pg("SELECT update FROM perms", "perms:R"));
    cases.add(pg("SELECT delete FROM perms", "perms:R"));
    cases.add(pg("SELECT insert, truncate FROM perms", "perms:R"));
    cases.add(pg("SELECT a, delete FROM perms WHERE update AND delete", "perms:R"));
    cases.add(pg("SELECT * FROM perms ORDER BY update DESC", "perms:R"));
    cases.add(pg("SELECT x AS update, y AS delete FROM perms", "perms:R"));
    cases.add(pg("SELECT * FROM a JOIN b ON a.tags && ARRAY['x', y], c", "a:R, b:R, c:R"));
    cases.add(pg("SELECT * FROM a JOIN b ON a.arr = ARRAY[a.x, a.y], c", "a:R, b:R, c:R"));
    cases.add(ora("SELECT * FROM TABLE(f(1))", "-"));
    cases.add(ora("SELECT * FROM a, TABLE(a.nested) n", "A:R"));
    cases.add(ora("SELECT * FROM a CROSS APPLY (SELECT * FROM b WHERE b.id = a.id)", "A:R, B:R"));
    cases.add(ora("SELECT * FROM a OUTER APPLY TABLE(f(a.id))", "A:R"));
    cases.add(ora("SELECT * FROM a@link", "-"));
    cases.add(ora("SELECT * FROM a@link x, b", "B:R"));
    cases.add(ora("SELECT sysdate FROM dual", "DUAL:R"));
    cases.add(ora("SELECT * FROM a CONNECT BY PRIOR id = parent_id START WITH id = 1", "A:R"));
    cases.add(ora("SELECT * FROM a MINUS SELECT * FROM b", "A:R, B:R"));
    cases.add(ora("SELECT * FROM a PARTITION (p1) x, b", "A:R, B:R"));
    cases.add(ora("SELECT * FROM a WHERE x = :bind", "A:R"));
    cases.add(ora("SELECT * FROM v$session", "V$SESSION:R"));
    cases.add(ora("SELECT * FROM a#b", "A#B:R"));
    cases.add(ora("SELECT * FROM \"sample\".t", "sample.T:R"));
    cases.add(ora("SELECT * FROM a, b WHERE a.id = b.id(+)", "A:R, B:R"));
    cases.add(
        ora("SELECT NTH_VALUE(x, 2) FROM FIRST OVER (ORDER BY y) AS nv, other_col FROM t", "T:R"));
    cases.add(ora("SELECT NTH_VALUE(x, 2) FROM LAST IGNORE NULLS OVER (ORDER BY y) FROM t", "T:R"));
    cases.add(ora("SELECT * FROM first, last", "FIRST:R, LAST:R"));
    cases.add(ora("SELECT * FROM sales PARTITION FOR (1), b", "SALES:R, B:R"));
    return cases;
  }

  static List<ScanCase> commonTableExpressionCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("WITH d AS (SELECT * FROM a) SELECT * FROM d", "a:R"));
    cases.addAll(
        both(
            "WITH d AS (SELECT * FROM a), e AS (SELECT * FROM d) SELECT * FROM e JOIN b ON 1 = 1",
            "a:R, b:R"));
    cases.addAll(both("WITH d (x, y) AS (SELECT x, y FROM a) SELECT * FROM d", "a:R"));
    cases.addAll(
        both(
            "WITH employee AS (SELECT * FROM sample.employee) SELECT * FROM employee",
            "sample.employee:R"));
    cases.addAll(
        both(
            "WITH employee AS (SELECT 1 FROM t) SELECT * FROM sample.employee",
            "t:R, sample.employee:R"));
    cases.addAll(
        both("WITH d AS (SELECT 1 FROM dual) SELECT * FROM d; SELECT * FROM d", "dual:R, d:R"));
    cases.addAll(both("INSERT INTO t WITH d AS (SELECT * FROM a) SELECT * FROM d", "t:C, a:R"));
    cases.add(
        pg(
            "FOR r IN WITH d AS (SELECT * FROM a) SELECT * FROM d LOOP DELETE FROM d; END LOOP",
            "a:R, d:D"));
    cases.add(
        ora(
            "FOR r IN (WITH d AS (SELECT * FROM a) SELECT * FROM d) LOOP DELETE FROM d; END LOOP",
            "A:R, D:D"));
    cases.addAll(both("WITH \"D\" AS (SELECT * FROM a) SELECT * FROM \"D\"", "a:R"));
    cases.addAll(
        both(
            "WITH d (x) AS (SELECT 1 FROM a), e (y, z) AS (SELECT 2 FROM d) SELECT * FROM e",
            "a:R"));
    cases.add(
        pg(
            "WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 10)"
                + " SELECT * FROM r",
            "-"));
    cases.add(
        pg(
            "WITH RECURSIVE r (n) AS (SELECT id FROM a UNION ALL SELECT n FROM r) SELECT * FROM r",
            "a:R"));
    cases.add(pg("WITH d AS MATERIALIZED (SELECT * FROM a) SELECT * FROM d", "a:R"));
    cases.add(pg("WITH d AS NOT MATERIALIZED (SELECT * FROM a) SELECT * FROM d", "a:R"));
    cases.add(
        pg("WITH d AS (DELETE FROM a RETURNING *) INSERT INTO b SELECT * FROM d", "a:D, b:C"));
    cases.add(pg("WITH u AS (UPDATE a SET x = 1 RETURNING id) SELECT * FROM u", "a:U"));
    cases.add(pg("WITH \"D\" AS (SELECT * FROM a) SELECT * FROM d", "a:R, d:R"));
    cases.addAll(both("WITH t AS (SELECT * FROM t WHERE flag) SELECT * FROM t", "t:R"));
    cases.addAll(
        both(
            "WITH employee AS (SELECT * FROM employee WHERE active) SELECT * FROM employee",
            "employee:R"));
    cases.add(pg("WITH r (n) AS (SELECT n FROM r) SELECT * FROM r", "r:R"));
    cases.add(
        ora(
            "WITH r (n) AS (SELECT 1 FROM dual UNION ALL SELECT n + 1 FROM r WHERE n < 10) SELECT *"
                + " FROM r",
            "DUAL:R"));
    cases.add(pg("WITH RECURSIVE a AS (SELECT 1), b AS (SELECT n FROM b) SELECT * FROM b", "-"));
    cases.add(pg("WITH x AS (SELECT 1 FROM a) DELETE FROM x", "a:R, x:D"));
    cases.add(pg("WITH x AS (SELECT id FROM a) UPDATE x SET y = 1", "a:R, x:U"));
    cases.addAll(both("WITH t AS (SELECT 1 FROM a) INSERT INTO t SELECT * FROM t", "a:R, t:C"));
    cases.add(
        pg(
            "WITH s AS (SELECT 1 FROM a) MERGE INTO s USING t ON s.id = t.id WHEN MATCHED THEN"
                + " UPDATE SET x = 1",
            "a:R, t:R, s:U"));
    cases.add(
        pg(
            "IF EXISTS (WITH d AS (SELECT 1 FROM a) SELECT 1 FROM d) THEN PERFORM 1 FROM d; END IF",
            "a:R, d:R"));
    cases.addAll(
        both("SELECT * FROM (WITH d AS (SELECT 1 FROM a) SELECT * FROM d) x, d", "a:R, d:R"));
    return cases;
  }

  static List<ScanCase> proceduralCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(both("FOR r IN (SELECT * FROM t) LOOP NULL; END LOOP", "t:R"));
    cases.addAll(both("IF v = 1 THEN DELETE FROM t; ELSE UPDATE u SET x = 1; END IF", "t:D, u:U"));
    cases.addAll(both("FETCH c INTO r", "-"));
    cases.addAll(both("LOOP FETCH c INTO r; EXIT WHEN c%NOTFOUND; END LOOP", "-"));
    cases.addAll(both("v := 1; SELECT x INTO v FROM t", "t:R"));
    cases.addAll(both("OPEN c FOR SELECT * FROM t", "t:R"));
    cases.addAll(
        both(
            "BEGIN INSERT INTO t VALUES (1); EXCEPTION WHEN OTHERS THEN UPDATE t SET x = 1; END",
            "t:CU"));
    cases.addAll(
        both("<<outer>> FOR i IN 1..10 LOOP INSERT INTO t VALUES (i); END LOOP outer", "t:C"));
    cases.add(pg("PERFORM 1 FROM t WHERE id = p_id", "t:R"));
    cases.add(
        pg(
            "FOR r IN SELECT * FROM t LOOP UPDATE u SET x = r.x WHERE id = r.id; END LOOP",
            "t:R, u:U"));
    cases.add(pg("RETURN QUERY SELECT * FROM t", "t:R"));
    cases.add(pg("c CURSOR FOR SELECT * FROM t; BEGIN OPEN c; END", "t:R"));
    cases.add(pg("FETCH NEXT FROM c INTO r", "-"));
    cases.add(pg("MOVE NEXT FROM c", "-"));
    cases.add(pg("LOOP FETCH FROM c INTO r; EXIT WHEN NOT FOUND; END LOOP", "-"));
    cases.add(pg("<<lbl>> FETCH FROM c INTO r", "-"));
    cases.add(pg("IF v IS DISTINCT FROM w THEN DELETE FROM t; END IF", "t:D"));
    cases.add(pg("RAISE NOTICE 'DELETE FROM %', 'x'", "-"));
    cases.add(pg("v := (SELECT count(*) FROM t)", "t:R"));
    cases.add(pg("CALL p(1)", "-"));
    cases.add(pg("SELECT a INTO STRICT v FROM t", "t:R"));
    cases.add(pg("SELECT a FROM t INTO v, w WHERE id = 1", "t:R"));
    cases.add(pg("GET DIAGNOSTICS n = ROW_COUNT; RETURN n", "-"));
    cases.add(pg("RETURN NEW", "-"));
    cases.add(ora("CURSOR c IS SELECT * FROM t", "T:R"));
    cases.add(ora("FORALL i IN 1..v.COUNT INSERT INTO t VALUES v(i)", "T:C"));
    cases.add(ora("FORALL i IN INDICES OF v DELETE FROM t WHERE id = v(i)", "T:D"));
    cases.add(ora("FORALL i IN 1..n SAVE EXCEPTIONS UPDATE t SET x = v(i) WHERE id = i", "T:U"));
    cases.add(ora("SELECT a BULK COLLECT INTO v FROM t", "T:R"));
    cases.add(ora("FETCH c BULK COLLECT INTO v LIMIT 100", "-"));
    cases.add(ora("DBMS_OUTPUT.PUT_LINE('DELETE FROM t')", "-"));
    cases.add(ora("$IF $$debug $THEN INSERT INTO log_t VALUES (1); $END", "LOG_T:C"));
    cases.add(ora("v := q'[DELETE FROM t WHERE x = 'a']'", "-"));
    cases.add(ora("IF INSERTING THEN INSERT INTO h VALUES (:NEW.id); END IF", "H:C"));
    cases.add(ora("SELECT :NEW.update FROM dual", "DUAL:R"));
    return cases;
  }

  static List<ScanCase> nonDmlKeywordCases() {
    final List<ScanCase> cases = new ArrayList<>();
    // ıとſ（ASCIIの英字ではない）は、i・sに畳み込まれずキーワードにならない
    cases.addAll(both("\u0131nsert into t values (1)", "-"));
    cases.addAll(both("insert \u0131nto t values (1)", "-"));
    cases.addAll(
        both("merge into t u\u017Fing u on (1) when matched then update set a = 1", "t:U"));
    cases.addAll(both("select * from a jo\u0131n b on a.x = b.x", "a:R"));
    cases.addAll(both("GRANT SELECT, INSERT, UPDATE, DELETE ON t TO u", "-"));
    cases.addAll(both("GRANT INSERT ON t TO u", "-"));
    cases.addAll(both("REVOKE DELETE ON t FROM u", "-"));
    cases.addAll(both("GRANT TRUNCATE ON t TO u", "-"));
    cases.addAll(both("GRANT SELECT, TRUNCATE ON a, b TO u", "-"));
    cases.addAll(both("REVOKE TRUNCATE ON a, b FROM u", "-"));
    cases.add(
        pg("CREATE TRIGGER trg BEFORE TRUNCATE ON t FOR EACH STATEMENT EXECUTE FUNCTION f()", "-"));
    cases.add(
        pg(
            "CREATE TRIGGER trg AFTER INSERT OR TRUNCATE ON t FOR EACH STATEMENT EXECUTE FUNCTION f()",
            "-"));
    cases.add(ora("CREATE TRIGGER trg BEFORE TRUNCATE ON SCHEMA BEGIN NULL; END", "-"));
    cases.addAll(
        both(
            "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (x) REFERENCES u (id) ON DELETE CASCADE",
            "-"));
    cases.addAll(both("SELECT * FROM t FOR UPDATE OF t", "t:R"));
    cases.add(
        pg(
            "CREATE TRIGGER trg BEFORE INSERT OR UPDATE OR DELETE ON t FOR EACH ROW EXECUTE"
                + " FUNCTION f()",
            "-"));
    cases.add(
        pg(
            "CREATE TRIGGER trg AFTER UPDATE OF salary ON t FOR EACH ROW EXECUTE PROCEDURE f()",
            "-"));
    cases.add(pg("CREATE POLICY p ON t FOR INSERT TO r WITH CHECK (true)", "-"));
    cases.add(pg("SELECT * FROM t FOR KEY SHARE", "t:R"));
    cases.add(
        pg(
            "ALTER TABLE t ADD FOREIGN KEY (x) REFERENCES u ON UPDATE SET NULL ON DELETE SET NULL",
            "-"));
    cases.add(ora("CREATE TRIGGER trg INSTEAD OF INSERT ON v FOR EACH ROW BEGIN NULL; END", "-"));
    cases.add(pg("REVOKE GRANT OPTION FOR EXECUTE ON FUNCTION f() FROM u", "-"));
    cases.add(pg("ALTER DEFAULT PRIVILEGES FOR ROLE x REVOKE ALL ON TABLES FROM y", "-"));
    cases.add(pg("SELECT revoke FROM t", "t:R"));
    return cases;
  }

  static List<ScanCase> statementBoundaryCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.addAll(
        both(
            "MERGE INTO a USING b ON (a.id = b.id) WHEN MATCHED THEN UPDATE SET x = 1; DELETE"
                + " FROM c",
            "b:R, a:U, c:D"));
    cases.addAll(both("SELECT * FROM (SELECT * FROM a; SELECT * FROM b, c", "a:R, b:R, c:R"));
    cases.addAll(both("SELECT 1); DELETE FROM a WHERE x IN (1, 2)", "a:D"));
    cases.addAll(both("SELECT * FROM a; v := 1, b", "a:R"));
    cases.addAll(both("DELETE FROM a WHERE x = 1; DELETE WHERE x = 2", "a:D"));
    cases.addAll(both("", "-"));
    cases.addAll(both(";;;", "-"));
    cases.addAll(both("INSERT INTO", "-"));
    cases.addAll(both("INSERT", "-"));
    cases.addAll(both("UPDATE", "-"));
    cases.addAll(both("DELETE", "-"));
    cases.addAll(both("DELETE FROM", "-"));
    cases.addAll(both("MERGE INTO", "-"));
    cases.addAll(both("SELECT * FROM", "-"));
    cases.addAll(both("TRUNCATE", "-"));
    cases.addAll(both("SELECT * FROM a,", "a:R"));
    cases.addAll(both("SELECT * FROM sample.", "sample:R"));
    cases.addAll(both("SELECT * FROM (", "-"));
    cases.addAll(both("SELECT * FROM a JOIN", "a:R"));
    cases.addAll(both("WITH d AS", "-"));
    cases.addAll(both("WITH d (x", "-"));
    cases.addAll(both("WITH d (x) AS NOT MATERIALIZED", "-"));
    cases.addAll(both(")))) SELECT * FROM a", "a:R"));
    return cases;
  }

  static List<ScanCase> dynamicSqlCases() {
    final List<ScanCase> cases = new ArrayList<>();
    cases.add(pg("EXECUTE 'DELETE FROM t'", "[dyn EXECUTE]"));
    cases.add(pg("EXECUTE format('DELETE FROM %I', v_table)", "[dyn EXECUTE]"));
    cases.add(pg("RETURN QUERY EXECUTE 'SELECT * FROM t'", "[dyn EXECUTE]"));
    cases.add(pg("FOR r IN EXECUTE v_sql LOOP NULL; END LOOP", "[dyn EXECUTE]"));
    cases.add(pg("OPEN c FOR EXECUTE v_sql", "[dyn EXECUTE]"));
    cases.add(pg("EXECUTE $q$DELETE FROM t$q$ USING v", "[dyn EXECUTE]"));
    cases.add(pg("PERFORM format('DELETE FROM %I', v)", "-"));
    cases.add(pg("SELECT * FROM dblink('conn', 'SELECT * FROM t') AS x(a int)", "[dyn dblink]"));
    cases.add(pg("PERFORM public.dblink_exec('conn', 'DELETE FROM t')", "[dyn dblink]"));
    cases.add(pg("PERFORM dblink_send_query('conn', 'DELETE FROM t')", "[dyn dblink]"));
    cases.add(pg("PERFORM dblink_open('conn', 'cur', 'SELECT * FROM t')", "[dyn dblink]"));
    cases.add(pg("PERFORM dblink_connect('conn')", "-"));
    cases.add(pg("SELECT execute FROM t", "t:R"));
    cases.add(pg("-- EXECUTE 'x'\nSELECT 1", "-"));
    cases.add(pg("RAISE NOTICE 'EXECUTE x'", "-"));
    cases.add(pg("CREATE TRIGGER trg AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION f()", "-"));
    cases.add(pg("GRANT EXECUTE ON FUNCTION f(integer) TO u", "-"));
    cases.add(pg("GRANT SELECT, EXECUTE ON ALL FUNCTIONS IN SCHEMA s TO u", "-"));
    cases.add(pg("REVOKE EXECUTE ON PROCEDURE p() FROM PUBLIC", "-"));
    cases.add(
        pg(
            "EXECUTE 'x'; PERFORM dblink('c', 'y'); EXECUTE 'z'; INSERT INTO t VALUES (1)",
            "t:C [dyn EXECUTE,dblink]"));
    cases.add(ora("EXECUTE IMMEDIATE 'DELETE FROM t'", "[dyn EXECUTE IMMEDIATE]"));
    cases.add(ora("EXECUTE IMMEDIATE v_sql INTO v USING p", "[dyn EXECUTE IMMEDIATE]"));
    cases.add(ora("EXECUTE IMMEDIATE q'[DELETE FROM t WHERE x = 'a']'", "[dyn EXECUTE IMMEDIATE]"));
    cases.add(
        ora(
            "c := DBMS_SQL.OPEN_CURSOR; DBMS_SQL.PARSE(c, v_sql, DBMS_SQL.NATIVE)",
            "[dyn DBMS_SQL]"));
    cases.add(ora("c := SYS.DBMS_SQL.OPEN_CURSOR", "[dyn DBMS_SQL]"));
    cases.add(ora("OPEN c FOR v_sql", "[dyn OPEN FOR]"));
    cases.add(ora("OPEN c FOR 'SELECT * FROM t'", "[dyn OPEN FOR]"));
    cases.add(ora("OPEN c FOR v_sql USING p", "[dyn OPEN FOR]"));
    cases.add(ora("OPEN :c FOR v_sql", "[dyn OPEN FOR]"));
    cases.add(ora("OPEN pkg.c FOR v_sql", "[dyn OPEN FOR]"));
    cases.add(ora("OPEN c FOR :sql", "[dyn OPEN FOR]"));
    cases.add(ora("OPEN c FOR WITH d AS (SELECT 1 FROM dual) SELECT * FROM d", "DUAL:R"));
    cases.add(ora("OPEN c FOR (SELECT * FROM t)", "T:R"));
    cases.add(ora("OPEN c", "-"));
    cases.add(ora("OPEN c(1)", "-"));
    cases.add(ora("OPEN c FOR", "-"));
    cases.add(ora("OPEN 'x' FOR v", "-"));
    cases.add(ora("OPEN c IN v", "-"));
    cases.add(ora("-- EXECUTE IMMEDIATE 'x'\nNULL", "-"));
    cases.add(ora("DBMS_OUTPUT.PUT_LINE('EXECUTE IMMEDIATE')", "-"));
    cases.add(ora("EXECUTE v", "-"));
    cases.add(ora("x := dblink(1)", "-"));
    cases.add(pg("x := DBMS_SQL.f(1)", "-"));
    return cases;
  }

  /** 全ケース */
  static Stream<ScanCase> allCases() {
    return Stream.of(
            insertCases(),
            updateCases(),
            deleteCases(),
            mergeCases(),
            truncateCases(),
            selectCases(),
            commonTableExpressionCases(),
            proceduralCases(),
            nonDmlKeywordCases(),
            statementBoundaryCases(),
            dynamicSqlCases())
        .flatMap(List::stream);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("insertCases")
  @DisplayName("INSERT: INTO・ALL/FIRSTの対象（副問い合わせの対象は最初のFROMのテーブル）をC、列名の並びの前の名前もテーブル、DBリンクは対象外")
  void testInsert(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("updateCases")
  @DisplayName("UPDATE: 対象（副問い合わせの対象は最初のFROMのテーブル）をU、FROM・副問い合わせはR、FOR UPDATEの行ロックはUにしない")
  void testUpdate(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("deleteCases")
  @DisplayName("DELETE: FROM（OracleはFROM無しも）の対象（副問い合わせの対象は最初のFROMのテーブル）をD、USINGの並びはR")
  void testDelete(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mergeCases")
  @DisplayName("MERGE: 対象の操作はWHEN句から付け、USINGはR。対象は次の文へ持ち越さない")
  void testMerge(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("truncateCases")
  @DisplayName("TRUNCATE: 並びのすべてをD")
  void testTruncate(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("selectCases")
  @DisplayName("SELECT: FROMの並び・JOIN（括弧で囲んだJOINを含む）・APPLYをR、関数呼び出し・DBリンク・FROMの別の用法は対象外")
  void testSelect(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("commonTableExpressionCases")
  @DisplayName("CTE: 同じ文の中のスキーマ修飾の無い同名の参照はテーブルとしない（FOR … LOOPの問い合わせのCTEはループの中に持ち越さない）")
  void testCommonTableExpression(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("proceduralCases")
  @DisplayName("手続き: 変数へのINTO・カーソルのFETCH・文字列の中を読まず、ループ・分岐の中のDMLを拾う")
  void testProcedural(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonDmlKeywordCases")
  @DisplayName("トリガー・権限・外部キーの構文のINSERT・UPDATE・DELETEはDMLとしない")
  void testNonDmlKeyword(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("statementBoundaryCases")
  @DisplayName("文の区切り: ;で状態を戻し、途中で終わる文・括弧の数が合わない文でも例外にせず次の文を読む")
  void testStatementBoundary(ScanCase scanCase) {
    assertScan(scanCase);
  }

  static Stream<String> listTerminators() {
    return TableReferenceScanner.LIST_TERMINATORS.stream().sorted();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("listTerminators")
  @DisplayName("FROMの並び: 同じ括弧の深さで句・区切りの語が現れたら、その後ろのカンマ区切りをテーブルとして読まない")
  void testListTerminators(String word) {
    final String sql = "SELECT * FROM a, b " + word + " x, c";

    assertEquals("a:R, b:R", ScanCase.describe(ScanCase.scan(sql, Dbms.POSTGRESQL)));
    assertEquals("A:R, B:R", ScanCase.describe(ScanCase.scan(sql, Dbms.ORACLE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dynamicSqlCases")
  @DisplayName("動的SQL: DBごとの構文を最初に現れた順に1回ずつ拾い、format(だけ・コメント・文字列の中・権限の名前のEXECUTEは数えない")
  void testDynamicSql(ScanCase scanCase) {
    assertScan(scanCase);
  }

  @Test
  @DisplayName("scan: 拾った名前は畳み込み、修飾の有無・操作を初めて現れた順に返す（操作が違えば同じ名前も別に返す）")
  void testReferencesInOrder() {
    final ScanResult result =
        ScanCase.scan(
            "INSERT INTO Sample.\"Audit\" SELECT * FROM EMPLOYEE; DELETE FROM employee",
            Dbms.POSTGRESQL);

    assertEquals(
        List.of(
            new TableReference("sample", "Audit", CrudOperation.CREATE),
            new TableReference("", "employee", CrudOperation.READ),
            new TableReference("", "employee", CrudOperation.DELETE)),
        result.references());
  }

  @Test
  @DisplayName("scan: 同じスキーマ・テーブル・操作の組は何度書いても1つにまとめる")
  void testDuplicateReferencesCollapse() {
    final ScanResult result =
        ScanCase.scan(
            "SELECT * FROM a, a, A JOIN a ON 1 = 1; INSERT INTO a SELECT * FROM a",
            Dbms.POSTGRESQL);

    assertEquals(
        List.of(
            new TableReference("", "a", CrudOperation.READ),
            new TableReference("", "a", CrudOperation.CREATE)),
        result.references());
  }

  @Test
  @DisplayName("both: Oracle側の期待値は、動的SQLの種類の名前を大文字にしない")
  void testBothKeepsDynamicSqlLabel() {
    final List<ScanCase> cases = both("EXECUTE 'x'", "t:C [dyn EXECUTE]");

    assertEquals("t:C [dyn EXECUTE]", cases.get(0).expected());
    assertEquals("T:C [dyn EXECUTE]", cases.get(1).expected());
  }

  private static void assertScan(ScanCase scanCase) {
    assertEquals(
        scanCase.expected(), ScanCase.describe(ScanCase.scan(scanCase.sql(), scanCase.dbms())));
  }
}
