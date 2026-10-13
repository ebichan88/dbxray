# 観点：物流（DB名：testdb）

## 基本情報

| RDBMS | データベース名 | 作成日 |
|:---|:---|:---|
|PostgreSQL 16|testdb|2026/10/08|

## ER図

```mermaid
erDiagram
    sample_shipment["shipment"]
    sample_warehouse_zone["warehouse_zone"]
    sample_warehouse_zone ||--o{ sample_shipment : "shipment_warehouse_code_zone_code_fkey"
    sample_shipment {
        character_varying warehouse_code FK
        character_varying zone_code FK
    }
    sample_warehouse_zone {
        character_varying warehouse_code PK
        character_varying zone_code PK
    }
```

## 所属テーブル

| No. | スキーマ名 | 物理テーブル名 | 論理テーブル名 | 区分 | Link |
|:---|:---|:---|:---|:---|:---|
|1|sample|shipment||table|[■](./sample/table/shipment.md)|
|2|sample|warehouse_zone||table|[■](./sample/table/warehouse_zone.md)|

___

[観点一覧へ](./viewpointList_testdb.md) / [テーブル一覧へ](./tableList_testdb.md)
