ALTER TABLE payment_event ADD COLUMN payload_hash CHAR(64) NOT NULL DEFAULT '';
ALTER TABLE payment_event ADD COLUMN payload_json TEXT;
ALTER TABLE payment_event ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE payment_event ADD COLUMN next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE order_item ADD COLUMN supply_price_fen BIGINT NOT NULL DEFAULT 0;
UPDATE order_item SET supply_price_fen=(SELECT s.supply_price_fen FROM sku_supply s WHERE s.id=order_item.supply_id);
ALTER TABLE sales_order ADD COLUMN completion_status VARCHAR(24) NOT NULL DEFAULT 'NOT_COMPLETED';
ALTER TABLE sales_order ADD COLUMN after_sales_status VARCHAR(24) NOT NULL DEFAULT 'NONE';
ALTER TABLE sales_order ADD COLUMN completed_at TIMESTAMP;
ALTER TABLE fulfillment_task ADD COLUMN task_type VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL';
ALTER TABLE fulfillment_task ADD COLUMN source_key VARCHAR(100);
UPDATE fulfillment_task SET source_key=CONCAT('ORDER_GROUP:',order_group_id) WHERE source_key IS NULL;
ALTER TABLE fulfillment_task ADD CONSTRAINT uq_fulfillment_source UNIQUE(source_key);
ALTER TABLE fulfillment_task_item ADD COLUMN frozen_qty INT NOT NULL DEFAULT 0;
ALTER TABLE fulfillment_task_item ADD COLUMN cancelled_qty INT NOT NULL DEFAULT 0;
ALTER TABLE parcel ADD COLUMN received_at TIMESTAMP;
ALTER TABLE parcel ADD COLUMN received_by_consumer_id BIGINT;
ALTER TABLE parcel ADD CONSTRAINT fk_parcel_received_consumer FOREIGN KEY(received_by_consumer_id) REFERENCES consumer_account(id);

CREATE TABLE after_sale_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_no VARCHAR(40) NOT NULL UNIQUE,
  sales_order_id BIGINT NOT NULL,
  consumer_id BIGINT NOT NULL,
  requested_action VARCHAR(20) NOT NULL,
  reason_code VARCHAR(40) NOT NULL,
  reason_detail VARCHAR(500),
  status VARCHAR(30) NOT NULL,
  review_comment VARCHAR(500),
  responsibility VARCHAR(20),
  requested_refund_fen BIGINT NOT NULL DEFAULT 0,
  approved_refund_fen BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at TIMESTAMP,
  completed_at TIMESTAMP,
  CONSTRAINT fk_after_sale_order FOREIGN KEY(sales_order_id) REFERENCES sales_order(id),
  CONSTRAINT fk_after_sale_consumer FOREIGN KEY(consumer_id) REFERENCES consumer_account(id),
  CONSTRAINT ck_after_sale_money CHECK(requested_refund_fen>=0 AND approved_refund_fen>=0)
);
CREATE INDEX idx_after_sale_order ON after_sale_case(sales_order_id,status);
CREATE TABLE after_sale_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  task_item_id BIGINT NOT NULL,
  requested_qty INT NOT NULL,
  frozen_qty INT NOT NULL DEFAULT 0,
  approved_qty INT NOT NULL DEFAULT 0,
  unit_paid_fen BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
  CONSTRAINT fk_after_sale_item_case FOREIGN KEY(case_id) REFERENCES after_sale_case(id),
  CONSTRAINT fk_after_sale_order_item FOREIGN KEY(order_item_id) REFERENCES order_item(id),
  CONSTRAINT fk_after_sale_task_item FOREIGN KEY(task_item_id) REFERENCES fulfillment_task_item(id),
  CONSTRAINT uq_after_sale_case_item UNIQUE(case_id,order_item_id),
  CONSTRAINT ck_after_sale_qty CHECK(requested_qty>0 AND frozen_qty>=0 AND approved_qty>=0)
);
CREATE TABLE after_sale_evidence (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL,
  uploader_consumer_id BIGINT NOT NULL,
  object_key VARCHAR(300) NOT NULL UNIQUE,
  content_type VARCHAR(100) NOT NULL,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_evidence_case FOREIGN KEY(case_id) REFERENCES after_sale_case(id),
  CONSTRAINT fk_evidence_consumer FOREIGN KEY(uploader_consumer_id) REFERENCES consumer_account(id),
  CONSTRAINT ck_evidence_size CHECK(size_bytes>0 AND size_bytes<=5242880)
);
CREATE TABLE after_sale_operation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL,
  operation VARCHAR(40) NOT NULL,
  detail VARCHAR(500) NOT NULL,
  actor_type VARCHAR(20) NOT NULL,
  actor VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_after_sale_operation_case FOREIGN KEY(case_id) REFERENCES after_sale_case(id)
);

