# Domain model

## Implemented in Phase 1

- `admin_user`: login name and BCrypt password hash. `admin_session`: digest and expiry of an opaque bearer token.
- `audit_log`: administrator, method, path, response status and timestamp for successful management mutations. It intentionally excludes request bodies and secrets.
- `store_settings`: singleton public brand name, logo URL, theme color and contact phone. Secret settings do not belong here.
- `category`: ordered product classification.
- `product`: title, description, category, published flag and images; `sku`: consumer-facing specification JSON, retail price in fen and active flag. Product and SKU fields are generic, so apple and packaged nut specifications are data.
- `supplier`: fulfillment party, contact and source type (`SELF`, `FARMER`, `FACTORY`). `origin` is the supplier-owned shipping-origin record (name, province/city/district codes and names, detail, shipping contact/phone, default and enabled state). A physical address used by two suppliers is stored twice. A supplier has at most one enabled default origin; it is only a creation-time suggestion.
- `product.origin_description` is the merchandise-production origin and is independent of shipping origins. Editing an `origin` never changes it.
- `sku_supply`: one concrete SKU + supplier + shipping origin + supply price + shipping template relationship. A SKU can have several records, including the same supplier at different origins; `supply_inventory` remains exclusively keyed by `supply_id`. Exactly one source per SKU may be default. Supplier/origin cannot be changed after stock, movement, reservation or order use; replacement requires a new relationship and an explicit default switch.
- `media_object`: stored object key and MIME type. Public image URLs use backend media proxy.

## Implemented in Phase 2

- `consumer_account` is separate from administrators. `consumer_identity` binds one platform/AppID user identity without cross-platform merging; WeChat OpenID comes only from the server-side code exchange. The unique `(platform, app_id, platform_user_id)` key serializes concurrent first logins, and a failed insert rolls back its new consumer row. `consumer_session` stores only token hashes; neither WeChat AppSecret nor session key is persisted.
- `customer_address` belongs to one consumer. Checkout copies every recipient/phone/region/detail field into `sales_order`, so address edits do not affect order history.
- Address region codes must form a path in the server region catalog. The server resolves province, city and district names and retains consumer ownership checks; delivery availability is evaluated separately by the quote.
- `shopping_cart` is unique by consumer and SKU and carries a row version. Successful checkout removes only rows whose IDs and versions still match the quote.
- `shipping_template` has an optional per-fulfillment-group free-shipping threshold and a monotonic version. `shipping_rule` stores mainland region matching, blocked regions, first/step weights and fees.
- `sku` stores display net weight and carrier billable weight in grams. `sku_supply.shipping_template_id` binds the default supply to its shipping rules.
- `supply_inventory` is the sole stock balance for a supply source. `inventory_movement` records administrator adjustments and reservation/release reasons. `stock_reservation` is unique per order/supply and changes from `HELD` to `RELEASED` once.
- `sales_order` owns idempotency/request hashes, `PENDING_PAYMENT`/`CANCELLED`/`CLOSED`, integer-fen totals, address snapshot and server expiry. `order_item` and `order_group` preserve catalog, supply, complete origin label/address, template version and freight snapshots; they are not fulfillment tasks.

## Phase 3 state transitions and consistency rules

`sales_order.status` remains the business-order state (`PENDING_PAYMENT`, `CLOSE_PENDING`, `PAID`,
`CANCELLED`, `CLOSED`). Payment progress is held separately in
`sales_order.payment_status` (`UNPAID`, `PENDING`, `UNKNOWN`, `CLOSE_PENDING`, `PAID`, `EXCEPTION`),
and fulfillment progress in `sales_order.fulfillment_status` (`NOT_STARTED`, `PENDING`, `PARTIALLY_SHIPPED`,
`SHIPPED`). No single state is used to imply all three concerns.

- `payment_order` is the order-level receivable in CNY/fen. `payment_attempt` is one explicit channel
  attempt and owns its channel transaction number. A still-pending attempt is reused. An `UNKNOWN`
  attempt blocks another attempt until query/close establishes a terminal result. Switching channels
  therefore requires closing and verifying the old attempt first.
- A customer cancellation or expiry with no active attempt atomically closes the order and releases
  reservations. With a `PENDING` or `UNKNOWN` attempt it first changes to `CLOSE_PENDING`; channel
  close/query happens outside the database transaction. Only a confirmed unpaid/closed result, with
  no other active attempt, releases reservations. A timeout or unavailable channel keeps the order in
  coordination and never guesses that it is unpaid.
- A trusted success locks the payment order and sales order, validates order, amount, currency and
  merchant subject, and deduplicates `payment_event` by channel/event key. If reservations are `HELD`,
  it changes them to `SOLD` while decrementing both `on_hand_qty` and `reserved_qty`; it does not
  decrement available stock a second time. It then marks payment/order paid, creates one
  `fulfillment_task` per checkout-time `order_group`, copies task items from the order snapshots, and
  inserts notification outbox rows in the same transaction.
