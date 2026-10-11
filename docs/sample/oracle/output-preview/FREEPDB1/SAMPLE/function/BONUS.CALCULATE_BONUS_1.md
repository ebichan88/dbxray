# BONUS.CALCULATE_BONUS

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|Oracle 23|FREEPDB1|2026/10/11|

## 利用しているテーブル

定義本体から機械的に抽出した参考値です。呼び出している関数・プロシージャの中の参照と、動的SQLの中の参照は含みません。

出力対象のテーブルへの参照は見つかりませんでした。

## 定義

```sql
CREATE OR REPLACE package bonus as
    function calculate_bonus(p_salary number, p_rate number) return number deterministic;
    function calculate_bonus(p_salary number) return number deterministic;
end bonus;

CREATE OR REPLACE package body bonus as
    function calculate_bonus(p_salary number, p_rate number) return number deterministic is
    begin
        return p_salary * p_rate;
    end;

    function calculate_bonus(p_salary number) return number deterministic is
    begin
        return calculate_bonus(p_salary, 0.1);
    end;
end bonus;
```

___

[関数・プロシージャ一覧へ](../../functionList_FREEPDB1.md)
