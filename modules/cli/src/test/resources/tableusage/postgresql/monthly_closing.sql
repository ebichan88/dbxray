CREATE OR REPLACE PROCEDURE sales.monthly_closing(IN p_target_month date)
 LANGUAGE plpgsql
 SET search_path TO 'sales', 'public'
AS $procedure$
DECLARE
    -- 対象月の範囲
    v_from      DATE := date_trunc('month', p_target_month);
    v_to        DATE := (date_trunc('month', p_target_month) + INTERVAL '1 month');
    v_count     INTEGER := 0;
    r_customer  RECORD;
    r_order     RECORD;
    c_orders CURSOR (p_customer_id INTEGER) FOR
        SELECT o.order_id, o.amount
          FROM Orders o                       -- 修飾なし（search_pathでsalesに決まる）
         WHERE o.customer_id = p_customer_id
           AND o.ordered_at >= v_from AND o.ordered_at < v_to;
BEGIN
    /*
     * 2019年の仕様変更前の処理（残しておく）
     * DELETE FROM sales.monthly_summary;
     * INSERT INTO sales.monthly_summary SELECT * FROM sales.old_summary;
     /* 入れ子のコメント: UPDATE sales.customers SET x = 1; */
     */
    RAISE NOTICE '締め処理を開始します: %（DELETE FROM sales.orders はしない）', v_from;

    -- 同じ月の集計を作り直す
    DELETE
      FROM sales.monthly_summary
     WHERE target_month = v_from;

    FOR r_customer IN
        SELECT c.customer_id, c.customer_name
          FROM sales.customers c
         WHERE c.is_active
         ORDER BY c.customer_id
    LOOP
        FOR r_order IN c_orders(r_customer.customer_id) LOOP
            UPDATE sales.Orders
               SET closed = TRUE,
                   closed_at = now()
             WHERE order_id = r_order.order_id;
            v_count := v_count + 1;
        END LOOP;

        INSERT INTO sales.monthly_summary (target_month, customer_id, total_amount, item_count)
        SELECT v_from, r_customer.customer_id, COALESCE(SUM(oi.quantity * p.unit_price), 0), COUNT(*)
          FROM sales.order_items oi
          JOIN sales.products p ON p.product_id = oi.product_id
         WHERE oi.order_id IN (SELECT o.order_id FROM orders o WHERE o.customer_id = r_customer.customer_id);
    END LOOP;

    -- 古い受注をアーカイブへ移す
    WITH moved AS (
        DELETE FROM sales.orders
         WHERE ordered_at < v_from - INTERVAL '2 years'
        RETURNING *
    )
    INSERT INTO archive.orders_archive
    SELECT * FROM moved;

    --UPDATE sales.customers SET last_closed = v_from;   -- 廃止
    INSERT INTO sales.closing_log (target_month, processed, message)
    VALUES (v_from, v_count, E'完了 \'' || v_from || E'\' -- 件数');

    IF v_count = 0 THEN
        RAISE WARNING $msg$対象の受注がありません。UPDATE sales.orders は行いません$msg$;
    END IF;

    EXECUTE format('ANALYZE %I.%I', 'sales', 'monthly_summary');
EXCEPTION
    WHEN OTHERS THEN
        INSERT INTO sales.closing_log (target_month, processed, message)
        VALUES (v_from, -1, SQLERRM);
        RAISE;
END;
$procedure$
