# Verification

## 2026-09-23 — supplier shipping origins / supply-source V8

- Backend: `./mvnw test` passed 24 tests. The focused flows pass ownership, source immutability after inventory use, shipping-origin grouping, default-origin non-migration, order snapshots, disable/quote behavior, cancellation release to both original supply IDs, refund while the old origin is disabled, and an explicit waiting reason (without supplier switching) for replacement from a disabled origin.
- MySQL 8.4: `scripts/verify_v8_mysql.sh` passed against `orchard-mall-local-mysql-1`. It applied V1–V8 to an empty isolated database and V1–V7 + legacy fixtures + V8 to another isolated database. The check preserved supply ID 100, `9/2` on-hand/reserved stock, the old order-group address/template snapshot, cloned a cross-supplier address into a supplier-owned record with a migration issue, rejected cross-supplier binding, rejected two defaults and disabling a default address, and serialized two concurrent default-setting transactions to exactly one default. Both temporary databases were dropped by the script; the development database was not migrated or overwritten.
- Admin: `npm run typecheck && npm run build` passed (the existing Vite large-chunk warning remains). The supply form filters enabled origins after supplier selection, clears stale selection, preselects the supplier default for new records, supports inline origin creation/refresh, includes the shipping template, and explains locked source fields.
- Miniapp: `npm run build:weapp && npm run build:alipay` passed after the additive public product-origin response change; no client adaptation was required.
- Browser: pending in this execution environment because the required in-app browser runtime tool was not exposed. No standalone browser driver was substituted, per the browser-control skill; API and component-build coverage completed independently.
- No unresolved ambiguity existed in the synthetic legacy fixture. Real deployments can query `migration_issue WHERE resolved=FALSE`; V8 records cross-supplier clones, incomplete old addresses, duplicate defaults and missing templates rather than inventing values.
- Current development database was inspected read-only and left at schema V5: 8 suppliers, 8 origins, 11 supplies/inventories and 7 order groups. It has no cross-supplier origin reference, blank origin address or SKU with multiple defaults. Two physical-address groups occur under multiple suppliers, but the records are already supplier-owned as required. Six supplies have no template; V8 will list those exact supply IDs as `MISSING_SHIPPING_TEMPLATE` for manual selection and will not invent a template.

# Phase 2 verification

Executed on 2026-09-19 with local JDK 26 targeting Java 21, Node 26, MySQL 8.4 in the `orchard-mall-local` Compose project, and the existing MinIO container.

| Check | Result |
| --- | --- |
| `backend/./mvnw test` | Passed: 5 Phase 1/2 tests. V1–V5 migrated in H2. Coverage includes production rejection of dev login, customer ownership, shipping rounding/block/free threshold, changed quote, idempotent order creation, immutable snapshots, repeated cancellation, and cancellation/expiry release competition. |
| `backend/./mvnw -DskipTests package` | Passed; Java 21 target Spring Boot jar produced. |
| Admin `npm run typecheck && npm run build` | Passed. Vite still reports the existing >500 kB chunk warning. |
| Miniapp `npx tsc --noEmit` | Passed. |
| Miniapp `npm run build:weapp` / `build:alipay` | Both passed when run with normal macOS SystemConfiguration access (1.47 s and 1.23 s). The restricted sandbox attempt triggered a native `system-configuration` panic; this was an execution-environment issue. Builds were not opened in either graphical developer tool and are not claimed as device success. |
| `docker compose ... config --quiet` | Passed for the full profile. Effective project is `orchard-mall-local`; network and the three named volumes have that prefix, no `container_name` is fixed, and host ports are loopback-only/configurable in the new config. |
| Docker isolation applied | Inspection confirmed mall containers carry project label `orchard-mall-local`; unrelated `deploy`, `lottery_admin`, and other containers were not changed. Only this project's MySQL/Redis/MinIO containers were recreated with existing named volumes; all became healthy/running with ports bound to `127.0.0.1` only. |
| Full container build and Nginx | Attempted, but Docker Desktop's configured proxy `127.0.0.1:7892` refused the base-image authorization request. No backend/Nginx container was created, so the Nginx route is not claimed as verified. Existing data volumes were preserved. |
| MySQL 8.4 Flyway V4/V5 | Passed against the live project database: V1–V3 validated, V4 applied in 179 ms; the final restart validated all five migrations and applied V5 in 17 ms, reaching schema v5. |
| Live Phase 2 E2E | Passed on temporary local backend port 18080 using `scripts/smoke_phase2.py`: admin inventory/template setup → dev consumer → address/cart → quote (1500 g billed as 1000 g first + one 500 g step = 800 fen) → pending order → admin detail → cancel → reserved stock 0/available restored. |
| Real MySQL concurrency/rollback | Passed: two consumers racing for the last unit produced exactly one 201 and one 409. A second two-SKU race verified same-group weight merging and the harder rollback case: the losing transaction reserved the first supply before failing on the second, but the first supply's reserved count remained 1 (winner only), then cancellation restored both. |

## Flyway/MySQL compatibility decision

Spring Boot 3.4.5 manages Flyway 10.20.1. Its live startup warning states that MySQL 8.4 is newer than its tested maximum (8.1). Successful migrations demonstrate that this schema currently runs, but do not establish vendor compatibility. Redgate's current MySQL reference still lists verified versions 5.7, 8.0 and 9.4 rather than 8.4, and even Flyway 11.15.0 had the same 8.4 warning recorded in the upstream issue. Therefore this delivery retains Boot-managed 10.20.1 instead of introducing an unverified Flyway major-version override into Spring Boot 3.4.5. Risk: later 8.4-specific DDL or metadata behavior may fail outside Flyway's tested matrix. Mitigation: keep real-MySQL migration tests in CI/staging and reassess together with a Spring Boot upgrade when Redgate explicitly verifies 8.4. References: https://documentation.red-gate.com/fd/mysql-277579322.html and https://github.com/flyway/flyway/issues/4168.

## Miniapp developer-tool acceptance

Compilation output is not equivalent to developer-tool or device execution. After the Taro native build issue is resolved:

1. Build with `TARO_APP_API_BASE=http://127.0.0.1:8080 npm run build:weapp` and import `miniapp/dist/weapp` into WeChat Developer Tools.
2. Build with the same API setting using `npm run build:alipay` and import `miniapp/dist/alipay` into Alipay Mini Program Studio.
3. Run the backend with `SPRING_PROFILES_ACTIVE=dev` and `DEV_CONSUMER_LOGIN_ENABLED=true`; use the development login page, then verify address → cart → quote → order → cancel on each platform.
4. Confirm an unauthenticated cart/order tab redirects to login, unavailable cart rows explain why, and order pages say “支付暂未开放”.
5. For device testing replace the API base with a reachable HTTPS origin and configure each platform's request/download domain allowlist. No real AppID login, device run, or developer-tool run was performed in this delivery.

## Remaining limits

- No real platform OAuth, payment, payment success state, fulfillment, supplier notification, refund, after-sales or settlement exists.
- The live E2E creates uniquely named local verification rows and leaves them for audit; it never touches real funds.
- Full-container/Nginx verification must be rerun after repairing Docker Desktop's proxy. The infrastructure containers already use the new loopback-only bindings.

# Phase 3 verification

Executed on 2026-09-20 with local JDK 26 targeting Java 21, Node 26, the healthy
`orchard-mall-local` MySQL 8.4/MinIO containers, and isolated databases
`orchard_mall_phase3_test` and `orchard_mall_phase3_e2e`. No real payment API or funds were used.