- Success wins over a later cancellation because both serialize on the order/payment rows and the
  closer's conditional transition no longer matches `PAID`. A success arriving after reservations were
  released, or a second successful collection, is recorded in `payment_anomaly`; it never restores the
  order or creates fulfillment work automatically and is left for refund/manual handling.
- `fulfillment_task.order_group_id` is unique. Parcel creation is idempotent per task/request key;
  task rows and shipped counters are locked while parcel quantities are checked, so concurrent requests
  cannot exceed ordered quantities. Parcel edits append `parcel_operation_log` rows.
- `notification_outbox` is independent of task visibility. Workers claim due rows with leases, use an
  idempotency key at the notification adapter, retry with backoff up to the configured maximum, and can
  recover expired claims after a process crash. Payloads contain identifiers, not full addresses/phones.

Refund execution, automatic receipt, after-sales, settlement and supplier payout remain outside Phase 3.

## Phase 4 state transitions and consistency rules

- Payment/refund channel events separate receipt from business completion. The receipt transaction stores the
  verified canonical payload and hash as `RECEIVED`; a second transaction locks and applies it, then marks it
  `PROCESSED`. A rollback leaves `RECEIVED`, so the identical event can be retried by the caller or recovery job
  after restart. `channel + event_key` with a different payload is rejected; a processed identical payload is a no-op.
- Each parcel is confirmed independently (`received_at`). Only the owning consumer may confirm a shipped parcel;
  confirmation is idempotent. An order becomes completed when every effective original quantity
  (`required - cancelled`) is shipped and all its parcels are received. Completion and after-sales status remain
  separate, and no automatic confirmation timer exists.
- An after-sales case owns immutable requested item quantities and an action (`REFUND` or `REPLACEMENT`). A refund
  application may also carry an explicit integer-fen request amount; the miniapp parses yuan text exactly and the
  server caps it at the remaining quantity's captured item price and current order goods balance. A quantity
  already claimed in a non-rejected case cannot be claimed again. The remaining financial balance alone does not
  grant another application for the same unit. Review cannot approve more than the requested amount or current
  refundable goods, shipping and collected payment balances. An unshipped refund application locks the order,
  fulfillment task and task items in ascending ID order and moves available
  quantities to `frozen_qty`. Parcel creation uses the same order and checks
  `shipped + frozen + cancelled <= required`, so a quantity cannot be shipped and cancelled concurrently. Rejection
  releases only that case's freeze. Approval moves the frozen quantity to `cancelled_qty` and restores original
  inventory exactly once; shipped damage refunds never restore inventory.
- `refund_order`, `refund_attempt` and `refund_event` are distinct. A refund is bound to a successful payment
  transaction (or a specific anomalous successful collection), uses one stable channel refund request number, and
  reserves amount while `PENDING` or `UNKNOWN`. Successful, pending and unknown refund amounts may never exceed the
  collected transaction balance or an order line's server-calculated refundable balance. Adapter calls are outside
  database transactions; callback, synchronous result and query all enter the same event processor.
- An approved replacement creates at most one `REPLACEMENT` fulfillment task using a unique source key. It retains
  the original order item/supply snapshot, reserves current stock with a conditional update, and remains
  `WAITING_STOCK` when unavailable. Replacement parcels are tracked independently and do not change sales quantity
  or revenue. Replacement cost and responsibility are explicit ledger entries.
- Supplier ledger entries use `order_item.supply_price_fen`, captured at checkout, never the current supply price.
  Goods, agreed shipping, after-sales deductions, replacement cost and manual adjustments are separate signed rows.
  Original goods become `AVAILABLE` only for confirmed received effective quantities without a pending related case.
  Statement items uniquely lock ledger entries. Manual payment records are idempotent, allow partial payment, state
  that they are unverified manual records, and are reversed by an appended reversal—not by editing history. Changes
  after payment create adjustment rows rather than rewriting paid amounts.

The global lock order for Phase 4 is sales order → fulfillment task → task item → payment/refund aggregate → supply
inventory → settlement rows, with numeric IDs ascending inside each class. Deadlock victims roll back completely and
are safe to retry through request/event idempotency keys.

Publishing requires an active SKU with a default supply source and at least one product image. Public catalog never returns supply prices or supplier contact information. Supplier role access is scoped to its own fulfillment, necessary after-sales data, ledger and statements.

Shipping templates are merchant-global in this single-merchant deployment. The effective template is the explicit `sku_supply.shipping_template_id`; there is no competing origin-level template. Quote and checkout group by supplier + shipping origin + effective template. Orders snapshot supplier, supply ID/price, complete shipping-origin/contact fields and template name/version/rule, so later defaults, address edits or template edits do not rewrite history.
