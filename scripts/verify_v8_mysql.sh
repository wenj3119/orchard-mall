#!/usr/bin/env bash
set -euo pipefail

container_name="${1:?usage: verify_v8_mysql.sh <mysql-container> <root-password>}"
root_password="${2:?usage: verify_v8_mysql.sh <mysql-container> <root-password>}"
empty_db="orchard_v8_empty_verify"
legacy_db="orchard_v8_legacy_verify"
migration_dir="$(cd "$(dirname "$0")/../backend/src/main/resources/db/migration" && pwd)"

mysql_exec() {
  docker exec -i -e MYSQL_PWD="$root_password" "$container_name" mysql -uroot "$@"
}

cleanup() {
  mysql_exec -e "DROP DATABASE IF EXISTS ${empty_db}; DROP DATABASE IF EXISTS ${legacy_db};" >/dev/null
}
trap cleanup EXIT

mysql_exec -e "DROP DATABASE IF EXISTS ${empty_db}; DROP DATABASE IF EXISTS ${legacy_db}; CREATE DATABASE ${empty_db}; CREATE DATABASE ${legacy_db};"
echo "stage: databases created"

for file in "$migration_dir"/V*.sql; do
  mysql_exec "$empty_db" < "$file"
done
echo "stage: empty database migrated"
mysql_exec "$empty_db" -e "SELECT 1 FROM migration_issue LIMIT 0" >/dev/null

for version in 1 2 3 4 5 6 7; do
  file="$(find "$migration_dir" -maxdepth 1 -name "V${version}__*.sql" -print -quit)"
  echo "stage: applying legacy V${version}"
  mysql_exec "$legacy_db" < "$file"
done
echo "stage: legacy schema migrated through V7"

mysql_exec "$legacy_db" <<'SQL'
INSERT INTO supplier(id,name,contact_name,contact_phone,source_type,enabled) VALUES
  (1,'旧供应商甲','甲联系人','13800000001','FARMER',TRUE),
  (2,'旧供应商乙','乙联系人','13800000002','FACTORY',TRUE);
INSERT INTO origin(id,supplier_id,label,province,city,address) VALUES
  (10,1,'旧共享地址','陕西省','西安市','旧数据真实地址');
INSERT INTO category(id,name,enabled) VALUES(1,'旧分类',TRUE);
INSERT INTO product(id,category_id,title,published) VALUES(1,1,'旧商品',TRUE);
INSERT INTO sku(id,product_id,code,spec_json,retail_price_fen,net_weight_g,billable_weight_g,active)
  VALUES(1,1,'LEGACY-SKU','{}',2000,500,500,TRUE);
INSERT INTO shipping_template(id,name,enabled,version) VALUES(1,'旧模板',TRUE,3);
INSERT INTO shipping_rule(template_id,region_code,blocked,first_weight_g,first_fee_fen,step_weight_g,step_fee_fen)
  VALUES(1,'000000',FALSE,1000,600,1000,200);
INSERT INTO sku_supply(id,sku_id,supplier_id,origin_id,supply_price_fen,is_default,shipping_template_id)
  VALUES(100,1,2,10,1200,TRUE,1);
INSERT INTO supply_inventory(supply_id,on_hand_qty,reserved_qty) VALUES(100,9,2);
INSERT INTO consumer_account(id) VALUES(1);
INSERT INTO sales_order(id,order_no,consumer_id,idempotency_key,request_hash,quote_hash,status,item_amount_fen,
  shipping_amount_fen,payable_amount_fen,recipient,mobile,province_code,province_name,city_code,city_name,
  district_code,district_name,address_detail,expires_at)
VALUES(1,'LEGACY-ORDER','1','legacy-key','request','quote','PAID',2000,600,2600,'旧客户','13900000000',
  '610000','陕西省','610100','西安市','610102','新城区','收货地址',CURRENT_TIMESTAMP + INTERVAL 1 DAY);
INSERT INTO order_group(id,order_id,supplier_id,supplier_name,origin_id,origin_label,origin_province,origin_city,
  origin_address,template_id,template_name,template_version,rule_region_code,billable_weight_g,item_amount_fen,shipping_fee_fen)
VALUES(1,1,2,'旧供应商乙',10,'旧共享地址','陕西省','西安市','旧数据真实地址',1,'旧模板',3,'000000',500,2000,600);
INSERT INTO order_item(id,order_id,group_id,product_id,product_title,sku_id,sku_code,spec_json,quantity,
  unit_price_fen,line_amount_fen,net_weight_g,billable_weight_g,supply_id,supply_price_fen,supplier_id,origin_id,template_id)
VALUES(1,1,1,1,'旧商品',1,'LEGACY-SKU','{}',1,2000,2000,500,500,100,1200,2,10,1);
INSERT INTO stock_reservation(order_id,supply_id,quantity,status) VALUES(1,100,2,'HELD');
SQL
echo "stage: legacy fixture inserted"

mysql_exec "$legacy_db" < "$migration_dir/V8__supplier_shipping_origins.sql"
echo "stage: legacy V8 applied"