| Check | Result |
| --- | --- |
| `backend/./mvnw test` | Passed: 12 tests (7 Phase 3 plus 5 Phase 1/2). V1–V6 migrated in H2. This is a fast regression signal only, not the MySQL concurrency claim. |
| Real MySQL `Phase3FlowTest` | Passed: 7 tests against MySQL 8.4 after V6 migration. Assertions cover the numbered scenarios below; Flyway repeats its known “8.4 newer than tested 8.1” warning. |
| Admin `npm run typecheck && npm run build` | Passed. Vite retains the existing chunk-size warning; output JS is about 1.1 MB before gzip and 343 kB gzip. |
| Miniapp `npx tsc --noEmit` | Passed. |
| Miniapp WeChat / Alipay builds | Both passed with normal macOS SystemConfiguration access (1.49 s / 1.23 s). The first restricted-sandbox attempt reproduced the native `system-configuration` NULL-object panic. Neither graphical developer tool nor a real device was run. |
| Live Phase 3 HTTP E2E | Passed on temporary port 18081 with `scripts/smoke_phase3.py`: admin catalog/inventory setup → customer order → dev simulator success → admin sees payment/task → admin creates supplier account → supplier accepts and ships one unit → admin ships remaining two → customer sees two packages and final `SHIPPED`. |
| Docker state | MySQL 8.4, Redis 7.4 and MinIO remained healthy and bound to loopback. No global proxy setting, Docker Desktop restart, unrelated container or real merchant credential was touched. The prior full-image-build proxy failure remains separately recorded above. |

## Required Phase 3 scenarios and assertions

1. **Normal confirmation:** one success changed the reservation `HELD → SOLD`, changed on-hand and reserved by exactly the ordered quantity, created one inventory movement, one task and one outbox row.
2. **Duplicate/concurrent successes:** notification-style and query-style success events raced; both serialized on order then attempt, while inventory conversion and task creation each occurred once and no false multiple-collection anomaly was created.
3. **Query result versus notification:** distinct event keys representing the two sources arrived concurrently and left one paid result and one fulfillment task.
4. **Transactional failure/retry:** an intentionally broken reserved-balance invariant threw during confirmation; order/payment event/task changes rolled back while the exact channel/event key and canonical payload remained `RECEIVED`. After repairing the balance, the recovery worker replayed that same persisted event successfully; a third identical delivery was a no-op and task/outbox counts stayed one.
5. **Payment/cancel/timeout competition:** success from `CLOSE_PENDING` wins and becomes `PAID`; real concurrent payment/cancel and cancel/timeout tests assert exactly one terminal reservation transition, zero remaining reserved quantity and task creation only for the paid outcome.
6. **Unknown close:** an `UNKNOWN` attempt makes the simulator's close/query remain unknown; cancellation leaves `CLOSE_PENDING` and retains the reservation.
7. **Success after release:** a confirmed close releases once; a later trusted success creates `SUCCESS_AFTER_RELEASE`, retains the closed/cancelled business state, changes only payment state to exception handling, and creates no fulfillment task.
8. **Identity validation:** a success with the wrong amount is persisted as rejected and does not pay the order. The same branch validates CNY currency and configured single-merchant subject.
9. **Supplier isolation:** supplier 302 cannot read supplier 301's task. Security routes also require the supplier role and every task mutation repeats the supplier predicate server-side.
10. **Partial/multi-package/idempotency/concurrency:** first package ships one of three, duplicate request key returns the same package, two concurrent over-ship attempts both fail, and the second valid package ships the remaining two before task/order become `SHIPPED`.
11. **Notification retry:** a row representing an earlier delivery failure is reclaimed and sent; the already-created fulfillment task count remains one. Worker code also has conditional claims, two-minute leases, exponential backoff and terminal `DEAD` state.
12. **Production guards:** constructors reject both the payment simulator and development consumer login when `prod` is active. WeChat/Alipay adapters return unavailable because credentials/signature implementations are absent.

## Capability boundary

- Verified locally: development simulator, MySQL payment/inventory/fulfillment transactions, supplier credentials and scoping, package visibility, in-process development reminder/outbox.
- Implemented as replaceable boundaries but not live-verified: WeChat/Alipay create/query/close/notification adapters. Their current concrete implementations deliberately return unavailable.
- Not implemented or not claimed: real signature verification, real payment/order query, refund execution, SMS/subscription messages, carrier tracking trajectory, developer-tool/device acceptance, automatic receipt, after-sales, splitting or supplier payout.

# Phase 4 verification

Executed on 2026-09-20 with local JDK 26 targeting Java 21, Node 26, MySQL 8.4 in the healthy `orchard-mall-local` project, and isolated databases `orchard_mall_phase_test`, `orchard_mall_phase4_final` and `orchard_mall_phase4_e2e`. All payment/refund results were from the explicitly enabled development simulators; no real funds, bank transfer, WeChat API or Alipay API was used.

| Check | Result |
| --- | --- |
| `backend/./mvnw test` | Passed: 22 tests, 0 failures/errors. This includes 9 Phase 4 tests plus the Phase 1–3 regressions; H2 remains only the fast compatibility signal. |
| Real MySQL `Phase3FlowTest,Phase4FlowTest` | Passed: 17 tests against MySQL 8.4 and Flyway V1–V7. The suite exercised actual InnoDB row locks, concurrent payment/close, concurrent refund occupancy and over-shipment paths, durable event recovery, receipt, refund freeze, replacement reservation and settlement uniqueness. Flyway retains its known MySQL 8.4 newer-than-tested warning. |
| Admin `npm run typecheck && npm run build` | Passed. Vite produced the existing >500 kB chunk warning (about 1.12 MB JS / 345 kB gzip). |
| Miniapp `npx tsc --noEmit` | Passed after adding receipt and after-sales pages. |
| Miniapp WeChat / Alipay builds | Both attempted. In this restricted execution surface, each Taro 4.0.9 build panicked in its macOS `system-configuration` native worker with `Attempted to create a NULL object` and then hung until terminated. This is the same environment-specific limitation recorded in prior phases; TypeScript passed, but this run does not claim either target compiled or ran in developer tools/device. |
| Two HTTP E2E chains | Passed on temporary local port 18082 against isolated MySQL/MinIO via `scripts/smoke_phase4.py`. It first reran the Phase 3 payment/split-shipment setup, then completed both required Phase 4 chains described below. The backend was stopped cleanly afterward. |
| Browser page check | Not run: the required in-app browser control runtime was not exposed in this session after following the browser skill discovery procedure. No Playwright substitute was used. Manual routes and checks are listed below. |
| Docker state | Existing project MySQL 8.4, Redis and MinIO were healthy. Only isolated test databases were added; Docker Desktop was not restarted, global proxy was not changed, and unrelated projects were untouched. The previously recorded Docker image-pull proxy issue remains separate. |

## Phase 4 scenarios and asserted outcomes

1. **Exact payment-event retry:** first processing failed inside the business transaction; the same event key/payload remained `RECEIVED`; restart-style recovery completed it; the third identical delivery produced no second inventory conversion, task or outbox.
2. **Exact refund-event retry:** deterministic failure after refund ledger mutation rolled back the business transaction but retained the same `RECEIVED` event. Recovery replayed the stored payload, created one ledger row, and a third delivery was duplicate-only.
3. **Receipt ownership/idempotency:** a different consumer received not-found; the owner submitted twice, yet only one receipt timestamp existed, the order completed once and the supply-price snapshot ledger became available.
4. **Unshipped freeze versus shipping:** applying one-unit refund increased `frozen_qty`; a package attempting all units was rejected. Approval moved only the frozen unit to `cancelled_qty`, restored exactly that original-stock quantity and reduced the frozen supplier-goods quantity/amount. Refund `UNKNOWN` retained its amount occupation and reused the same attempt/request rather than creating another refund.
5. **Shipped damage refund:** refund success and its duplicate produced one refund ledger row, left on-hand stock unchanged, kept the original order paid and did not recreate fulfillment.
6. **Replacement:** repeated approval was rejected and the unique after-sale source produced one task. Its stock was reserved first, consumed only by the replacement parcel, ended `CONSUMED`, and produced one explicit replacement-cost ledger entry without sales revenue. Consumer receipt of the replacement parcel completed the after-sale case and closed the order's separate after-sales status.
7. **Settlement:** confirmed receipt unlocked snapshot-price goods. A ledger entry could enter only one statement; a repeated payment key created one record; partial then full payment worked; reversal appended a new row and returned entries to `IN_STATEMENT`; a later adjustment appended instead of rewriting history.
8. **Account/session security:** supplier password reset deleted existing sessions, rejected the old password, and issued a new session only for the new password. Disabling the account deleted that session and rejected login; the token filter also requires matching credential version, enabled account and enabled supplier.
9. **Production guard:** refund simulator construction with `prod` active failed. Existing Phase 3 tests repeat the same guard for simulated payment and development customer login.
10. **Scope:** Phase 3 retains cross-supplier task rejection; customer order/evidence routes and supplier after-sales/ledger/detail queries all include their owner/supplier predicate. Private evidence accepts only JPEG/PNG/WebP up to 5 MiB and is returned only through authenticated, `private, no-store` routes.
11. **Concurrent refund cap:** two approved one-unit refunds were created concurrently against a two-unit captured payment. Payment-row serialization produced exactly two refund orders whose occupied sum equalled, and did not exceed, the captured amount; a third line request was rejected because the full purchased quantity was already under active after-sales.