CREATE TABLE refund_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  refund_no VARCHAR(40) NOT NULL UNIQUE,
  channel_request_no VARCHAR(50) NOT NULL UNIQUE,
  payment_order_id BIGINT NOT NULL,
  payment_anomaly_id BIGINT,
  after_sale_case_id BIGINT,
  amount_fen BIGINT NOT NULL,
  shipping_amount_fen BIGINT NOT NULL DEFAULT 0,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  status VARCHAR(24) NOT NULL,
  reason VARCHAR(240) NOT NULL,
  created_by VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP,
  CONSTRAINT fk_refund_payment FOREIGN KEY(payment_order_id) REFERENCES payment_order(id),
  CONSTRAINT fk_refund_anomaly FOREIGN KEY(payment_anomaly_id) REFERENCES payment_anomaly(id),
  CONSTRAINT fk_refund_after_sale FOREIGN KEY(after_sale_case_id) REFERENCES after_sale_case(id),
  CONSTRAINT uq_refund_after_sale UNIQUE(after_sale_case_id),
  CONSTRAINT ck_refund_amount CHECK(amount_fen>0 AND shipping_amount_fen>=0 AND shipping_amount_fen<=amount_fen)
);
CREATE INDEX idx_refund_payment_status ON refund_order(payment_order_id,status);
CREATE TABLE refund_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  refund_order_id BIGINT NOT NULL,
  attempt_no VARCHAR(40) NOT NULL UNIQUE,
  channel VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  channel_refund_no VARCHAR(100),
  failure_code VARCHAR(80),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_refund_attempt_order FOREIGN KEY(refund_order_id) REFERENCES refund_order(id),
  CONSTRAINT uq_channel_refund UNIQUE(channel,channel_refund_no)
);
CREATE TABLE refund_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  refund_order_id BIGINT NOT NULL,
  refund_attempt_id BIGINT NOT NULL,
  channel VARCHAR(24) NOT NULL,
  event_key VARCHAR(120) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  payload_json TEXT NOT NULL,
  processing_status VARCHAR(24) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  failure_reason VARCHAR(240),
  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at TIMESTAMP,
  CONSTRAINT fk_refund_event_order FOREIGN KEY(refund_order_id) REFERENCES refund_order(id),
  CONSTRAINT fk_refund_event_attempt FOREIGN KEY(refund_attempt_id) REFERENCES refund_attempt(id),
  CONSTRAINT uq_refund_event UNIQUE(channel,event_key)
);
CREATE TABLE refund_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  refund_order_id BIGINT NOT NULL UNIQUE,
  payment_order_id BIGINT NOT NULL,
  payment_anomaly_id BIGINT,
  amount_fen BIGINT NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_refund_ledger_order FOREIGN KEY(refund_order_id) REFERENCES refund_order(id),
  CONSTRAINT fk_refund_ledger_payment FOREIGN KEY(payment_order_id) REFERENCES payment_order(id),
  CONSTRAINT fk_refund_ledger_anomaly FOREIGN KEY(payment_anomaly_id) REFERENCES payment_anomaly(id)
);

CREATE TABLE replacement_reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  after_sale_case_id BIGINT NOT NULL UNIQUE,
  fulfillment_task_id BIGINT NOT NULL UNIQUE,
  supply_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  consumed_qty INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  CONSTRAINT fk_replacement_case FOREIGN KEY(after_sale_case_id) REFERENCES after_sale_case(id),
  CONSTRAINT fk_replacement_task FOREIGN KEY(fulfillment_task_id) REFERENCES fulfillment_task(id),
  CONSTRAINT fk_replacement_supply FOREIGN KEY(supply_id) REFERENCES sku_supply(id),
  CONSTRAINT ck_replacement_qty CHECK(quantity>0 AND consumed_qty>=0 AND consumed_qty<=quantity)
);

CREATE TABLE supplier_ledger_entry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  supplier_id BIGINT NOT NULL,
  sales_order_id BIGINT,
  order_item_id BIGINT,
  after_sale_case_id BIGINT,
  entry_type VARCHAR(30) NOT NULL,
  source_key VARCHAR(120) NOT NULL UNIQUE,
  quantity INT NOT NULL DEFAULT 0,
  amount_fen BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  description VARCHAR(240) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  available_at TIMESTAMP,
  CONSTRAINT fk_ledger_supplier FOREIGN KEY(supplier_id) REFERENCES supplier(id),
  CONSTRAINT fk_ledger_order FOREIGN KEY(sales_order_id) REFERENCES sales_order(id),
  CONSTRAINT fk_ledger_order_item FOREIGN KEY(order_item_id) REFERENCES order_item(id),
  CONSTRAINT fk_ledger_after_sale FOREIGN KEY(after_sale_case_id) REFERENCES after_sale_case(id)
);
CREATE INDEX idx_ledger_supplier_status ON supplier_ledger_entry(supplier_id,status,id);
CREATE TABLE settlement_statement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  statement_no VARCHAR(40) NOT NULL UNIQUE,
  supplier_id BIGINT NOT NULL,
  total_amount_fen BIGINT NOT NULL,
  paid_amount_fen BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL,
  created_by VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_statement_supplier FOREIGN KEY(supplier_id) REFERENCES supplier(id)
);
CREATE TABLE settlement_statement_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  statement_id BIGINT NOT NULL,
  ledger_entry_id BIGINT NOT NULL UNIQUE,
  locked_amount_fen BIGINT NOT NULL,
  CONSTRAINT fk_statement_item_statement FOREIGN KEY(statement_id) REFERENCES settlement_statement(id),
  CONSTRAINT fk_statement_item_entry FOREIGN KEY(ledger_entry_id) REFERENCES supplier_ledger_entry(id)
);
CREATE TABLE settlement_payment_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  statement_id BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  amount_fen BIGINT NOT NULL,
  paid_on DATE NOT NULL,
  reference_no VARCHAR(100) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  reverses_payment_id BIGINT,
  note VARCHAR(240),
  actor VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_settlement_payment_statement FOREIGN KEY(statement_id) REFERENCES settlement_statement(id),
  CONSTRAINT fk_settlement_payment_reversal FOREIGN KEY(reverses_payment_id) REFERENCES settlement_payment_record(id),
  CONSTRAINT uq_settlement_payment_key UNIQUE(statement_id,idempotency_key),
  CONSTRAINT ck_settlement_payment_amount CHECK(amount_fen>0)
);
