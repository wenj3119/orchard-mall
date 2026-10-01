ALTER TABLE sales_order ADD COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT 'UNPAID';
ALTER TABLE sales_order ADD COLUMN fulfillment_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE sales_order ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE payment_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sales_order_id BIGINT NOT NULL,
  payment_no VARCHAR(40) NOT NULL UNIQUE,
  amount_fen BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  merchant_subject VARCHAR(100) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_payment_sales_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id),
  CONSTRAINT uq_payment_sales_order UNIQUE (sales_order_id),
  CONSTRAINT ck_payment_amount CHECK (amount_fen >= 0)
);
CREATE TABLE payment_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_order_id BIGINT NOT NULL,
  attempt_no VARCHAR(40) NOT NULL UNIQUE,
  channel VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  channel_transaction_no VARCHAR(100),
  failure_code VARCHAR(60),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_attempt_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
  CONSTRAINT uq_channel_transaction UNIQUE (channel,channel_transaction_no)
);
CREATE INDEX idx_attempt_payment_status ON payment_attempt(payment_order_id,status);
CREATE TABLE payment_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_order_id BIGINT NOT NULL,
  payment_attempt_id BIGINT,
  channel VARCHAR(24) NOT NULL,
  event_key VARCHAR(120) NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  processing_status VARCHAR(24) NOT NULL,
  failure_reason VARCHAR(240),
  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMP,
  CONSTRAINT fk_event_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
  CONSTRAINT fk_event_attempt FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempt(id),
  CONSTRAINT uq_payment_event UNIQUE (channel,event_key)
);
CREATE TABLE payment_anomaly (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  payment_order_id BIGINT NOT NULL,
  payment_attempt_id BIGINT,
  anomaly_type VARCHAR(40) NOT NULL,
  channel_transaction_no VARCHAR(100),
  amount_fen BIGINT NOT NULL,
  detail VARCHAR(240) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_anomaly_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
  CONSTRAINT fk_anomaly_attempt FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempt(id)
);

CREATE TABLE supplier_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  supplier_id BIGINT NOT NULL,
  username VARCHAR(80) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  credentials_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_supplier_user_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);
CREATE TABLE supplier_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  supplier_user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  credentials_version BIGINT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_supplier_session_user FOREIGN KEY (supplier_user_id) REFERENCES supplier_user(id)
);
CREATE TABLE fulfillment_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(40) NOT NULL UNIQUE,
  sales_order_id BIGINT NOT NULL,
  order_group_id BIGINT NOT NULL,
  supplier_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  accepted_at TIMESTAMP,
  accepted_by VARCHAR(100),
  shipped_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_task_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id),
  CONSTRAINT fk_task_group FOREIGN KEY (order_group_id) REFERENCES order_group(id),
  CONSTRAINT fk_task_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id),
  CONSTRAINT uq_task_group UNIQUE (order_group_id)
);
CREATE INDEX idx_task_supplier_status ON fulfillment_task(supplier_id,status,id);
CREATE TABLE fulfillment_task_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  product_title VARCHAR(200) NOT NULL,
  sku_code VARCHAR(80) NOT NULL,
  spec_json TEXT NOT NULL,
  required_qty INT NOT NULL,
  shipped_qty INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_task_item_task FOREIGN KEY (task_id) REFERENCES fulfillment_task(id),
  CONSTRAINT fk_task_item_order_item FOREIGN KEY (order_item_id) REFERENCES order_item(id),
  CONSTRAINT uq_task_order_item UNIQUE (task_id,order_item_id),
  CONSTRAINT ck_task_item_qty CHECK (required_qty > 0 AND shipped_qty >= 0 AND shipped_qty <= required_qty)
);
CREATE TABLE parcel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  carrier_code VARCHAR(40) NOT NULL,
  carrier_name VARCHAR(80) NOT NULL,
  tracking_no VARCHAR(100) NOT NULL,
  created_by_type VARCHAR(16) NOT NULL,
  created_by VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_parcel_task FOREIGN KEY (task_id) REFERENCES fulfillment_task(id),
  CONSTRAINT uq_parcel_idempotency UNIQUE (task_id,idempotency_key)
);
CREATE TABLE parcel_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  parcel_id BIGINT NOT NULL,
  task_item_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  CONSTRAINT fk_parcel_item_parcel FOREIGN KEY (parcel_id) REFERENCES parcel(id),
  CONSTRAINT fk_parcel_item_task_item FOREIGN KEY (task_item_id) REFERENCES fulfillment_task_item(id),
  CONSTRAINT uq_parcel_task_item UNIQUE (parcel_id,task_item_id),
  CONSTRAINT ck_parcel_item_quantity CHECK (quantity > 0)
);
CREATE TABLE parcel_operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  parcel_id BIGINT NOT NULL,
  operation VARCHAR(24) NOT NULL,
  before_value VARCHAR(240),
  after_value VARCHAR(240) NOT NULL,
  actor_type VARCHAR(16) NOT NULL,
  actor VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_parcel_log_parcel FOREIGN KEY (parcel_id) REFERENCES parcel(id)
);
CREATE TABLE notification_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_type VARCHAR(40) NOT NULL,
  aggregate_type VARCHAR(40) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  idempotency_key VARCHAR(120) NOT NULL UNIQUE,
  payload_json TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 8,
  next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_until TIMESTAMP,
  worker_id VARCHAR(80),
  last_error VARCHAR(240),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP
);
CREATE INDEX idx_outbox_claim ON notification_outbox(status,next_attempt_at,lease_until,id);