## HTTP chain results

- Chain 1: order → simulated payment → unshipped refund request → task quantity frozen → administrator approval → frozen quantity cancelled (never shipped) → simulated refund success → original inventory restored once → supplier goods ledger quantity and amount reduced to zero.
- Chain 2: order → simulated payment → two parcels → owner confirmed each parcel → order completed → one-unit bad-fruit partial refund → simulated refund success without stock increase → available supplier ledger selected into one statement → two manual partial-payment records → statement `PAID`. The records explicitly say they are manual and bank status was not verified.

## Page entrances and manual acceptance

- Admin: `http://localhost:5173/` → “售后与退款” and “供应商结算”; existing “发货任务” includes replacement tasks. Verify review, private evidence thumbnails, refund status, ledger selection, statement and partial manual payment.
- Supplier browser workbench: `http://localhost:5173/supplier`. At a phone viewport (for example 390×844), verify login, horizontal task tables, modal form controls expanded to full width, original/replacement tasks, related after-sales and own ledger/statements. The supplier miniapp entry is not implemented.
- Consumer miniapp: order detail has per-parcel “确认收到此包裹” and per-line “申请退款 / 补发”; `/pages/after-sales/index` provides request, evidence selection and progress. Import `miniapp/dist/weapp` or `dist/alipay` only after a successful local target build, then repeat both chains. No developer-tool or real-device result is claimed here.

## Capability boundary

- Implemented and verified: local MySQL state machines; development payment/refund simulators; durable original-event retries; receipt; minimum refund/replacement; private evidence authorization; supplier browser portal; settlement ledger/statements; append-only manual payment/reversal records.
- Implemented boundary but intentionally unavailable: WeChat/Alipay payment and refund adapters without merchant credentials/signature implementations. They never fall back to simulation.
- Not implemented/verified: real fund movement, real callback signature/merchant identity validation, refund reconciliation against a provider, return shipping/exchange, automatic receipt, carrier trajectory, automatic supplier transfer/splitting, graphical miniapp tool/device acceptance and browser screenshot acceptance.

# Phase 5 stabilization verification — 2026-09-21

本机 macOS arm64、Node v26.3.1、npm 11.16.0、Taro 4.0.9、JDK 26 编译目标 Java 21。pnpm 10.33.0 可用但本项目使用 npm 锁文件；Yarn 未安装。所有新增数据仅在 `orchard_mall_phase5_source` 与 `orchard-phase5-source` 测试位置，支付/退款使用开发模拟器。项目所有文件在 Git 基线中尚未跟踪，未执行提交或推送。

| 检查 | 结果 |
| --- | --- |
| `backend/./mvnw test` | 22/22 通过，H2/Flyway V1–V7；此前 MySQL 8.4 并发套件证据保留，未重复写同断言。 |
| `admin-web/npm run typecheck && npm run build` | 通过；Vite 仍提示约 1.12 MB JS 大 chunk。 |
| `miniapp/npx tsc --noEmit && npm run build:weapp && npm run build:alipay` | 通过，本机最新代码；清理本次旧 `dist`/`.temp` 后，`dist/weapp` 与 `dist/alipay` 都含 `pages/after-sales/index`、订单详情和 app.json 页表。分别约 1.83s、1.27s。 |
| Chrome 页面 | 临时目录 `playwright-core@1.55.0` 复用本机 Chrome，不加入仓库依赖。管理员页面登录及八个主要标签可操作；供应商登录、接单、部分发货、售后/结算查看，390px 无页面级横向溢出。服务端重置密码和禁用会话均拒绝旧令牌。一个 404 资源控制台错误尚需定位；无 5xx 或敏感字符串输出。测试截图/脚本仅在被忽略的 `.artifacts/phase5`，不用于发布。 |
| HTTP E2E | 独立 MySQL/MinIO 的 `scripts/smoke_phase4.py` 两条链路通过，含商品/供货/库存/运费/图片、订单/支付/包裹、售后/退款/结算；不是小程序开发者工具验收。 |
| 备份恢复 | 停止测试后端写入，备份独立测试库/bucket 并恢复到 `orchard_mall_restore_phase5_20260921`、`orchard-restore-phase5-20260921`；SHA-256 通过，恢复库 8 单/8 支付尝试/2 退款单/1 结算单/2 付款记录/1 媒体行，恢复 bucket 1 个 68B 测试图片。备份在 `/private/tmp`，未提交。 |
| Compose | `--profile full config --quiet` 通过；MySQL、Redis、MinIO 现有容器运行，宿主绑定 loopback；新增 Redis/MinIO 健康检查、完整 dev flag 透传，未重建服务。 |
| 全容器/Nginx | 受阻：`docker compose --env-file .env -f deploy/compose.yml --profile full build backend` 的 `maven:3.9.11-eclipse-temurin-21` 与 `eclipse-temurin:21-jre` 元数据请求在 `https://auth.docker.io/token` 经 Docker Desktop 代理 `127.0.0.1:7892` 被拒绝。未创建 backend/Nginx 容器，未声称代理、刷新、私有图片通过。 |
| 微信/支付宝开发者工具/真机 | 未验证；本机没有可执行的图形开发者工具运行验收。本轮编译成功不等于平台工具或真机运行。 |

## 原生 panic 诊断及规避

- 复现命令：在 `miniapp` 运行 `npm run build:weapp`，Taro 显示版本后 Node 进程内 `tokio-runtime-worker` panic 并挂起；同样问题先前在支付宝目标发生。`RUST_BACKTRACE=full npm run build:weapp` 输出 `system-configuration-0.5.1/src/dynamic_store.rs:154:1: Attempted to create a NULL object`，栈帧 0–21 从 N-API 模块至 `__pthread_exit`。完整日志在本地忽略的 `.artifacts/phase5/miniapp-weapp-node26-backtrace.log`；仅对本次 PTY 会话发送 Ctrl-C。没有 `system-configuration` npm 包，关联组件是 `@tarojs/cli@4.0.9` build 前 `checkConfig` → `@tarojs/plugin-doctor@0.0.13` 的 N-API 二进制 → Rust `reqwest`/`system-configuration`；`@tarojs/binding` 与 Webpack 编译不是该崩溃路径。构建未访问旧输出当成果。
- Taro CLI 自带 `--no-check`（build 命令帮助说明为跳过 config validity check）。项目级四个 dev/build 脚本使用此选项，避免联网远程 Schema 校验的原生线程；保持 TypeScript 和 Webpack 构建。正式 build 脚本为每个目标设置 120 秒上限，按目标和时间戳写入被忽略的 `.artifacts/miniapp-builds`，超时仅向本脚本启动的独立子进程组发送 TERM、5 秒后 KILL。没有修改全局 Node、代理、Docker Desktop、`node_modules` 或锁文件。最初复现 panic 挂起时按 30 秒观察后仅中断本次 PTY 进程。当前本机已修复构建，未借 Linux 环境混称通过。

## 尚未签收的人工步骤