test "$(mysql_exec -N -s "$legacy_db" -e "SELECT COUNT(*) FROM sku_supply x JOIN origin o ON o.id=x.origin_id WHERE x.id=100 AND x.supplier_id=2 AND o.supplier_id=2")" = "1"
test "$(mysql_exec -N -s "$legacy_db" -e "SELECT CONCAT(on_hand_qty,':',reserved_qty) FROM supply_inventory WHERE supply_id=100")" = "9:2"
test "$(mysql_exec -N -s "$legacy_db" -e "SELECT CONCAT(origin_label,':',origin_address,':',template_version) FROM order_group WHERE id=1")" = "旧共享地址:旧数据真实地址:3"
test "$(mysql_exec -N -s "$legacy_db" -e "SELECT COUNT(*) FROM migration_issue WHERE issue_type='CROSS_SUPPLIER_ORIGIN' AND entity_id=100")" = "1"
echo "stage: legacy preservation verified"

mysql_exec "$empty_db" <<'SQL'
INSERT INTO supplier(id,name,contact_name,contact_phone,source_type,enabled) VALUES
  (1,'供应商甲','甲','13800000001','FARMER',TRUE),(2,'供应商乙','乙','13800000002','FACTORY',TRUE);
INSERT INTO origin(id,supplier_id,label,province_code,province,city_code,city,district_code,district,address,
  contact_name,contact_phone,is_default,enabled) VALUES
  (1,1,'甲一仓','610000','陕西省','610100','西安市','610102','新城区','地址一','甲','13800000001',TRUE,TRUE),
  (2,1,'甲二仓','610000','陕西省','610100','西安市','610103','碑林区','地址二','甲','13800000001',FALSE,TRUE),
  (3,2,'乙仓','130000','河北省','130900','沧州市','130902','新华区','地址三','乙','13800000002',TRUE,TRUE);
INSERT INTO category(id,name,enabled) VALUES(1,'分类',TRUE);
INSERT INTO product(id,category_id,title,published) VALUES(1,1,'商品',FALSE);
INSERT INTO sku(id,product_id,code,spec_json,retail_price_fen,net_weight_g,billable_weight_g,active)
  VALUES(1,1,'SKU-V8','{}',2000,500,500,TRUE);
INSERT INTO shipping_template(id,name,enabled,version) VALUES(1,'模板',TRUE,1);
INSERT INTO sku_supply(id,sku_id,supplier_id,origin_id,supply_price_fen,is_default,shipping_template_id)
  VALUES(1,1,1,1,1000,TRUE,1),(2,1,1,2,1100,FALSE,1);
INSERT INTO supply_inventory(supply_id,on_hand_qty,reserved_qty) VALUES(1,8,2),(2,20,1);
SQL
echo "stage: constraint fixture inserted"

if mysql_exec "$empty_db" -e "INSERT INTO sku_supply(id,sku_id,supplier_id,origin_id,supply_price_fen,is_default,shipping_template_id) VALUES(3,1,2,1,900,FALSE,1)" >/dev/null 2>&1; then
  echo "cross-supplier origin constraint unexpectedly accepted" >&2
  exit 1
fi
if mysql_exec "$empty_db" -e "UPDATE origin SET is_default=TRUE WHERE id=2" >/dev/null 2>&1; then
  echo "multiple default origins unexpectedly accepted" >&2
  exit 1
fi
if mysql_exec "$empty_db" -e "UPDATE origin SET enabled=FALSE WHERE id=1" >/dev/null 2>&1; then
  echo "disabled default origin unexpectedly accepted" >&2
  exit 1
fi
mysql_exec "$empty_db" -e "START TRANSACTION; SELECT id FROM supplier WHERE id=1 FOR UPDATE; UPDATE origin SET is_default=FALSE WHERE supplier_id=1; SELECT SLEEP(0.2); UPDATE origin SET is_default=TRUE WHERE id=1; COMMIT" >/dev/null &
default_job_one=$!
mysql_exec "$empty_db" -e "START TRANSACTION; SELECT id FROM supplier WHERE id=1 FOR UPDATE; UPDATE origin SET is_default=FALSE WHERE supplier_id=1; SELECT SLEEP(0.2); UPDATE origin SET is_default=TRUE WHERE id=2; COMMIT" >/dev/null &
default_job_two=$!
wait "$default_job_one"
wait "$default_job_two"
test "$(mysql_exec -N -s "$empty_db" -e "SELECT COUNT(*) FROM origin WHERE supplier_id=1 AND is_default=TRUE")" = "1"
test "$(mysql_exec -N -s "$empty_db" -e "SELECT CONCAT((SELECT on_hand_qty-reserved_qty FROM supply_inventory WHERE supply_id=1),':',(SELECT on_hand_qty-reserved_qty FROM supply_inventory WHERE supply_id=2))")" = "6:19"
test "$(mysql_exec -N -s "$empty_db" -e "SELECT COUNT(*) FROM origin WHERE supplier_id=1")" = "2"

echo "PASS: MySQL 8.4 empty/incremental migration, ownership/default checks, legacy snapshots and supply inventory isolation"
