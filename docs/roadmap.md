# Roadmap

## Phase 1 — catalog publication (complete)

Bootstrap infrastructure and migrations; secure admin login; manage store, categories, products, SKUs, images, suppliers, origins and default SKU sources; connect admin and both miniapp targets to public catalog APIs. Prove create → publish → read → unpublish with tests and, when local services are available, a live smoke test.

## Phase 2 — order placement (complete in this delivery)

Customer identity, cart, addresses, mainland shipping rules and quote, default-supply inventory reservation, immutable checkout snapshots, pending-order lifecycle and retryable timeout closure are implemented. Automated tests cover ownership, shipping boundaries, quote changes, idempotency, snapshots and release races; a live MySQL smoke test covers the full flow and last-unit concurrency. Real platform OAuth and payment remain Phase 3 work.

## Phase 3 — payment and fulfillment (complete for development integration)

Development-only payment simulator; payment/attempt/event/anomaly model; idempotent confirmation, close coordination and active-state reconciliation boundaries; fulfillment tasks, supplier account/workbench, partial multi-parcel shipping, consumer parcel view and retryable notification outbox are complete. Live WeChat/Alipay adapters, channel signature implementations, real reconciliation jobs, real messaging and logistics tracking wait for merchant credentials and provider qualification.

## Phase 4 — service and delivery (complete for development integration)

Manual parcel receipt, minimum partial-refund/replacement after-sales, private evidence, Phase 3 anomaly refund handling, development-only refund simulator, replacement stock, supplier settlement ledger/statements and unverified manual payout records are complete. Automatic receipt, return shipping, exchange, real refund execution, carrier tracking, automatic transfer/splitting, broader audit/monitoring, backup/restore, security review, deployment hardening and merchant packaging remain later work.

## Phase 5 — stabilization and local delivery acceptance (in progress)

Both miniapp builds, backend/admin builds, isolated backup/restore and the requested browser after-sales, replacement, settlement-adjustment and 390px supplier workflows passed locally with test data. On 2026-09-22 the WeChat Developer Tools simulator actually completed catalog → SKU → cart → address → shipping quote → order → development simulated payment → parcel receipt → refund evidence and progress, plus a replacement case and parcel. Consumer page steps were driven in the simulator; admin APIs only prepared catalog and processed fulfillment/review. Device acceptance and the full Nginx container route remain unverified; the latter is blocked by Docker Hub authorization via a refused local proxy. See `docs/release-readiness.md`. This is not a production launch sign-off.

The supplier → multiple shipping origins → SKU supply model is now implemented in V8. It keeps one default supply per SKU and supply-owned inventory, adds supplier-scoped origin/default constraints and complete checkout snapshots, and intentionally does not implement automatic warehouse selection or inter-origin transfer.

## Later phase — production payment, refund and operations

After merchant qualification and credentials exist: implement WeChat/Alipay signature verification, real refund create/query/callback and reconciliation; add return/exchange policy, carrier events, operational alerts, rate limits and production acceptance. Automated supplier transfer or revenue splitting remains a separate product decision.
