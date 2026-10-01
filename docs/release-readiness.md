# Phase 5 本地交付与上线准备（更新至 2026-09-22）

本文件区分开发完成、本地验证和正式上线。所有支付、退款、付款演练仅使用测试数据、开发模拟器或未核验的人工登记，不接真实资金。

| 项目 | 状态 | 证据/限制 |
| --- | --- | --- |
| 微信、支付宝最新小程序编译 | 已实现且已验证 | macOS Node 26/npm 11、Taro 4.0.9 双端重新编译成功；清理旧 `dist` 后，产物均包含 `pages/after-sales/index` 和订单详情。`--no-check` 跳过 Taro 构建前 `plugin-doctor.validateConfig` 配置有效性检查；不跳过 TypeScript/webpack 编译。 |
| 管理后台登录、主要页面 | 已实现但未完整验证 | Chrome 实际完成分类、供应商、发货地、商品、SKU、供货关系、库存、运费模板；本轮又完成未发货退款、坏果凭证预览及部分退款、补发审核、结算追加调整。失败恢复、会话过期和完整运费规则/绑定仍未完成浏览器操作。 |
| 供应商桌面/390px 工作台 | 已实现且本轮链路已验证 | 390px 页面实际登录、查任务、接单、原订单两包发货、补发任务接单与包裹录入、关联售后和结算查看；无页面级横向溢出。表格自身可横向滚动；服务端重置密码/禁用旧会话的既有验收仍有效。 |
| 微信开发者工具模拟器 | 页面链路已验证 | Stable v2.02.2608070 已登录并导入 AppID `wx1bd16bd6d48b3f93` 的项目。模拟器实操通过商品规格、购物车、地址、运费、下单、开发模拟支付、包裹收货、退款凭证与进度，以及补发凭证、进度和包裹收货；见 `docs/verification.md`。供应商交付仍为浏览器工作台。 |
| 微信真机 | 预览码已生成，页面未验证 | 最新构建指向本机当时的 LAN 地址 `192.168.3.78:18084`，使用隔离 dev 后端和模拟资金开关。待有该 AppID 权限的微信扫码并按实际页面结果签收；局域网 IP 与开发域名设置仅供测试，不构成生产域名验收。 |
| Nginx 全容器入口、路由刷新、代理与私有图片 | 已实现但未验证 | Compose/Nginx 静态检查通过；镜像拉取在 Docker Hub OAuth token 经本机失效代理 `127.0.0.1:7892` 时失败；Vite 成功不替代 Nginx 结果。 |
| MySQL/MinIO 备份恢复 | 已实现且已验证 | 停写维护窗口备份、校验和、独立测试库/bucket 恢复及订单/支付/退款/结算/图片核对通过；生产恢复、定时调度及异地备份未验证。 |
| 原支付/退款事件原样重投 | 已实现且已验证 | `Phase3FlowTest`、`Phase4FlowTest` 的 `RECEIVED` 恢复与重复事件幂等断言，MySQL 8.4 既有集成验收记录。 |
| 退款 UNKNOWN 保留占用 | 已实现且已验证 | `Phase4FlowTest` 对同一退款尝试和余额占用的断言。 |
| 发货/售后冻结并发互斥 | 已实现且已验证 | MySQL 8.4 并发/锁测试及 HTTP 冻结后拒绝超量发货链路。 |
| 已付款结算追加调整、冲正 | 已实现且已验证 | `Phase4FlowTest` 追加行与部分付款/冲正；Chrome 已实际冲正、登记 1 分部分付款，并在已有付款记录的独立测试结算数据上追加 −123 分调整。刷新后原因和金额可见，原付款历史 ID 保留。 |
| 正式配置拒绝模拟能力 | 已实现且已验证 | 现有三个启动守卫测试分别覆盖开发登录、模拟支付与模拟退款在 `prod` 下拒绝；正式配置与真实渠道尚未上线验证。 |
| 真实微信/支付宝登录、支付、退款、对账 | 需要外部资料 | 需商户/小程序主体、商户号/应用 ID、回调与请求域名、证书/密钥保管方案、沙箱/验签样例、退款权限与账单格式；接入 `ConsumerAuth`、`PaymentAdapter`、`RefundAdapter` 及回调/对账任务。不得把凭据写入代码或本文。 |
| 真实通知与物流轨迹 | 需要外部资料 | 需短信/订阅消息服务资质、模板与回执协议，以及承运商接口/回调协议；接入 notification outbox adapter 和物流事件查询/订阅。 |
| 退货、换货、自动分账 | 尚未实现 | 不属于本轮；自动资金拆分须单独产品决策。 |

## 本地备份和恢复演练