分别在微信开发者工具导入 `miniapp/dist/weapp`、支付宝小程序开发者工具导入 `miniapp/dist/alipay`；在本机开发构建时设 `TARO_APP_API_BASE=http://127.0.0.1:8080`，启动独立 dev 后端及模拟开关，按商品→规格→购物车→地址→运费→下单→开发模拟支付→包裹→收货→售后凭证→退款/补发进度操作，记录工具版本、编译、网络请求及截图。真机另用可达 HTTPS 域名和平台 request/download 白名单，不能使用本机回环或容器主机名。供应商只在浏览器 `/supplier`，无供应商小程序入口。Nginx 镜像可拉取后再验 `8088` 的 `/api`、`/supplier` 刷新、公开图片和需授权的售后图片；Vite 成功不算 Nginx 通过。

## Phase 5 收尾增量验收（2026-09-21）

- 保留既有未提交工作，仅在独立测试数据库 `orchard_mall_phase5_source`、bucket `orchard-phase5-source`、本机 API 18084 与 Vite 5174 上操作；未访问真实资金接口、未提交或推送。后端无本轮代码变更，未重跑先前已通过的全套后端测试；管理端变更后 `npm run typecheck` 与 `npm run build` 均通过。
- Chrome/Playwright-core 实际页面操作：登录后新建分类、供应商、发货地、商品、SKU、默认供货关系、库存 +10 及流水、运费模板；商品必填项提示出现，金额负值与库存小数失焦后未保持非法值。新建关系在库存页可见。供应商和发货地选项增加搜索，避免长虚拟列表无法选择新条目。测试脚本位于忽略的 `.artifacts/phase5/page-acceptance.mjs`，末次 11 项检查通过；未把脚本 HTTP 请求当作表单验收。
- 控制台 404 定位：后台首页自动请求 `http://127.0.0.1:5174/favicon.ico`，HTTP 404，不影响业务 API；加入 `/favicon.svg` 及 HTML icon 链接后，Chrome 复测商品、库存、运费、订单、支付、发货、售后、结算页无 404 控制台消息。另有 Ant Design `useForm` 未连接警告，尚未定位到用户可见故障，不列为已修复。
- Chrome 结算页面在测试结算单上追加一笔付款冲正，明细显示 `REVERSAL`；刷新页面后登记 1 分部分付款，明细显示新 `PAYMENT`。未调用银行/支付机构。测试脚本位于忽略的 `.artifacts/phase5/settlement-ui.mjs`。追加结算调整入口已实现但本轮未实操。
- 售后页面当前独立测试库呈空列表，浏览器仅确认空状态可显示；售后审核、私有凭证授权读取与补发尚未在本轮实际操作。供应商手机宽度完整接单/发货、提交中防重复、失败恢复、会话过期和无权限页面亦尚未完成本轮浏览器复验；上节既有桌面/390px、会话服务端失效证据仍有效但不能代替缺失的页面步骤。
- Docker 只读检查：上下文 `desktop-linux`；Docker HTTP/HTTPS 代理为 `http.docker.internal:3128`，宿主 HTTP/HTTPS/ALL 代理指向 `127.0.0.1:7892` 且该端口无监听。宿主经代理访问 registry/auth 报连接拒绝，宿主直连 `https://registry-1.docker.io/v2/` 得预期 401、直连 auth token 得 200。已有 `maven:3.9-eclipse-temurin-21`、`nginx:1.27-alpine`、MySQL 8.4、Redis 7.4、固定 MinIO 镜像；后端 Dockerfile 所需精确 `maven:3.9.11-eclipse-temurin-21` 与 `eclipse-temurin:21-jre` 缺失。没有更换基础镜像、改代理/全局设置、重启 Desktop 或重复拉取。因此完整 Compose/Nginx 的路由、API、图片、上传、健康检查、重启与隔离均为**未验证/受阻**，Vite 通过不计为 Nginx 通过。
- 本机 `/Applications`、用户应用目录、Spotlight 均未发现微信/支付宝小程序开发者工具或可用 CLI；最新双端产物仅编译通过，工具导入、消费者链路和真机均**未验证**。当前产物默认 API 为 `http://127.0.0.1:8080`；手机不能使用回环地址。本机活动 Wi-Fi `en0` 为 `192.168.3.78`，仅可作为同一可信局域网联调的地址候选，须先核对服务监听、防火墙和平台开发域名设置；未开放公网。
- Node 版本：项目 `package.json` 未声明 `engines`、`.nvmrc` 或 `.node-version`；README 原建议 20/22 属文档建议，实际执行版本为 Node v26.3.1、npm 11.16.0，不能把二者写成一致。`--no-check` 由 Taro CLI 实现为跳过 `checkConfig → @tarojs/plugin-doctor.validateConfig`；TypeScript、webpack 与产物检查仍执行。待原生校验组件可稳定运行、去掉参数后双端构建及配置校验通过再移除。未调整全局 Node 或锁文件。

## Phase 5 售后与移动工作台页面收尾（2026-09-21）

测试范围仍限于 `orchard_mall_phase5_source`、`orchard-phase5-source`、dev 后端 18084 和 Vite 5174。仅用开发模拟支付/退款及测试物流单号；未修改数据库状态、接真实资金、拉取 Docker 镜像或运行小程序开发者工具。业务数据准备运行 `SMOKE_API_BASE=http://127.0.0.1:18084 python3 scripts/phase5_page_fixture.py`：复用 `smoke_phase3.py` 经业务 API 建立已支付/发货订单，再由消费者业务 API 申请售后、上传 1×1 测试 PNG，另建已支付未发货订单和待供应商接单订单。有 API 准备/查询的消费者步骤**不算小程序页面验收**。仅本地忽略的 `.artifacts/phase5/page-fixture-private.json` 保存本次临时测试会话，文件权限 0600；报告和截图不含密码/令牌。可复现标识在被忽略的 `.artifacts/phase5/page-fixture.json`。

| 项目 | 实际结果与标识 |
| --- | --- |
| 未发货退款 | 订单 `OM1368DEBE4CF04348AE92A4D899D10FAA`、售后 `ASB5BC8A46475A42CEBEC364B506532F3C`：Chrome 后台审核、dev 页面模拟退款成功；业务 API 核对原履约项 `cancelledQty=1`、`frozenQty=0`、`shippedQty=0`。 |
| 已发货坏果售后与私有凭证 | 订单 `OMEDA62B49D303400ABBF99F538837E3AA`、售后 `AS0EF24D901BF94D5BB607428F195F948D`、测试凭证 ID `1`：Chrome 后台打开授权图片预览、审核 500 分部分退款并从 dev 页面模拟成功；管理员凭证 HTTP 200，匿名 401/403、无关消费者/供应商 404。权限状态用业务 HTTP 诊断，不冒充浏览器页面。 |
| 补发 | 售后 `AS23B5BF8B4D1042F9B69F66D20847E193`：Chrome 后台审核生成补发任务；390px 供应商页面接单并录入 `LOCAL-replacement-a722ff71e6`。消费者业务 API 查到 `SHIPPED` 补发任务及该包裹，未声称小程序页面已验。 |
| 结算追加调整 | 结算单 `ST1E0D7D73752E4A9C89FFEB9FDD13EF85` 已有 100 分人工付款记录；Chrome 页面为其供应商追加 −123 分“Phase 5 正确供应商调整 a722ff71e6”，刷新后台账可见，原付款 ID 均保留。首次长列表误选了另一测试供应商，随后通过页面追加 +123 分抵消，并为目标供应商重新追加；所有记录保留、未直接改库。 |
| 390px 供应商工作台 | 账号对应测试供应商 ID `17`：Chrome 手机宽度登录、查待接原订单 `OM6FDD5EED117548D5BE978D89CD987309`、接单、分别录入数量 1 和 2 的测试包裹；再完成上述补发接单/发货，查看关联售后及结算单。页面级无横向溢出，业务 API 核对原订单 `SHIPPED`。 |

