CREATE TABLE migration_issue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  migration_version VARCHAR(20) NOT NULL,
  issue_type VARCHAR(80) NOT NULL,
  entity_type VARCHAR(40) NOT NULL,
  entity_id BIGINT NOT NULL,
  detail VARCHAR(500) NOT NULL,
  resolved BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_migration_issue UNIQUE (migration_version,issue_type,entity_type,entity_id)
);

ALTER TABLE product ADD COLUMN origin_description VARCHAR(240);

ALTER TABLE origin ADD COLUMN province_code CHAR(6);
ALTER TABLE origin ADD COLUMN city_code CHAR(6);
ALTER TABLE origin ADD COLUMN district_code CHAR(6);
ALTER TABLE origin ADD COLUMN district VARCHAR(80);
ALTER TABLE origin ADD COLUMN contact_name VARCHAR(80);
ALTER TABLE origin ADD COLUMN contact_phone VARCHAR(40);
ALTER TABLE origin ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE origin ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE origin ADD COLUMN legacy_source_origin_id BIGINT;
ALTER TABLE origin ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE origin ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

UPDATE origin
SET contact_name=(SELECT s.contact_name FROM supplier s WHERE s.id=origin.supplier_id),
    contact_phone=(SELECT s.contact_phone FROM supplier s WHERE s.id=origin.supplier_id);

INSERT INTO migration_issue(migration_version,issue_type,entity_type,entity_id,detail)
SELECT 'V8','INCOMPLETE_SHIPPING_ORIGIN','origin',o.id,
       '旧发货地缺少可用的省/市/详细地址，已停用；补全真实信息后再启用'
FROM origin o
WHERE TRIM(COALESCE(o.province,''))='' OR TRIM(COALESCE(o.city,''))='' OR TRIM(COALESCE(o.address,''))='';

UPDATE origin
SET enabled=FALSE
WHERE TRIM(COALESCE(province,''))='' OR TRIM(COALESCE(city,''))='' OR TRIM(COALESCE(address,''))='';

INSERT INTO migration_issue(migration_version,issue_type,entity_type,entity_id,detail)
SELECT 'V8','MISSING_SHIPPING_TEMPLATE','sku_supply',x.id,
       '旧供货关系未绑定运费模板；未自动猜测，请人工选择模板后再作为新订单来源'
FROM sku_supply x WHERE x.shipping_template_id IS NULL;

INSERT INTO migration_issue(migration_version,issue_type,entity_type,entity_id,detail)
SELECT 'V8','CROSS_SUPPLIER_ORIGIN','sku_supply',x.id,
       CONCAT('旧供货关系 supplier_id=',x.supplier_id,' 引用了供应商 ',o.supplier_id,
              ' 的发货地 ',o.id,'；已复制为供应商独立记录并保留 supply_id')
FROM sku_supply x JOIN origin o ON o.id=x.origin_id
WHERE x.supplier_id<>o.supplier_id;

INSERT INTO origin(supplier_id,label,province,city,address,province_code,city_code,district_code,district,
                   contact_name,contact_phone,is_default,enabled,legacy_source_origin_id)
SELECT DISTINCT x.supplier_id,CONCAT(o.label,'（迁移副本）'),o.province,o.city,o.address,
       o.province_code,o.city_code,o.district_code,o.district,s.contact_name,s.contact_phone,FALSE,o.enabled,o.id
FROM sku_supply x
JOIN origin o ON o.id=x.origin_id
JOIN supplier s ON s.id=x.supplier_id
WHERE x.supplier_id<>o.supplier_id;

UPDATE sku_supply
SET origin_id=(
  SELECT MIN(new_o.id) FROM origin old_o
  JOIN origin new_o ON new_o.legacy_source_origin_id=old_o.id AND new_o.supplier_id=sku_supply.supplier_id
  WHERE old_o.id=sku_supply.origin_id AND old_o.supplier_id<>sku_supply.supplier_id
)
WHERE EXISTS(
  SELECT 1 FROM origin old_o WHERE old_o.id=sku_supply.origin_id AND old_o.supplier_id<>sku_supply.supplier_id
);

INSERT INTO migration_issue(migration_version,issue_type,entity_type,entity_id,detail)
SELECT 'V8','MULTIPLE_DEFAULT_SUPPLIES','sku',x.sku_id,
       CONCAT('旧数据存在多个默认供货来源；保留 ID 最大的记录 ',MAX(x.id),' 为默认')
FROM sku_supply x WHERE x.is_default=TRUE GROUP BY x.sku_id HAVING COUNT(*)>1;

UPDATE sku_supply
SET is_default=FALSE
WHERE is_default=TRUE AND id NOT IN (
  SELECT keep_id FROM (SELECT MAX(id) keep_id FROM sku_supply WHERE is_default=TRUE GROUP BY sku_id) defaults_to_keep
);

ALTER TABLE origin ADD COLUMN default_supplier_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN supplier_id ELSE NULL END);
ALTER TABLE origin ADD CONSTRAINT uq_origin_id_supplier UNIQUE (id,supplier_id);
ALTER TABLE origin ADD CONSTRAINT uq_origin_default_supplier UNIQUE (default_supplier_key);
ALTER TABLE origin ADD CONSTRAINT ck_origin_default_enabled CHECK (is_default=FALSE OR enabled=TRUE);

ALTER TABLE sku_supply ADD COLUMN default_sku_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN sku_id ELSE NULL END);
ALTER TABLE sku_supply ADD CONSTRAINT uq_supply_default_sku UNIQUE (default_sku_key);
ALTER TABLE sku_supply ADD CONSTRAINT fk_supply_origin_supplier FOREIGN KEY (origin_id,supplier_id) REFERENCES origin(id,supplier_id);

ALTER TABLE order_group ADD COLUMN origin_province_code CHAR(6);
ALTER TABLE order_group ADD COLUMN origin_city_code CHAR(6);
ALTER TABLE order_group ADD COLUMN origin_district_code CHAR(6);
ALTER TABLE order_group ADD COLUMN origin_district VARCHAR(80);
ALTER TABLE order_group ADD COLUMN origin_contact_name VARCHAR(80);
ALTER TABLE order_group ADD COLUMN origin_contact_phone VARCHAR(40);

-- These attributes were not captured when historical orders were placed.  Do not
-- populate them from today's origin record: doing so would turn current master data
-- into a false order-time snapshot.  Keep them NULL and make the provenance gap
-- explicit for migration review.
INSERT INTO migration_issue(migration_version,issue_type,entity_type,entity_id,detail)
SELECT 'V8','HISTORICAL_ORIGIN_DETAIL_UNAVAILABLE','order_group',g.id,
       '历史订单未采集发货地区县编码及联系人；新增快照字段保持空值，未使用当前发货地信息倒填'
FROM order_group g;