1. 切换维护窗口，停止本部署所有 API、outbox 和支付/退款事件恢复 worker 的写入，确认无在途数据库事务；MySQL 与 MinIO 保持运行。第一版用停写保证 SQL 快照与对象文件属于同一业务切面，`mysqldump --single-transaction` 仅保证数据库内部一致性，不能单独保证对象一致性。
2. 使用仓库外、新建且权限受控的绝对目录：`scripts/backup_local.sh /private/tmp/orchard-backup-YYYYMMDD`。默认备份 `.env` 的数据库和 bucket；演练可用 `BACKUP_DB`、`BACKUP_BUCKET` 只选择独立测试源。备份含订单地址、账号哈希和私有凭证对象，应按个人数据处理、加密离线保管并限制访问；不得提交 Git。
3. 目标必须是全新独立名字：`scripts/restore_local_test.sh /private/tmp/orchard-backup-YYYYMMDD orchard_mall_restore_drill1 orchard-restore-drill1`。脚本拒绝已有数据库/bucket，不执行 DROP 或覆盖。校验 SHA-256 后导入 SQL 与对象。任何失败都先人工检查独立目标，不复用同名重试。
4. 恢复演练中不要启动恢复库的 Web/worker；如确需只读启动，必须关闭开发模拟登录/支付/退款、外部通知与资金适配器、支付/退款/outbox/超时任务，并阻断对外网络写调用。当前应用未提供统一只读恢复模式，因此不能直接启动恢复副本声称安全。用 SQL 只读核对 `sales_order`、`payment_attempt`/`payment_event`、`refund_order`/`refund_ledger`、`settlement_statement`/`settlement_payment_record` 行数及关联 ID，用 MinIO `mc ls` 和哈希核对商品与私有售后对象；人工抽样在隔离副本中核对关联键。演练完保留或由数据管理员按保留策略清理，不自动删除。

## 尚需完成的验收

- 微信开发者工具已登录并导入 `/Users/wenjun/data/project/business/orchard-mall/miniapp`，`project.config.json` 指向 `dist/weapp/`，AppID 为 `wx1bd16bd6d48b3f93`，服务端口已开启。模拟器已走完主要购物、收货、退款及补发页面。真机预览码已生成，请用有该 AppID 开发或体验权限的微信扫码；手机与电脑连同一可信局域网，先确认首页商品和图片，再按同一顺序实际点选并记录结果。最新产物使用 `TARO_APP_API_BASE=http://192.168.3.78:18084` 和开发模拟支付开关；IP 改变须重建。真机不能用 localhost（指向手机自身）；正式上线仍须 HTTPS、平台 request/uploadFile/downloadFile 合法域名和真实主体，不把关闭域名校验作为上线方案。不擅自开放公网。支付宝工具仍导入 `/Users/wenjun/data/project/business/orchard-mall/miniapp/dist/alipay`。
- Docker 当前 `desktop-linux` 上 HTTP/HTTPS 代理指向 Docker Desktop 内部 `http.docker.internal:3128`，其转发的宿主代理 `127.0.0.1:7892` 未监听；宿主经该代理访问镜像仓库连接失败，而宿主直连 registry `/v2/` 得到预期 401 挑战、auth token 得到 200。已有 `nginx:1.27-alpine`、MySQL、Redis、MinIO 镜像，但后端 Dockerfile 所需精确基础镜像 `maven:3.9.11-eclipse-temurin-21` 与 `eclipse-temurin:21-jre` 缺失，不能把近似本地镜像混用作正式验收。由环境负责人在 Docker Desktop **Settings → Resources → Proxies** 检查 HTTP/HTTPS 代理来源，停止指向失效的 7892 转发；若当前网络允许直连，选择 Docker Desktop 的无代理/直连配置。先用 `docker info` 核对生效值，再分别 `docker pull maven:3.9.11-eclipse-temurin-21`、`docker pull eclipse-temurin:21-jre` 验证；成功后运行 `docker compose --env-file .env -f deploy/compose.yml --profile full up -d --build`。本轮没有修改全局代理、Docker 设置或重启 Desktop，也未重复已知失败的拉取。随后验收 Nginx 首页/静态资源、`/supplier` 直达刷新、API 转发/错误、商品图、授权与匿名私有凭证、上传大小限制、健康检查/重启及数据库/Redis/MinIO 隔离；Vite 结果不算 Nginx 结果。
- 本轮售后退款/私有凭证/补发、追加结算调整及 390px 供应商完整链路已补实操；仍需浏览器实操运费规则/绑定、空列表/无权限/会话过期、提交中防重复与失败恢复。早期消费者 API 准备和查询不计为小程序页面验收；本轮微信模拟器记录的是实际页面操作。
