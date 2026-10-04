# Orchard Mall 品牌商城

Nebula 的管理端和后端已部署（据线上验收反馈）。本地仓库的商品图片与 Logo 上传鉴权修复尚未重新构建、发布；发布参数与步骤见 [Nebula 部署记录](docs/nebula-deployment.md)。该记录包含早期准备阶段的历史状态，不能代表当前线上发布状态。

一套代码按商家独立部署的品牌商城。已交付 **Phase 4 开发环境闭环**，Phase 5 正在稳定化与本地交付验收；微信开发者工具模拟器已实际走通购物、模拟支付、包裹、退款及补发页面链路，真机及 Nginx 全容器入口尚未签收。真实微信/支付宝资金通道和真实退款仍未接入。

针对真机反馈，源码已调整加购后的返回和购物车刷新、登录后续加购，以及省市区联动地址、服务端地区校验和结算返回重新报价。地区数据由后端 `/api/public/regions` 统一提供；合法地址可以保存，是否配送由结算报价判断。用户已在真机走通真实微信登录、浏览、加购、保存地址和创建待支付订单；本轮新增的取消确认、不可配送报价保护、运费规则名称选择与地址展示去重仍待页面和真机验收，也尚未发布。

本轮交互修复：取消订单先确认，并按服务端 `CANCELLED`、`CLOSED`、`CLOSE_PENDING`、已支付状态分别处理；结算报价失败或不可配送时禁用下单，包邮明确显示；后台运费规则复用 `/api/public/regions`，支持全国默认、省、市、区县；地址详细内容只在展示层去除完全重复的地区前缀。地址、售后、发货、结算登记与冲正等关键操作补充对象和后果确认。没有改线上包邮配置、历史订单快照或真实支付能力。验证明细见 [验证记录](docs/verification.md)。

这次地址改动包含后端代码和随包地区资源，体验版使用前需先重新构建并发布后端（无新增 Flyway 迁移），核对 `/api/public/regions` 可访问。随后在 `miniapp` 目录执行 `npm run build:nebula:weapp`，在微信开发者工具中打开 `miniapp` 项目、确认 `miniprogramRoot=dist/server/weapp/`、编译并上传新版本，再在小程序后台设为体验版；上传和设体验版都需人工操作。本轮验证了本机构建和开发者工具模拟器的请求域名，没有执行后端发布或小程序上传。

当前隔离开发库 `orchard_mall_phase5_source` 已到 Flyway V8，保留固定商品 **DEV验收苹果**（商品 15、5斤装 SKU 16、供货关系 15、北京 700 分模板 5）供手工验收；售价 1234 分、北京一件应付 1934 分，均是测试值。管理端为 `http://localhost:5173`，后端为 `http://127.0.0.1:18084`。当前 18084 的 `dev` 进程显式开启开发消费者登录、模拟支付和模拟退款。微信开发者工具模拟器的独立订单 15 已完成申请 ¥5.00、核准 ¥5.00、开发模拟退款 ¥5.00 的页面验收；订单 14 的历史售后与退款记录保留。真机和支付宝页面尚未验收。详见 [固定数据与验证记录](docs/verification.md)。

## 已实现

