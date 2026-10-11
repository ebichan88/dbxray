CREATE OR REPLACE PACKAGE billing_pkg AUTHID DEFINER AS
  /* 請求のパッケージ（仕様部） */
  PROCEDURE log_message(p_msg VARCHAR2);
  FUNCTION get_tax_rate(p_date DATE) RETURN NUMBER;
  FUNCTION create_invoice(p_customer_id NUMBER) RETURN NUMBER;
  PROCEDURE add_line(p_invoice_id NUMBER, p_amount NUMBER);
  PROCEDURE add_line(p_invoice_id NUMBER, p_product VARCHAR2, p_qty NUMBER);
  PROCEDURE apply_payment(p_invoice_id NUMBER, p_amount NUMBER);
  PROCEDURE purge_old(p_before DATE);
  PROCEDURE rebuild_balances;
  FUNCTION open_report(p_sql VARCHAR2) RETURN SYS_REFCURSOR;
  PROCEDURE close_month(p_month DATE);
  FUNCTION employee_name(p_id NUMBER) RETURN VARCHAR2;
END billing_pkg;

CREATE OR REPLACE PACKAGE BODY billing_pkg AS

  g_initialized BOOLEAN := FALSE;

  -- パッケージのカーソル（どのサブプログラムにも含めない）
  CURSOR c_open_invoices IS
    SELECT * FROM invoices WHERE status = 'OPEN';

  PROCEDURE assert_open(p_invoice_id NUMBER);  -- 前方宣言

  ------------------------------------------------------------------
  -- ログ（自律型トランザクション）
  ------------------------------------------------------------------
  PROCEDURE log_message(p_msg VARCHAR2) IS
    PRAGMA AUTONOMOUS_TRANSACTION;
  BEGIN
    INSERT INTO billing_log (logged_at, message) VALUES (SYSTIMESTAMP, p_msg);
    COMMIT;
  END log_message;

  FUNCTION get_tax_rate(p_date DATE) RETURN NUMBER IS
    v_rate NUMBER;
  BEGIN
    SELECT rate
      INTO v_rate
      FROM tax_rates
     WHERE p_date BETWEEN valid_from AND NVL(valid_to, DATE '9999-12-31');
    RETURN v_rate;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN 0.1;
  END get_tax_rate;

  FUNCTION create_invoice(p_customer_id NUMBER) RETURN NUMBER IS
    v_id   invoices.invoice_id%TYPE;
    v_name customers.customer_name%TYPE;
  BEGIN
    SELECT customer_name INTO v_name FROM Customers WHERE customer_id = p_customer_id;
    insert into INVOICES (invoice_id, customer_id, status, total)
    values (invoice_seq.NEXTVAL, p_customer_id, 'OPEN', 0)
    returning invoice_id into v_id;
    RETURN v_id;
  END create_invoice;

  PROCEDURE add_line(p_invoice_id NUMBER, p_amount NUMBER) IS
  BEGIN
    assert_open(p_invoice_id);
    INSERT INTO invoice_lines (invoice_id, amount) VALUES (p_invoice_id, p_amount);
    UPDATE invoices
       SET total = total + p_amount * (1 + get_tax_rate(SYSDATE))
     WHERE invoice_id = p_invoice_id;
  END add_line;

  PROCEDURE add_line(p_invoice_id NUMBER, p_product VARCHAR2, p_qty NUMBER) IS
  BEGIN
    MERGE INTO invoice_lines l
    USING (SELECT p_invoice_id AS invoice_id, product_code, unit_price
             FROM products@master_db
            WHERE product_code = p_product) s
       ON (l.invoice_id = s.invoice_id AND l.product_code = s.product_code)
     WHEN MATCHED THEN
       UPDATE SET l.quantity = l.quantity + p_qty
     WHEN NOT MATCHED THEN
       INSERT (invoice_id, product_code, quantity, amount)
       VALUES (s.invoice_id, s.product_code, p_qty, s.unit_price * p_qty);
  END add_line;

  PROCEDURE apply_payment(p_invoice_id NUMBER, p_amount NUMBER) IS
  BEGIN
    UPDATE invoices SET paid = paid + p_amount WHERE invoice_id = p_invoice_id;
    INSERT INTO payments (invoice_id, amount, paid_at) VALUES (p_invoice_id, p_amount, SYSDATE);
    MERGE INTO customer_balances b
    USING (SELECT customer_id FROM invoices WHERE invoice_id = p_invoice_id) s
       ON (b.customer_id = s.customer_id)
     WHEN MATCHED THEN UPDATE SET b.balance = b.balance - p_amount
     WHEN NOT MATCHED THEN INSERT (customer_id, balance) VALUES (s.customer_id, -p_amount);
  EXCEPTION
    WHEN OTHERS THEN
      INSERT INTO payment_errors (invoice_id, message) VALUES (p_invoice_id, SQLERRM);
      RAISE;
  END apply_payment;

  PROCEDURE purge_old(p_before DATE) IS
  BEGIN
    -- TRUNCATE TABLE invoices;  -- 昔は全件消していた
    DELETE invoice_lines
     WHERE invoice_id IN (SELECT invoice_id FROM invoices WHERE issued_at < p_before);
    DELETE FROM invoices WHERE issued_at < p_before;
    log_message(q'[purged: DELETE FROM invoices WHERE issued_at < ']' || p_before || '''');
  END purge_old;

  PROCEDURE rebuild_balances IS
  BEGIN
    EXECUTE IMMEDIATE 'TRUNCATE TABLE customer_balances';
    INSERT /*+ APPEND */ INTO customer_balances (customer_id, balance)
    SELECT customer_id, SUM(total - paid) FROM invoices GROUP BY customer_id;
  END rebuild_balances;

  FUNCTION open_report(p_sql VARCHAR2) RETURN SYS_REFCURSOR IS
    c SYS_REFCURSOR;
  BEGIN
    OPEN c FOR p_sql;
    RETURN c;
  END open_report;

  PROCEDURE close_month(p_month DATE) IS
    v_count PLS_INTEGER := 0;

    PROCEDURE mark_closed(p_invoice_id NUMBER) IS
    BEGIN
      UPDATE invoices SET status = 'CLOSED' WHERE invoice_id = p_invoice_id;
    END mark_closed;
  BEGIN
    FOR r IN (
      SELECT i.invoice_id, c.customer_name, i.total - i.paid AS remaining
        FROM customers c
        JOIN invoices i ON i.customer_id = c.customer_id
       WHERE TRUNC(i.issued_at, 'MM') = TRUNC(p_month, 'MM')
    ) LOOP
      CASE
        WHEN r.remaining = 0 THEN
          mark_closed(r.invoice_id);
          v_count := v_count + 1;
        ELSE
          log_message('未収: ' || r.customer_name);
      END CASE;
    END LOOP;
    IF v_count > 0 THEN
      log_message(v_count || ' invoices closed');
    END IF;
  END close_month;

  FUNCTION employee_name(p_id NUMBER) RETURN VARCHAR2 IS
    v_name VARCHAR2(100);
  BEGIN
    SELECT last_name INTO v_name FROM hr.employees WHERE employee_id = p_id;
    RETURN v_name;
  END employee_name;

  PROCEDURE assert_open(p_invoice_id NUMBER) IS
    v_status invoices.status%TYPE;
  BEGIN
    SELECT status INTO v_status FROM invoices WHERE invoice_id = p_invoice_id FOR UPDATE;
    IF v_status <> 'OPEN' THEN
      RAISE_APPLICATION_ERROR(-20001, 'invoice is not open');
    END IF;
  END assert_open;

BEGIN
  g_initialized := TRUE;
END billing_pkg;