浏览器操作脚本分别为本地忽略的 `.artifacts/phase5/final-admin-ui.mjs`、`final-supplier-mobile.mjs`，结果 JSON 与脱敏截图 `final-admin-*.png`、`final-supplier-mobile-*.png` 同目录。截图只含测试数据，手机号/地址提示已遮盖。修复项：私有凭证可点开预览；仅 Vite dev 构建可见的退款模拟结果页面入口（生产构建隐藏，服务端仍有 dev 配置守卫）；结算调整供应商长列表可搜索。只运行受影响的 `admin-web npm run typecheck` 和 `npm run build`，均通过；未重跑未受影响的后端/小程序全量检查。Nginx 和开发者工具/真机仍沿用上节**未验证/受阻**状态。

## Phase 5 微信开发者工具准备（2026-09-22，服务端口开启前的历史记录）

| 检查 | 实际结果 |
| --- | --- |
| 工具与 CLI | `/Applications/wechatwebdevtools.app` 已安装，应用包版本 Stable v2.02.2608070；`Contents/MacOS/cli --help` 正常。CLI `islogin` 和 `open --project` 均明确返回“服务端口已关闭”，未成功查询登录或导入。图形应用可启动，但当前执行环境的 `screencapture` 返回 `could not create image from display`，不能据此声称模拟器页面操作成功。 |
| 项目导入配置 | 新增 `miniapp/project.config.json`，项目目录为 `miniapp`，`miniprogramRoot=dist/weapp/`，已填入用户提供的测试 AppID `wx1bd16bd6d48b3f93`；尚待扫码登录、开启工具服务端口并确认该账号的项目权限。 |
| API/商品图/上传配置 | 2026-09-22 微信构建显式使用 `TARO_APP_API_BASE=http://127.0.0.1:8080`、`TARO_APP_DEV_PAYMENT_ENABLED=true`；请求和 `uploadFile` 共用 API 基址，上传字段为 `file` 且带消费者 Bearer 会话。公开店铺和商品 API 只读返回 200，商品 9 有两种规格，公开 `/api/media/6` 返回 200 `image/png`。这些只证明本机服务和配置，不算消费者页面验收。售后上传后端限制 JPEG/PNG/WebP、单张至 5 MiB。 |
| 编译与修复 | `npx tsc --noEmit`、显式设置上述环境变量的 `npm run build:weapp` 通过。修复订单详情的开发模拟支付构建常量，产物中已是 `true`，没有运行时 `process.env` 引用；结算页从地址页返回时重新读取地址；售后申请后可再次选图上传并显示凭证张数，避免取消选图或上传失败后无法重试。 |
| 开发者工具页面 | **未验证**。CLI 服务端口关闭，当前可截取桌面，但工具窗口无法置前，且 macOS 拒绝辅助访问；没有把公开 API 检查或编译记作首页、购物车、下单、支付、包裹、售后页面通过。 |
| 真机 | **未验证**。仍缺有权限的测试 AppID、设备可达的 API 地址及相应平台合法域名配置；本机回环地址不能用于手机。未调用真实资金接口。 |

继续时先在微信开发者工具扫码登录，进入「工具 → 设置 → 安全设置」开启「服务端口」；用开发者工具导入 `miniapp` 目录，确认当前 AppID `wx1bd16bd6d48b3f93` 属于已登录账号可开发的小程序。模拟器本机联调检查项目详情的本地设置，暂时关闭合法域名校验以访问 `127.0.0.1:8080`；真机另使用设备可达的 HTTPS 开发地址和 request/uploadFile/downloadFile 域名配置。按本轮用户指定顺序逐页操作、记录网络请求和截图，再分别记录工具与真机结果。后台和供应商页面无需重复验收，除非后续变更影响它们。

## Phase 5 微信小程序页面实操（2026-09-22，最新记录）

本节覆盖上节服务端口关闭时的状态。微信开发者工具 Stable v2.02.2608070 已登录，CLI `open` 和 `auto` 已把 `miniapp` 项目导入并开启自动化；`miniprogramRoot=dist/weapp/`、AppID `wx1bd16bd6d48b3f93`。工具的可管理 AppID 列表确实包含该 AppID。本地项目设置的合法域名校验关闭。隔离 dev 后端运行在 `*:18084`，数据库 `orchard_mall_phase5_source`、对象 bucket `orchard-phase5-source`；所有订单使用开发模拟支付、退款。没有真实资金操作，也没有重验后台或供应商页面。`scripts/phase5_weapp_catalog_fixture.py` 只通过管理员 API 准备商品；`scripts/phase5_weapp_ship_fixture.py`、`scripts/phase5_weapp_after_sales_admin.py` 只通过管理员 API 执行审核/发货。消费者购买和售后步骤均通过小程序页面操作，没有用消费者 API 测试代替。

| 环境 | 实际结果 |
| --- | --- |
| 开发者工具模拟器 | **通过以下实操链路**。首页显示已上架商品，商品详情选择第二规格（加购袋，¥10.00）并加入购物车；首次未登录时跳转开发登录再回商品页。购物车数量 1；地址页实际填写并保存测试地址；返回结算页刷新地址，显示商品 ¥10.00、运费 ¥7.00、应付 ¥17.00、计费重量 300g。页面创建订单 12，使用“模拟支付成功（仅开发）”变为已支付；管理员 API 录入包裹后，页面显示测试单号 `LOCAL-WEAPP-12`，点击确认收货，订单变为已完成。由订单详情进入退款申请页、提交坏果退款；首次选图路径失效时页面保留申请，随后从小程序文件系统重试上传 1 张 PNG 凭证，详情显示 1 张。管理员 API 审核并执行开发模拟退款后，页面显示售后已完成、退款成功。另通过页面创建并模拟支付订单 13，选择补发并上传 1 张 PNG 凭证；管理员 API 审核/录入补发包裹后，售后页显示补发处理中、任务已发货，订单页显示补发包裹 `LOCAL-WEAPP-13-15`；页面点击确认收货后，售后显示已完成。 |
| 真机 | **待实际结果**。最新构建已改用 `TARO_APP_API_BASE=http://192.168.3.78:18084` 和 `TARO_APP_DEV_PAYMENT_ENABLED=true`，已生成微信预览码；手机扫码、页面请求、商品图及凭证上传尚未得到实际结果。`192.168.3.78` 是此时电脑的局域网地址，地址改变需重建。项目本地设置不能代替真机合法域名配置或生产 HTTPS 验收。 |

实操中发现并修复：订单详情开发支付按钮原先的运行时环境变量在小程序中无效，改为构建常量；结算页从地址页返回后不刷新，改为 `useDidShow` 重新读取；商品规格与地址输入缺少稳定选择器，补上唯一 ID；订单列表长订单号挤压状态和卡片，改为状态独立一行并在模拟器截图复核；售后选择图片取消或上传失败后无法继续，改为保留售后单及继续上传入口、显示凭证张数；订单和售后状态由英文代码改为中文显示。最后又调整了已确认收货的订单列表状态为“已收货”，并在打开售后详情后同时刷新列表；这两处已通过 TypeScript 和微信构建，但开启真机调试后模拟器截图接口超时，尚未再次截图复核。模拟器网络请求中的会话令牌未写入报告。

验证：最后一次改动后 `npx tsc --noEmit` 通过；`TARO_APP_API_BASE=http://192.168.3.78:18084 TARO_APP_DEV_PAYMENT_ENABLED=true npm run build:weapp` 通过，并重新生成最新预览码。此前 LAN 构建在模拟器刷新后首页显示 3 个商品，错误/异常 console 过滤结果为空；宿主机请求 `http://192.168.3.78:18084/api/public/store` 返回 200。测试商品卡片的白色图片区使用 1×1 白色 PNG，不能据此证明真实商品图的视觉效果。后端、后台及供应商未因本轮小程序代码变动重跑验收。真机需要有该 AppID 开发或体验权限的微信扫描最新临时预览码，手机与电脑连同一可信局域网，先核对首页商品和图片，再按表中页面顺序操作并单独记录实际结果。