- Java 21 / Spring Boot 模块化单体基础、MySQL Flyway 迁移、开发演示商品、MinIO 图片存储接口。
- 管理员密码登录、服务端令牌鉴权、最近管理操作日志；店铺名称、Logo 图片、主题色、联系电话与介绍配置。
- 管理端商品图片与 Logo 上传共用管理员请求封装，发送时读取当前令牌，并由浏览器生成 multipart boundary；该修复待 Nebula 前端镜像重新构建、发布和页面验收。
- 分类、商品、SKU、商品图片、上下架、区分自营果园/农户/厂家的供应商、供应商多发货地、SKU 供货来源管理。每条来源显式绑定供应商、启用发货地、供货价、独立库存和运费模板；商品产地独立保存。上架必须具备图片及供应商/发货地/模板均启用的默认供货来源。
- React / Ant Design 管理后台对接管理 API；Taro 微信和支付宝小程序提供商品浏览、地址、购物车、确认订单、订单列表/详情及取消。微信小程序真实登录代码已接入，待真实 AppSecret 与真机联调；本地 dev/test 仍可使用开发身份登录。
- 公开 API 只返回已上架商品与可售 SKU，不公开供应商报价和联系人。
- 微信/支付宝消费者身份分别绑定，不自动跨平台合并；开发模拟登录仅能在 `dev`/`test` 启用，任何含 `prod` 的环境会拒绝启动。
- 收货地址归属隔离、购物车失效原因、服务端价格与默认供货来源复核、按“供应商 + 发货地 + 来源运费模板”拆组的大陆运费试算；不自动择仓。
- 默认供货来源库存、库存流水、MySQL 条件更新防超卖、待支付订单幂等创建、地址/商品/供货/运费快照、取消与超时幂等释放库存。
- 支付单、显式支付尝试、渠道事件去重、异常收款记录，以及订单/支付/履约三套独立状态；成功确认把预占库存一次性转为已售出。
- 仅在明确 `dev`/`test` 且 `DEV_PAYMENT_ENABLED=true` 时启用的模拟支付，支持成功、失败、未知、重复和延迟事件；生产环境开启会拒绝启动。微信、支付宝适配器未配置时明确返回不可用，不回退模拟通道。
- 支付/取消/超时关单协调：活动支付先关单核实，未知结果不释放库存，支付成功优先；关后到款和多笔成功收款进入可查询异常记录，不自动恢复订单或派单。
- 按下单时的履约分组生成供应商任务和 outbox；供应商账号由店主创建并绑定，工作台位于 `/supplier`，支持接单、多包裹和部分发货；后台可代录发货并记录操作人。
- 消费者查看待发货/部分发货/全部发货与包裹商品、数量、快递公司、单号；未接入物流轨迹时不伪造轨迹。
- 开发站内通知适配器和可恢复 outbox worker，支持并发条件领取、租约恢复、退避、最大次数及后台受控重试。
- 支付与退款事件将“已接收”和“业务完成”分开持久化；原载荷事务失败后由恢复任务重放，已完成事件不重复扣库存、派单或记账。
- 消费者按包裹幂等确认收货；订单完成状态与售后状态分离。未发货退款冻结待发数量，审核通过后只取消并恢复未发部分库存；已发货坏果退款不回补库存。
- 售后申请以元输入，按最多两位小数精确转换成整数分；当前可申请金额和数量由服务端返回，服务端再次校验申请与审核上限。同一件商品目前只支持一次售后申请，即使部分退款后仍有财务余额也不能再次申请；页面展示原因和店铺已配置的联系电话，未配置时明确提示。凭证走私有对象与鉴权下载；补发预占并消耗原供货来源库存，不重复计销售收入。
- 开发退款模拟器仅在明确 `dev`/`test` 与 `DEV_REFUND_ENABLED=true` 下可用，支持成功、失败、未知、重复和延迟通知。微信/支付宝退款适配器未配置时明确不可用且不回退模拟。
- 供应商台账使用下单供货价快照，区分货款、约定运费、售后扣款、补发成本及调整；结算单锁定明细，人工付款可部分登记并通过追加冲正留痕，不声称银行已验证。
- 管理后台新增售后/退款和供应商结算页，本地 dev 模式可在页面模拟退款结果（生产构建无该入口、服务端仍禁止正式配置启用模拟）；私有售后凭证可授权预览。浏览器版 `/supplier` 工作台新增补发、相关售后和本供应商台账，390px 页面已实际完成接单、分包与补发发货。小程序的包裹确认收货和售后申请/凭证/进度已在微信开发者工具模拟器实操通过；真机待确认。

## 尚未实现

真实微信登录的凭据配置和真机联调、真实支付宝登录、支付/退款验签与资金调用、物流轨迹查询、自动确认收货、退货寄回/换货、自动责任裁定、自动转账/分账和正式环境限流。开发模拟结果不是微信、支付宝或银行接入成功；Redis 已提供，但订单、支付、退款、库存、履约和结算台账以 MySQL 为最终依据。

## 外部接入前提

真实微信/支付宝收款与退款需商户资质、商户号、支付密钥、回调域名及验签与对账实现。小程序真机请求与图片加载需可访问的 HTTPS 域名及对应平台域名白名单。当前只有显式开发/测试模拟成功入口，没有真实资金成功接口。

## 环境

