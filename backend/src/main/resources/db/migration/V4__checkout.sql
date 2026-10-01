CREATE TABLE consumer_account (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE consumer_identity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  consumer_id BIGINT NOT NULL,
  platform VARCHAR(16) NOT NULL,
  app_id VARCHAR(100) NOT NULL,
  platform_user_id VARCHAR(180) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_identity_consumer FOREIGN KEY (consumer_id) REFERENCES consumer_account(id),
  CONSTRAINT uq_platform_identity UNIQUE (platform,app_id,platform_user_id)
);
CREATE TABLE consumer_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  consumer_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_session_consumer FOREIGN KEY (consumer_id) REFERENCES consumer_account(id)
);
CREATE TABLE customer_address (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  consumer_id BIGINT NOT NULL,
  recipient VARCHAR(60) NOT NULL,
  mobile VARCHAR(20) NOT NULL,
  province_code CHAR(6) NOT NULL,
  province_name VARCHAR(40) NOT NULL,
  city_code CHAR(6) NOT NULL,
  city_name VARCHAR(40) NOT NULL,
  district_code CHAR(6) NOT NULL,
  district_name VARCHAR(40) NOT NULL,
  detail VARCHAR(240) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_address_consumer FOREIGN KEY (consumer_id) REFERENCES consumer_account(id)
);
CREATE INDEX idx_address_consumer ON customer_address (consumer_id,is_default);
CREATE TABLE shipping_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  free_threshold_fen BIGINT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 1,
  CONSTRAINT ck_free_threshold CHECK (free_threshold_fen IS NULL OR free_threshold_fen >= 0)
);
CREATE TABLE shipping_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  region_code VARCHAR(6) NOT NULL,
  blocked BOOLEAN NOT NULL DEFAULT FALSE,
  first_weight_g INT NOT NULL,
  first_fee_fen BIGINT NOT NULL,
  step_weight_g INT NOT NULL,
  step_fee_fen BIGINT NOT NULL,
  CONSTRAINT fk_rule_template FOREIGN KEY (template_id) REFERENCES shipping_template(id),
  CONSTRAINT uq_template_region UNIQUE (template_id,region_code),
  CONSTRAINT ck_rule_weight CHECK (first_weight_g > 0 AND step_weight_g > 0),
  CONSTRAINT ck_rule_fee CHECK (first_fee_fen >= 0 AND step_fee_fen >= 0)
);
ALTER TABLE sku ADD COLUMN net_weight_g INT NOT NULL DEFAULT 0;
ALTER TABLE sku ADD COLUMN billable_weight_g INT NOT NULL DEFAULT 0;
ALTER TABLE sku_supply ADD COLUMN shipping_template_id BIGINT;
ALTER TABLE sku_supply ADD CONSTRAINT fk_supply_template FOREIGN KEY (shipping_template_id) REFERENCES shipping_template(id);
CREATE TABLE supply_inventory (
  supply_id BIGINT PRIMARY KEY,
  on_hand_qty INT NOT NULL DEFAULT 0,
  reserved_qty INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_inventory_supply FOREIGN KEY (supply_id) REFERENCES sku_supply(id),
  CONSTRAINT ck_inventory_nonnegative CHECK (on_hand_qty >= 0 AND reserved_qty >= 0 AND on_hand_qty >= reserved_qty)
);
INSERT INTO supply_inventory (supply_id,on_hand_qty,reserved_qty) SELECT id,0,0 FROM sku_supply;
CREATE TABLE shopping_cart (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  consumer_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  selected BOOLEAN NOT NULL DEFAULT TRUE,
  row_version BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cart_consumer FOREIGN KEY (consumer_id) REFERENCES consumer_account(id),
  CONSTRAINT fk_cart_sku FOREIGN KEY (sku_id) REFERENCES sku(id),
  CONSTRAINT uq_cart_sku UNIQUE (consumer_id,sku_id),
  CONSTRAINT ck_cart_quantity CHECK (quantity BETWEEN 1 AND 99)
);
CREATE TABLE sales_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(40) NOT NULL UNIQUE,
  consumer_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  quote_hash CHAR(64) NOT NULL,
  status VARCHAR(30) NOT NULL,
  item_amount_fen BIGINT NOT NULL,
  shipping_amount_fen BIGINT NOT NULL,
  payable_amount_fen BIGINT NOT NULL,
  recipient VARCHAR(60) NOT NULL,
  mobile VARCHAR(20) NOT NULL,
  province_code CHAR(6) NOT NULL,
  province_name VARCHAR(40) NOT NULL,
  city_code CHAR(6) NOT NULL,
  city_name VARCHAR(40) NOT NULL,
  district_code CHAR(6) NOT NULL,
  district_name VARCHAR(40) NOT NULL,
  address_detail VARCHAR(240) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  closed_at TIMESTAMP,
  CONSTRAINT fk_order_consumer FOREIGN KEY (consumer_id) REFERENCES consumer_account(id),
  CONSTRAINT uq_order_idempotency UNIQUE (consumer_id,idempotency_key)
);
CREATE INDEX idx_order_expiry ON sales_order (status,expires_at);
CREATE INDEX idx_order_consumer ON sales_order (consumer_id,id);
CREATE TABLE order_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  supplier_name VARCHAR(120) NOT NULL,
  origin_id BIGINT NOT NULL,
  origin_label VARCHAR(120) NOT NULL,
  origin_province VARCHAR(80) NOT NULL,
  origin_city VARCHAR(80) NOT NULL,
  template_id BIGINT NOT NULL,
  template_name VARCHAR(100) NOT NULL,
  rule_region_code VARCHAR(6) NOT NULL,
  billable_weight_g BIGINT NOT NULL,
  item_amount_fen BIGINT NOT NULL,
  shipping_fee_fen BIGINT NOT NULL,
  CONSTRAINT fk_group_order FOREIGN KEY (order_id) REFERENCES sales_order(id)
);
CREATE TABLE order_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  group_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_title VARCHAR(200) NOT NULL,
  sku_id BIGINT NOT NULL,
  sku_code VARCHAR(80) NOT NULL,
  spec_json TEXT NOT NULL,
  quantity INT NOT NULL,
  unit_price_fen BIGINT NOT NULL,
  line_amount_fen BIGINT NOT NULL,
  net_weight_g INT NOT NULL,
  billable_weight_g INT NOT NULL,
  supply_id BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  origin_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
  CONSTRAINT fk_item_group FOREIGN KEY (group_id) REFERENCES order_group(id)
);
CREATE TABLE stock_reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  supply_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  CONSTRAINT fk_reservation_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
  CONSTRAINT fk_reservation_supply FOREIGN KEY (supply_id) REFERENCES sku_supply(id),
  CONSTRAINT uq_reservation_order_supply UNIQUE (order_id,supply_id)
);
CREATE TABLE inventory_movement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  supply_id BIGINT NOT NULL,
  order_id BIGINT,
  delta_on_hand INT NOT NULL,
  delta_reserved INT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  actor VARCHAR(80) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_movement_supply FOREIGN KEY (supply_id) REFERENCES sku_supply(id),
  CONSTRAINT fk_movement_order FOREIGN KEY (order_id) REFERENCES sales_order(id)
);