## 开发库 V8 升级（2026-09-23）

- 实际运行目标经进程工作目录、进程环境、TCP 连接和 MySQL 会话四处核对为 `dev` profile、`127.0.0.1:3306/orchard_mall_phase5_source`；连接用户脱敏记录为 `orchard`，未输出口令。源库实际已是 V7，并非此前估计的 V5；V6、V7 均于 2026-09-21 成功安装，本次正式升级只执行 V8。
- 停止本项目后端及定时任务后，逻辑备份保存为 `backups/orchard_mall_phase5_source_pre_v8_20260923T183257+0800.sql.gz`，SHA-256 为 `00cee38eb705875682f08738a3d30cb65989e2eb665e35b3256c98227ce58427`。备份已恢复到独立 MySQL 8.4 数据库并由当前应用完成 V8；预演后供货、库存、预占、订单、支付、履约和旧订单快照逐项比对均为 0 差异。
- V8 不使用当前地址倒填历史订单新快照字段。15 个历史订单组的区县编码和联系人字段保持 `NULL`，并记录 `HISTORICAL_ORIGIN_DETAIL_UNAVAILABLE`。9 条无运费模板的供货关系记录 `MISSING_SHIPPING_TEMPLATE`，试算返回“运费模板未配置”，不会进入履约分组。
- 正式 Flyway 已到 V8，checksum `-1727063901`；后端恢复在 `http://127.0.0.1:18084`，当时管理端 Vite 使用 `http://127.0.0.1:5173`（历史接口记录；当前后台入口为 `http://localhost:5173`）。管理员登录、供应商/发货地查询、两个发货地及默认设置、跨供应商绑定拒绝、供货关系保存、库存调整、完整运费试算和缺模板试算均经 HTTP 验证。隔离验收记录随后按精确 ID 删除，业务基线计数恢复。
- 页面实操未执行：本次会话没有提供 browser 技能所要求的内置浏览器控制运行时，未以独立 Playwright 或类型检查替代页面验收。

## 2026-09-26 — 参数错误与固定 DEV 验收商品

- 运行中的 18084 Java 进程是本项目 `dev` profile，数据库 URL 为 `127.0.0.1:3306/orchard_mall_phase5_source`。对该库 `flyway_schema_history` 实查最新版本 `8`、`success=1`。当时的 `http://127.0.0.1:5173/api/public/store`（只读代理验证；当前后台入口为 `http://localhost:5173`）经 Vite 代理返回了与 18084 一致的店铺响应；对 `5173/api/public/products/15` 实际请求返回 200。微信和支付宝小程序本轮重新构建后，产物 `dist/*/common.js` 的请求及上传基址均为 `http://127.0.0.1:18084`；对该地址的公开商品与报价 API 已实际请求。没有把产物静态检查或 API 调用冒充小程序页面点击/真机请求；回环地址仅适用于本机模拟器，真机须按当时局域网/正式域名重建。
- 管理员 `shippingTemplateId:null` 请求的实际响应为 HTTP 400、`VALIDATION_ERROR`、`请求参数校验失败` 和字段 `shippingTemplateId:不能为空`。同一路径匿名请求实际为 401 `UNAUTHORIZED`，消费者令牌实际为 403 `FORBIDDEN`，对专用供货关系提交内容不变的合法管理员 PUT 为 200。`CatalogFlowTest#supplyValidationKeepsAuthenticationAndAuthorizationSemantics` 定向测试通过。当前运行代码的 `ApiErrors` 已捕获 `MethodArgumentNotValidException` 并直接返回 400；旧“空正文 403”在本轮无法再次复现，因此无法从当前运行状态证明旧构建的唯一根因。链路检查显示，未被处理的 MVC 异常若转发到 `/error`，会遇到 Security 的 `anyRequest().denyAll()`；这是旧现象的可能覆盖路径。本轮未放宽任何业务路由。管理端 API 错误提示现附带后端 `fields` 信息，不再把参数错误显示成统一“无权限”。
- 固定验收数据复用开发库原有记录：商品 `DEV验收苹果` **15**（已上架，分类 **1**、图片 **3**）；SKU `DEV-ACCEPT-APPLE-5JIN` **16**（5斤装、售价 **1234 分**、展示/计费重 2500g）；供应商 `DEV验收供应商（测试）` **22**，发货地 `DEV验收发货地（测试）` **19**，默认供货关系 **15**（测试供货价 **800 分**）；独立模板 `DEV验收北京固定运费700分（测试）` **5**、规则 **4**。模板没有包邮门槛，仅 `110000` 北京可配送，首重上限 1000000g 收 **700 分**、续重费用 0；其他地区因无匹配规则不可配送。该规则在现有运费模型内实现固定 700 分，验证范围为这套 5斤装验收 SKU。所有价格、运费、联系人和地址均为开发测试值，不代表真实经营。
- 供货库存经管理 API 核对 `onHandQty=10`、`reservedQty=0`、`availableQty=10`。已有开发消费者会话通过业务 API 报价：北京测试地址 **14** + 购物车项 **19** 得商品 1234 分、运费 700 分、应付 **1934 分**、供货关系 15 / 模板 5；上海测试地址 **15** 的报价 `purchasable=false`，原因“配送地区未配置”。公开商品 API 200 且仅返回 SKU 16。未创建订单、调用支付或真实资金接口。
- 为纠正本轮对已有 5斤装 SKU 的识别错误，误建的 SKU 17、供货关系 16、模板 6、供应商 23、发货地 20、分类 22 及其专用规则/库存流水已在确认无订单、预占引用后按精确 ID 清理；相关测试购物车项通过业务 API 删除。最终 SQL 实查这些重复 ID 均不存在，原有 9 条缺模板供货关系仍为 9，模板 1–4 和历史订单未改动。原固定套件保留供后续使用，准确 ID 见上段；不加入正式环境自动种子。
- 本轮通过管理端 `npm run typecheck && npm run build`、微信/支付宝两个 Taro 构建。当前后端 `DEV_CONSUMER_LOGIN_ENABLED` 未开启，新的小程序测试账号登录会返回 404；已有开发消费者会话仍有效。页面点击、真机和资金流程本轮未验证。

## 2026-09-28 — 新开发消费者登录、购物车与试算

- 本节更新上节的运行状态。18084 监听进程的工作目录是本项目 `backend`，进程环境实查 `SPRING_PROFILES_ACTIVE=dev`、`DEV_CONSUMER_LOGIN_ENABLED=true`、`SERVER_PORT=18084`，数据库为隔离开发库 `orchard_mall_phase5_source`。根目录 `.env` 也配置为开启，但判断实际状态以进程环境和 HTTP 响应为准。未重启后端，未修改数据库结构或固定商品。
- 微信和支付宝当前构建产物的 API 基址均为 `http://127.0.0.1:18084`，登录调用 `POST http://127.0.0.1:18084/api/dev/consumer-login`，正文示例为 `{"platform":"WECHAT","externalUserId":"dev404_probe_20260928"}`。直接请求该 URL 实得 HTTP 200，响应为 `{"consumerId":15,"token":"[REDACTED]","platform":"WECHAT"}`。再次登录仍为消费者 15，发放新会话；同标识的支付宝登录得到独立消费者。此前 404 无法在当前已开启的进程上复现；上一节记录当时开关关闭，而控制器在关闭时明确主动返回 404。当前证据排除了路径、方法、客户端基址和路由缺失，不能将当前 200 冒称为旧 404 的原始响应正文。
- 新微信消费者 15 可读公开商品 15/SKU 16；购物车项 21 保留一件 SKU 16，北京地址 18 保留供页面验收。北京试算商品 1234 分、运费 700 分、合计 1934 分；临时上海地址试算不可配送后已删除。另一平台消费者的地址列表、购物车列表和订单列表不含该消费者记录；修改其地址/购物车及用其地址和购物车试算均被拒绝。新微信消费者的订单列表为空，读取既有其他消费者订单 12、13 均被拒绝。匿名请求管理供货接口被拒绝；认证后只读核对供货关系 15 库存 `onHandQty=10`、`reservedQty=0`、`availableQty=10`。
- 微信开发者工具 Stable 的 CLI 已登录并开启自动化端口。清除本地消费者会话后，模拟器实际打开登录页、输入上述固定身份并点击登录，进入购物车显示 DEV验收苹果一件；点击结算进入确认订单页，显示 ¥12.34、¥7.00、¥19.34。未点击创建订单。`npx tsc --noEmit`、本机基址的微信与支付宝构建通过。页面 404 提示已改为说明接口返回 404，并引导核对 dev/test 实例和模拟登录开关；失败提示同时保留在页面及 Toast。未运行其他阶段测试、未调用资金接口、未提交或推送。