- Java 21（项目按 Java 21 编译；本轮本机 JDK 26 通过）、npm、Docker Compose。文档原建议 Node 20/22，但 `admin-web` 和 `miniapp` 的 package.json 均未声明 `engines` 或固定 Node 版本；本轮实际验证为 Node v26.3.1/npm 11.16.0，不能据此宣称 Node 20/22 已验收，也未修改全局 Node。
- 固定版本与架构见 [docs/architecture.md](docs/architecture.md)。
- 先复制 `.env.example` 为 `.env`，替换所有 `CHANGE_ME` 值；`.env` 已被 Git 忽略。密码和 MinIO 密钥必须只保存在部署环境，不能提交到 Git。

## 本地启动

在项目根目录启动 MySQL、Redis 与 MinIO：

```sh
cp .env.example .env
# 编辑 .env，把所有 CHANGE_ME 替换为仅用于本地开发的随机值
docker compose --env-file .env -f deploy/compose.yml up -d mysql redis minio
```

在新终端从根目录加载本地环境，启动后端：

```sh
set -a
. ./.env
set +a
cd backend
DEV_CONSUMER_LOGIN_ENABLED=true DEV_PAYMENT_ENABLED=true DEV_REFUND_ENABLED=true SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

新终端启动管理后台：

```sh
cd admin-web
npm ci
npm run dev
```

当前管理后台从 `admin-web` 目录运行 `npm run dev -- --port 5173 --strictPort`，浏览器打开 **`http://localhost:5173`**；Vite `/api` 默认代理到 `http://localhost:18084`。如后端使用其他端口，启动时设置 `API_PROXY_TARGET`。当前后端为 `http://127.0.0.1:18084`。不要用 `http://127.0.0.1:5173` 打开后台：当前精确 CORS 来源是 `http://localhost:5173`，两者不是同一来源。也不要把 `VITE_API_BASE` 设置为另一后端地址。Spring Boot 不会自动读取项目根目录的 `.env`，IDE 运行配置也需显式传入所需环境变量。

当前地址：后台 http://localhost:5173 ，后端 http://127.0.0.1:18084 ，MinIO 控制台 http://localhost:9001 。通用本地启动若后端仍在默认 8080 端口，需显式设置 `API_PROXY_TARGET=http://127.0.0.1:8080`。开发环境首次启动后提供未上架的演示商品，库存初始为 0；请在管理后台填写净重/计费重量、维护库存、绑定运费模板、上传图片并上架。不要将演示联系人或地址用于真实订单。

供应商账号由管理员在“供应商账号”页创建并绑定，初始密码至少 12 位；创建时需明确选择供应商，并核对供应商名称及 ID、用户名和角色后提交。供应商从浏览器 http://localhost:5173/supplier 登录，可在手机浏览器接单、分包发货、处理补发并查看自己的售后/结算数据。禁用或重置凭据会在服务端删除已有会话，令旧令牌立即失效，不提供供应商自助注册。供应商小程序入口尚未实现。

小程序构建分为服务器与本地两套目录。**连接服务器的微信真机调试、预览、体验版只使用下面这一条构建命令**：

```sh
cd miniapp
npm run build:nebula:weapp
```

命令从 `miniapp/server-build.json` 固定读取 `https://orchard.douwen.top`，强制关闭开发身份登录和模拟支付，并在产物中核对 API 常量、构建标识及本地 API 地址；不需要设置 `TARO_APP_API_BASE`。微信开发者工具**导入 `miniapp` 目录**，根目录 `project.config.json` 的 `miniprogramRoot` 是 `dist/server/weapp/`。打开项目后点击“编译”只编译该服务器产物；项目没有编译前 Taro 脚本。预览、真机调试及上传体验版前，运行 `npm run verify:server:weapp`；在开发者工具控制台查看 `[orchard-build]` 的构建标识和 API 域名，也可查看 `dist/server/weapp/build-info.json`。旧的 `miniapp/dist/weapp` 项目入口是历史产物，不能用于连接服务器；若开发者工具保存了该旧项目，请关闭并从 `miniapp` 根目录重新导入。

需要本地后端调试时才显式运行 `npm run dev:weapp`（watch）或 `npm run build:weapp`；默认本地 API 为 `http://127.0.0.1:18084`，需要其他本地地址时仅对该命令设置 `TARO_APP_API_BASE`。本地产物只写入 `dist/local/weapp/`，应作为单独项目导入 `miniapp/dist/local/weapp`，不影响服务器项目。支付宝同理使用 `dist/server/alipay/` 与 `dist/local/alipay/`。Taro 4.0.9 构建仍带项目级 `--no-check`，因此另行执行 TypeScript 检查和产物校验。