## 2026-09-28 — 微信正常交易链路继续验收

- 执行前实查 18084 属于本项目后端，运行 `dev` profile、隔离库 `orchard_mall_phase5_source`；消费者 15 的订单列表为空，购物车仅一件商品 15/SKU 16，北京地址 18 仍可用。默认供货关系 15 属于供应商 22、发货地 19，报价为 1234 + 700 = 1934 分，初始库存现货 10、预占 0、可售 10。原进程的开发模拟支付开关为关闭；只重启该项目的 18084 后端并在同一隔离配置下显式开启 `DEV_PAYMENT_ENABLED=true`，`DEV_REFUND_ENABLED=false`，未重置数据库或库存。微信小程序仅重新构建为本机 API 基址并显示开发模拟支付入口，构建通过。
- 微信开发者工具模拟器实际从购物车点击结算，页面核对 DEV验收苹果一件、北京测试地址、商品 ¥12.34、运费 ¥7.00、合计 ¥19.34；只点击一次创建订单，跳转订单 14，订单号 **`OM68210EA27F524DBFB99E7516764DA2D0`**。详情页显示待支付、未支付及服务端金额。此时管理 API 只读核对库存为现货 10、预占 1、可售 9。
- 在同一微信订单详情页点击一次“模拟支付成功（仅开发）”，页面显示已支付、待发货；随后后端只读核对订单状态 `PAID`、支付状态 `PAID`、履约状态 `PENDING`，唯一支付记录金额 1934 分，库存为现货 9、预占 0、可售 9。管理 API 查到同一订单仅一条供应商 22 的原订单发货任务 16，状态 `PENDING_ACCEPTANCE`、应发一件、无包裹。消费者包裹列表为空，没有展示物流轨迹。
- 管理后台浏览器页面以 `http://localhost:5173` 登录（当前后端 `ADMIN_ORIGIN` 与此一致；用 `127.0.0.1:5173` 会遭 CORS 403），订单页核对同一订单金额和状态，发货任务页核对唯一供应商 22 原订单任务 16。供应商 22 原本无账号，已通过后台页面创建其工作台账号；凭据仅本地保存。浏览器密码自动填充曾造成四条误建账号（4、7、8、9），均通过后台页面停用并以只读 API 确认；没有改动供应商、商品或订单。账号表单增加了自动填充提示，但浏览器仍可能忽略，后续创建账号须逐字段核对。
- 供应商浏览器工作台页面登录后仅看到该供应商对应的任务，打开任务 16 并点击接单；为 SKU 16 一件录入一个包裹，快递代码 `DEV_TEST`，名称“开发测试物流（无真实承运）”，单号 `DEV-OM14-20260928`。没有调用真实物流下单或轨迹接口。只读后台核对唯一支付记录 1934 分、唯一任务 16、包裹一件且创建身份为供应商；发货后库存仍为现货 9、预占 0、可售 9。
- 微信开发者工具模拟器重新打开订单 14 详情，实际核对商品、包裹、测试单号及“尚未接入物流轨迹服务，不展示虚构轨迹”，随后在页面点击一次“确认收到此包裹”。页面显示已收货；只读后台核对订单 `status=PAID`、`paymentStatus=PAID`、`fulfillmentStatus=SHIPPED`、`completionStatus=COMPLETED`，包裹有收货时间，库存仍为现货 9、预占 0、可售 9。订单保留供售后页面验收，未申请售后、调用真实支付/退款或物流下单、提交或推送。微信开发者工具模拟器结果不算真机验收；支付宝页面仍未验收。本轮管理端 typecheck 与 build、微信小程序受影响构建通过。

## 2026-09-30 — 订单 14 坏果售后页面验收

- 当前本地入口统一为管理后台 `http://localhost:5173`、后端 `http://127.0.0.1:18084`。后端 `ADMIN_ORIGIN=http://localhost:5173` 精确匹配；历史章节出现的 `http://127.0.0.1:5173` 是当时的环境记录，不能作为当前入口。18084 使用 `dev` profile、隔离库 `orchard_mall_phase5_source`，本轮只为页面退款重启并开启 `DEV_REFUND_ENABLED=true`。未调用真实资金。
- 执行前只读核对订单 14 / 消费者 15 / SKU 16 / 供应商 22 / 原任务 16：订单已收货、支付 1934 分、商品 1234 分、运费 700 分，订单没有售后或退款；误建账号 4、7、8、9 均停用。微信开发者工具模拟器从订单详情实际点击“申请退款 / 补发”，选择坏果并提交一次，生成售后单 **`ASF4EF175ED5AC45A8AF68A2E1AAF368C3`**（ID 8）。
- 上传时发现 `Taro.chooseMedia` 在当前微信模拟器报错，改用 `Taro.chooseImage` 后仅重建微信目标，回到**同一售后单**点击“继续上传凭证”。自动化将无个人信息的 68 字节、1×1 PNG 放入模拟器文件系统并为系统选图结果提供该路径；页面实际执行上传并显示凭证 1 张。后台售后审核页显示对应订单和 SKU；私有图片经授权加载为 1×1 的 Blob，并实际点击“查看图片”打开预览。
- 此单提交时的消费者页面按申请数量默认计算整件商品金额，**当时没有输入申请金额的控件**；因此本次原始申请记录为 **1234 分**，未能在消费者提交时填入指定的 500 分。不能将核准额冒称消费者申请额，也不改写已完成申请的审计历史。随后已给申请页增加明确的商品退款分金额输入和后端上限校验；微信模拟器仅验证输入框可把 1234 改为 500，未重复提交本单。后台页面把本单核准额设为 **500 分**、运费退款 **0 分**、责任 `MERCHANT`、供应商扣款 **0 分**，选择 `DEV_SIMULATOR` 审核；在“开发模拟退款结果”页只点击一次“模拟成功”。退款单 **`RFEFF10D3088F94E14BE35FB7A8F5F22E1`** 显示 `SUCCEEDED`、500 分。微信模拟器刷新后显示售后已完成、核准 ¥5.00、该退款单 ¥5.00“已退款”。
- 只读数据库核对：原支付单唯一且仍为 1934 分；订单 14 只有这一笔成功的 500 分退款、一条 500 分退款账务及一条已处理退款事件，净收款 **1434 分**。商品财务余额按 1234−500 = **734 分**，运费 700 分独立且未退；现有售后按商品数量限制再次申请同一件，不把此余额写成已验证可在页面再次申请。库存供货关系 15 为现货 **9**、预占 **0**、可售 **9**。原包裹 11、任务 16、测试单号及 `received_at=2026-09-28 13:47:56` 均保留。订单状态 `PAID`、履约 `SHIPPED`、收货 `COMPLETED`、独立售后状态 `CLOSED`；微信详情页已分开展示“已收货”和“售后已结束”。供应商 22 台账仅有原商品 800 分、运费 700 分两条，没有新增售后扣款，遵循人工确认的责任 `MERCHANT`。
- 账号表单改为新建时清空旧值、供应商无默认选择、必选校验、提交前显示供应商名称与 ID、用户名、固定供应商角色及启用状态；密码不显示。确认基于同一份实际提交载荷，内容变化后需重新确认，提交中锁定。Chrome 页面实测空供应商拦截、选择供应商 22 后确认内容、修改用户名后重新确认；**没有点击最终提交，也没有创建测试账号**。服务端管理员权限、供应商外键归属和用户名唯一约束保持。账号 4、7、8、9 仍停用且未删除。
- 受影响验证：管理端 `npm run typecheck` 与 `npm run build` 通过；小程序 `npx tsc --noEmit` 与本机 API 基址的 `npm run build:weapp` 通过；后端定向 `Phase4FlowTest#refundApplicationUsesExplicitProductAmountWithinCapturedLinePrice` 和原有 `#shippedDamageRefundNeverRestoresInventoryAndDuplicateSuccessBooksOnce` 通过。未重跑无关后端全套测试或支付宝构建。微信开发者工具模拟器页面结果不是真机或支付宝验收；未提交或推送。