真实微信登录使用服务器接口 `/api/wechat/auth/login`；后端 AppID 和 AppSecret 仍须分别从部署配置与 Secret 注入。服务器版与开发版分开存储消费者会话。服务器包的接口请求、商品图片、凭证上传及将来的下载共用同一 API 域名，不从 Storage 读取 API 地址。微信平台还须配置该 HTTPS 域名的 request、uploadFile、downloadFile 合法域名。先确认后端接口可用，再构建并由有权限人员在开发者工具中上传、设为体验版；本轮未自动上传或发布。真机 Network 域名、登录和页面流程仍待实际验收。

### 开发账号安全初始化

`ADMIN_INIT_PASSWORD` 必须至少 12 个字符，首次启动且管理员表为空时创建用户名 `admin`，密码以 BCrypt 存储。此环境变量不用于每次重置密码。初始化后移除运行时的 `ADMIN_INIT_PASSWORD` 更安全；丢失密码须通过受控运维流程重置，不能在仓库中写入明文密码。会话令牌仅保存 SHA-256 摘要，12 小时失效。

### 全栈容器方式

先执行 `cd admin-web && npm ci && npm run build`，然后回到根目录运行：

```sh
docker compose --env-file .env -f deploy/compose.yml --profile full up -d --build
```

Nginx 入口默认为 http://localhost:8088 。容器模式的管理后台使用同源 `/api` 代理；小程序 API 地址需用设备可达的入口 HTTPS 域名，不能用 `backend`/`mysql`/`minio` 等容器内部主机名。Compose 没有固定 `container_name`，项目名默认为 `orchard-mall-local`，网络和卷按项目名隔离；MySQL、Redis、MinIO 和 Nginx 宿主端口仅绑定 `127.0.0.1`，可通过 `.env.example` 中的端口变量避让冲突。正式部署需配置 HTTPS、独立数据库与存储权限，设置 `SPRING_PROFILES_ACTIVE=prod` 且所有 `DEV_*_ENABLED=false`；生产配置开启任意模拟能力会拒绝启动。本轮 Docker Hub 基础镜像拉取仍受失效代理阻塞，Nginx 入口未验证。

### 备份与恢复

先停止本部署所有应用和 worker 写入，MySQL/MinIO 保持运行；再运行 `scripts/backup_local.sh /绝对路径/新备份目录`。用 `scripts/restore_local_test.sh /绝对路径/备份目录 orchard_mall_restore_drill1 orchard-restore-drill1` 恢复到全新独立数据库和 bucket；脚本拒绝同名覆盖。备份包含个人数据和图片，只能放在受控仓库外目录，绝不提交 Git。恢复副本不可直接启动资金、通知和后台任务。详细一致性边界与核验清单见 [docs/release-readiness.md](docs/release-readiness.md)。

## 验证命令

```sh
(cd backend && ./mvnw test)
(cd admin-web && npm run typecheck && npm run build)
(cd miniapp && npm run build:weapp && npm run build:alipay)
python3 scripts/smoke_phase1.py  # 本地 MySQL/MinIO 和后端启动后
SMOKE_API_BASE=http://127.0.0.1:8080 python3 scripts/smoke_phase2.py
SMOKE_API_BASE=http://127.0.0.1:8080 python3 scripts/smoke_phase3.py
SMOKE_API_BASE=http://127.0.0.1:8080 python3 scripts/smoke_phase4.py
scripts/verify_v8_mysql.sh orchard-mall-local-mysql-1 "$MYSQL_ROOT_PASSWORD"  # 仅创建并清理 orchard_v8_* 隔离库
```

后端集成测试还覆盖消费者越权隔离、报价变化、运费规则、支付/退款原事件重试、关单竞争、异常款、供应商隔离、冻结与发货互斥、补发库存和结算幂等。真实 MySQL 8.4 套件覆盖事务、锁与并发，HTTP smoke 覆盖 Phase 3 及两条 Phase 4 链路。本轮实际验证结果见 [docs/verification.md](docs/verification.md)，上线准备清单见 [docs/release-readiness.md](docs/release-readiness.md)。

## 后续计划

见 [docs/roadmap.md](docs/roadmap.md)。先完成 Phase 5 真机及 Nginx 验收、页面剩余交互修复；后续在具备商户资质后接入真实支付/退款验签、主动查单与对账，补齐退货/换货、物流轨迹和生产运维能力。模拟通道和人工付款登记不是资金成功。
# orchard-mall