## 2026-09-30 — 售后元金额与一次申请限制定向验收

- 执行前只读核对隔离开发库：仅有历史订单 14 和其已完成售后/退款，没有本轮可继续的新订单或申请；供货关系 15 库存现货 9、预占 0、可售 9。订单 14 与原售后、退款、审计记录未修改，也未追加退款。店铺配置的 `contact_phone` 为 `NULL`，不能展示未经配置的号码。
- 小程序申请页改为**元**输入，用字符串语法精确转换为整数**分**：`5` 和 `5.00` 均为 500 分；`0`、负数、超过两位小数和非法文本直接拒绝，不四舍五入。可申请数量、单位实付价及当前金额上限从带消费者鉴权的新只读接口取得，不再使用页面 URL 的金额上限；服务端在提交事务内重新校验数量和商品可退余额。审核端独立限制到申请额、已收款余额、商品余额及运费余额。
- 通过**业务 API 准备**唯一新订单 15（`OMA69514429F6B475896F83CBDB514B98F`）：复用消费者 15、北京地址 18、商品 15/SKU 16、供应商 22、供货关系 15 及既有运费模板；API 购物车、报价、创建订单、开发模拟支付、管理员录入开发测试包裹 12、消费者确认收货。商品 1234 分、运费 700 分、实付 1934 分。上述准备步骤不是小程序页面验收，也没有 API 提交售后申请。预留固定幂等键，重复运行先查现有订单，未批量建数据。
- 微信开发者工具模拟器**实际页面点击**订单 15 的售后入口，页面从服务端显示本次最多 ¥12.34；分别输入 `0`、`-1`、`5.001`、`abc` 并点击提交，均未生成申请；输入 `5` 验证输入值，再以 `5.00` 元选坏果提交一次，使用模拟器文件系统中的无个人信息 1×1 PNG 经页面上传。售后单 **`AS88E7A4DDEA5E42779271CCC6426841AD`**（ID 9）页面显示申请 ¥5.00、凭证 1 张；只读数据库记录的申请额为 **500 分**。
- 后台浏览器页面显示同一订单与售后申请 **¥5.00**，私有图片经鉴权加载；页面核准商品退款 **500 分**、运费 **0 分**、供应商扣款 **0 分**，随后只在“开发模拟退款结果”页点击一次模拟成功。退款单 **`RF2AFEB56612D14F948C5000D359824EEA`** 为 `SUCCEEDED`、500 分。微信模拟器刷新后同时显示申请 ¥5.00、核准 ¥5.00、退款 ¥5.00“已退款”；原包裹和已收货状态仍在。
- 新订单仅一笔售后、一笔成功退款、一条 500 分退款账务。库存从准备前 **9/0/9** 变为付款后 **8/0/8**，库存流水仅有预占 `+1` 和支付转已售 `−1/−1`；已发货售后没有回补。准备脚本第一次查询误用 MySQL 事务旧快照，曾读到 9/0；独立只读查询与库存流水确认实际为 8/0，已修正本地 manifest，未直接改库。订单 14 仍保留原一笔 500 分成功退款。
- 再次申请：订单 14 的服务端范围返回 `remainingQty=0`、`maxRefundFen=0` 和“该商品已申请过售后；当前每件商品仅支持一次售后申请”。微信页面点击售后入口实际显示该原因、隐藏提交按钮，**没有把 734 分财务余额显示为可再次申请额度**。由于开发库店铺联系电话未配置，页面如实显示“店铺尚未配置联系电话”；配置号码后接口会返回并展示该号码，不虚构客服入口。
- 受影响验证：小程序金额解析的两项 Node 测试及 TypeScript 检查、微信构建通过；后端 `Phase4FlowTest#refundApplicationUsesExplicitProductAmountWithinCapturedLinePrice`、`#reviewCannotExceedRequestedAmountOrCurrentCollectedBalance` 定向测试通过。没有运行无关构建或全量测试；没有真实资金、提交或推送。这是微信开发者工具模拟器验收，不是真机或支付宝验收。

## 2026-09-30 — 微信真机验收准备（等待手机反馈）

- 执行前和准备后只读复核：订单 14 `OM68210EA27F524DBFB99E7516764DA2D0`、订单 15 `OMA69514429F6B475896F83CBDB514B98F` 均属消费者 15、已付款和收货，各保留原售后、成功退款 500 分；供货关系 15 的库存为现货 **8**、预占 **0**、可售 **8**。没有创建订单、售后、退款或调整库存。固定微信开发身份 `dev404_probe_20260928` 仍对应消费者 15；北京默认地址 18 仍在，购物车当前为空，因此手机若要试算须从商品 15 的 SKU 16 加入一件购物车，停在 ¥19.34 确认页，**不要点击“创建待支付订单”**。
- 当前默认路由走 `en0`，电脑局域网地址 `192.168.1.8/24`，网关 `192.168.1.1`。本项目 Java 进程监听 `*:18084`；Docker 的 MySQL 3306、Redis 6379、MinIO 9000/9001 仍只绑定 `127.0.0.1`。未改全局代理、CORS 或正式环境配置，也未发布公网入口。
- 商品元数据经 `http://192.168.1.8:18084/api/public/products/15` 返回 200，包含图片 `/api/media/3`。首次访问图片返回 401，定位到商品 15 的现有 68 字节测试 PNG 还在旧 `orchard-phase5-source` bucket，而当前后端读取 `orchard-media`。只将这一现有对象按原 key 复制到当前 bucket，未改数据库引用；复测 `http://192.168.1.8:18084/api/media/3` 返回 200、`image/png`、68 字节。该图片本身是 1×1 测试图，不代表真实商品视觉验收。
- 订单 14、15 的私有凭证在当前 bucket；复用消费者 15 的开发登录后，经局域网鉴权读取 `/api/customer/after-sales/evidence/5` 和 `/api/customer/after-sales/evidence/6` 均返回 200、`image/png`、68 字节。小程序的 `Taro.request`、商品图片 `asset()` 和 `Taro.uploadFile` 共用 `API_BASE`；本阶段没有在手机上传文件或发起售后。
- 只重建微信目标：`TARO_APP_API_BASE=http://192.168.1.8:18084 TARO_APP_DEV_PAYMENT_ENABLED=false npm run build:weapp` 成功；实际 `dist/weapp/common.js` 只有 `http://192.168.1.8:18084`，无 `127.0.0.1:18084`。微信开发者工具 CLI 使用项目 AppID `wx1bd16bd6d48b3f93` 生成临时[预览码](../.artifacts/phase5/weapp-lan-preview-20260930.jpg)，包大小 342001 字节。
- **真机结果待用户反馈**：电脑经局域网地址访问成功及开发者工具生成预览码，均不能证明手机能访问。用户需用有 AppID 权限的微信账号扫码，连接与电脑相同的可信局域网，依次验证开发登录、商品图、规格、购物车、北京地址、1934 分试算、订单 14/15 与退款进度；记录每一步手机画面和报错。项目本地 `urlCheck=false` 仅供开发调试，手机预览若仍受 HTTP/IP 合法域名限制，须在开发者工具使用“真机调试”取得实际请求错误；本地设置不等于正式上线配置。正式环境仍需 HTTPS 和平台合法域名，不能沿用本次 IP/HTTP 地址。
