# Nebula 独立测试环境交付记录（2026-10-01）

## 当前结论

已完成本地构建适配与静态配置，**未向 Nebula 发布**。商城仓库当前 `main` 无 Git HEAD、无 remote；Nebula 的 Kubernetes Kaniko executor 先 `git clone --depth 1 --branch <revision>`，再读取仓库根目录下的 Dockerfile 路径和构建上下文。Docker daemon 当前不可连接；本机仅有 `k3d-mycluster` context，API 握手超时，且没有运行中的 Nebula API/前端监听地址。没有证据证明这个 context 是可用的独立测试集群，也没有已核实的项目、registry、存储类、域名或访问限制。没有创建云资源、Namespace、Secret、Pod、仓库或验收数据。

Nebula 代码支持 Git 仓库、Kaniko、镜像仓库凭据、项目 Namespace、模板发布、Deployment/Service/Ingress、Traefik/TLS、Secret/ConfigMap、PVC 与运行日志；这些是**代码能力**，不是此环境已启用的证明。当前 Nebula 工作树也有大量未提交改动，例如 MinIO 单实例模板迁移；运行中的控制面是否含该版本必须在平台上确认。不能使用 Nebula 自身的 PostgreSQL 作为商城数据库。

## 已准备的构建与发布参数

| 服务 | Git 构建上下文 | Dockerfile 路径（仓库根目录起） | 端口 | 副本 |
| --- | --- | --- | --- | --- |
| `orchard-mall-backend` | `backend` | `backend/Dockerfile` | 8080 | 1，`Recreate` |
| `orchard-mall-admin` | `admin-web` | `admin-web/Dockerfile` | 80 | 1 |

两个前端/后端上下文各有 `.dockerignore`；管理端使用 `package-lock.json` 和 `npm ci`，没有在镜像中复制本地 `.env`、`node_modules` 或 `dist`。后端 Maven 版本由 `pom.xml` 管理；基础镜像是 `maven:3.9.11-eclipse-temurin-21`、`eclipse-temurin:21-jre`，管理端是 `node:22-alpine`、`nginx:1.27-alpine`。构建和运行镜像必须由目标 registry/集群实际拉取验证。Kaniko 的上下文与 Dockerfile 参数彼此独立，不能把 Dockerfile 路径误填为 `Dockerfile`。在平台构建时确认目标节点 `kubernetes.io/arch` 与基础镜像架构一致，优先选 `linux/amd64`；当前未读取到目标节点架构，不能声称多架构通过。每次发布使用不可变 Git commit 和镜像 tag/digest，禁用 `latest` 作为回退依据。

`deploy/nebula/*-template.yaml` 是供 Nebula 自定义工作负载模板审核导入的源 YAML，其中 `{{ target_namespace }}`、`{{ image }}`、`{{ image_pull_secret }}`、`{{ storage_class }}` 由平台模板参数解析，**不要直接执行 kubectl apply**。如果运行中的平台已提供隔离的 MySQL/Redis/MinIO 模板或服务，优先在 Nebula 项目内使用并核实版本、凭据、PVC 与 Service 名；否则使用这里的 MySQL、MinIO 模板建立专属实例。Redis 目前不承担订单或会话最终状态，应用没有 Redis 连接配置；若项目要求保留 Redis，优先使用 Nebula 实际可见的 `redis-single` 模板，在独立 Namespace 下配置密码和存储，不开放外部端口。未在平台确认之前不虚构可用的内置 MySQL 模板。所有服务只建 `ClusterIP`，MySQL 3306、Redis 6379、MinIO API 9000 和 Console 9001 不创建公网入口。MinIO 模板只提供内部 API Service，不提供 Console Service。

后端 ConfigMap 的非秘密示例见 `deploy/nebula/backend-config.example.yaml`。`SPRING_PROFILES_ACTIVE=prod` 用于此独立测试环境，以避免开发种子数据自动注入；三个 `DEV_*_ENABLED=false`。这不代表正式营业上线。后端 Secret 的**键名**见 `backend-secret.example.yaml`：`DB_USER`、`DB_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、首次初始化时的 `ADMIN_INIT_PASSWORD`。MySQL 单独 Secret 需要 `MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`；MinIO 单独 Secret 需要 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`。`DB_USER`/`DB_PASSWORD` 与 MySQL 应用用户一致，但按各自 Secret 引用；MinIO 后端凭据须为仅有测试 bucket 读写权限的独立用户，不用 root 凭据。Secret 值只在 Nebula 项目 Secret/外部密钥存储中输入，不提交 Git，不作为镜像 build arg，不写入日志或发布说明。初始化管理密码至少 12 位，确认首个管理员创建后从运行时 Secret/Pod 环境移除；后续密码重置走受控流程。

推荐 Namespace：`orchard-mall-test`（**候选名，非已创建**）；数据库 `orchard_mall_test`、bucket `orchard-mall-test-media` 均为**新建空隔离目标**。本地开发库、历史订单、用户图片不迁入。MySQL StatefulSet 20Gi 和 MinIO PVC 20Gi 是待目标配额确认的起始值；两者需要目标 StorageClass 的 RWO 持久卷。资源初值：后端请求 250m/512Mi、限制 1 CPU/1Gi；管理端 50m/64Mi、限制 250m/256Mi；MySQL 250m/512Mi、限制 1 CPU/2Gi；MinIO 100m/256Mi、限制 500m/1Gi。实际值要用节点容量、配额和监控调整。部署前核对 registry 拉取 Secret 在目标 Namespace 生效，Nebula 的构建推送凭据与运行时拉取凭据均须验证。

## 网络、入口与小程序

候选测试域名 `mall-test.example.invalid` 仅为占位，不指向实际环境。选定 HTTPS 域名后，仅为 `orchard-mall-admin:80` 在 Nebula 创建路由，**先验证网关访问限制**（例如可信 VPN 或经批准的 IP allowlist）和 TLS，再允许测试者访问。路由配置应有同 Namespace TLS Secret，网关类型/IngressClass 与目标集群实际 Traefik 能力一致。若无法验证限制，不开放公网入口，三个模拟能力继续关闭。Nginx 托管后台、`/supplier` 和路由刷新；同源 `/api/` 代理到 `orchard-mall-backend:8080`，保留原始 `/api` 路径，上传上限 11m、后端单文件 10MB、售后凭证 5MB，代理读取/发送 60 秒。`ADMIN_ORIGIN` 设成与浏览器地址完全相同的 `https://<域名>`，没有尾随斜杠；管理端 `VITE_API_BASE` 保持空值以使用同源代理。图片使用 `/api/media/{id}`，由后端控制公开商品关联或管理员权限；私有售后凭证必须经 `/api/customer|admin|supplier/after-sales/evidence/{id}` 鉴权返回，MinIO bucket 不公开。

微信/支付宝小程序不进 Kubernetes。实际 HTTPS 域名和测试访问限制已可从设备访问后，在仓库根目录分别运行：

```sh
cd miniapp
TARO_APP_API_BASE=https://<已验证的测试域名> npm run build:nebula:weapp
TARO_APP_API_BASE=https://<已验证的测试域名> npm run build:nebula:alipay
```

这两个脚本要求 HTTPS、拒绝示例域名和开发模拟支付开关，并强制隐藏开发身份登录；本地 `build:weapp` / `build:alipay` 与之分开。微信服务器体验版使用 `TARO_APP_API_BASE=https://orchard.douwen.top TARO_APP_DEV_PAYMENT_ENABLED=false TARO_APP_DEV_LOGIN_ENABLED=false npm run build:nebula:weapp`。产物在 `dist/weapp` / `dist/alipay`；还须在对应平台配置 request、uploadFile、downloadFile 合法域名，并由有权限的测试者在开发者工具及真机实际验收。微信真实登录代码已接入，真实凭据和真机联调尚未完成；支付宝真实登录及支付适配器仍不可用。

微信登录发布时，在 Nebula 后端 ConfigMap 设置 `WECHAT_MINIAPP_APP_ID=wx1bd16bd6d48b3f93`，在后端 Secret 设置对应小程序的 `WECHAT_MINIAPP_APP_SECRET`。AppID 必须与 `miniapp/project.config.json` 一致；AppSecret 仅通过 Secret 注入，不作为镜像参数、前端变量或日志内容。后端 Pod 需能通过 HTTPS 访问 `api.weixin.qq.com`；先构建、发布含 `/api/wechat/auth/login` 的后端镜像并检查就绪，再上传新的微信小程序体验版。`DEV_CONSUMER_LOGIN_ENABLED`、`DEV_PAYMENT_ENABLED`、`DEV_REFUND_ENABLED` 均保持 `false`。用有权限的微信账号在真机验证登录、`/api/customer/me`、购物车和订单归属，重复登录应返回同一消费者 ID；换一个微信账号应得到独立数据。未配置真实 AppSecret 时该接口返回 503，不能把自动化模拟换码测试写成真实联调通过。

## Flyway、健康检查与回退

后端启动时 Flyway 自动对专属 MySQL 库执行 V1–V8；首次只运行**一个后端 Pod**，待迁移完成、数据库健康和 `/actuator/health/readiness` 为 200 后再把流量接入。迁移失败时应用启动失败，停止发布，保留数据库/PVC，读取 Flyway 与 MySQL 日志诊断，不使用 `repair` 或手改 schema 来掩盖错误。MySQL 8.4 与当前 Flyway 10.20.1 的兼容警告已在 `docs/verification.md` 记录，目标集群首次迁移必须实测。后续版本上线前先在隔离副本演练迁移；Kubernetes 镜像回滚**不能逆转 Flyway schema**，只可回到与当前 schema 兼容的应用版本。若迁移不可逆或数据不兼容，用维护窗口停写，从同步时间点的数据库和对象存储备份恢复到新隔离环境，再经核对后切换入口。

启动探针走 liveness；readiness 包含应用接流量状态与 MySQL `db` 健康；liveness 只检查进程存活，因此数据库短暂异常会使 Pod 退出就绪，但不会被持续重启。后端对 MinIO 的实际对象读写另做验收；健康探针不证明 bucket 权限。后端定时订单过期、支付事件恢复、退款事件恢复按行扫描，只有 outbox 有条件领取和租约机制。虽然关键状态变更有数据库事务/幂等保护，多副本下这三类扫描的锁、重复外部动作和恢复竞争尚未做专项验收，因此后端固定单副本。`Recreate` 避免发布期间新旧版本同时执行调度，但意味着短暂服务中断。

备份需在维护窗口停写并暂停后端/worker，对 MySQL 使用事务一致快照，同时导出对应 bucket 的对象清单/内容；两者的同一业务切面需要人工协调。备份包含个人信息和私有凭证，存放在仓库外的受控加密位置。恢复必须是新库、新 bucket，先校验哈希、迁移版本、订单/支付/退款/结算行数及对象引用，再切换入口。现有 `scripts/backup_local.sh` / `restore_local_test.sh` 是 Compose 本地演练工具，不直接对集群 PVC 执行。

## 待平台按顺序执行的验收

1. 在 Nebula 确认真实测试集群、项目/Namespace、节点架构、资源配额、StorageClass、registry 推送/拉取权限、网关/TLS 与限制访问能力，记录标识和证据。
2. 在 Nebula 创建隔离 Secret/ConfigMap、持久化 MySQL 与 MinIO；创建私有 bucket 和专用 MinIO 用户。Redis 如需部署，核实平台模板版本并在同一隔离项目创建。核对 PVC `Bound` 和 Service 仅 `ClusterIP`。
3. 通过 Nebula 关联授权后的 Git 仓库，以不可变 commit 分别构建两个镜像并确认推送与 digest；通过 Nebula 模板创建、发布 backend/admin，不以直接 kubectl apply 代替平台发布记录。核对 Pod Ready、挂载、Flyway 到 V8 和日志无敏感值。
4. 验证域名解析、TLS 链、入口访问限制，再验证 `/`、`/supplier` 直达和刷新、同源 `/api/` 代理、上传限制、超时、后台登录。使用仅在独立环境建立的最小验收商品和账号，检查公开商品与图片、未登录后台 401、供应商/消费者越权 403/404、私有凭证匿名拒绝及授权可读。
5. 三个模拟开关先保持关闭，验证 `/api/dev/consumer-login` 返回 404、生产 profile 开启任意开关会拒绝启动。只有独立 dev/test 环境的访问限制实际生效且获准测试时，才显式打开模拟能力并验证，绝不把开发身份登录开放到公共互联网。真机和浏览器页面验收要与 curl/API 验证分开记录。

## 当前验证证据与阻塞

| 检查 | 结果 |
| --- | --- |
| `backend/./mvnw test` | 通过，28 tests；使用本地 H2，不代表集群 MySQL 实测 |
| `admin-web/npm run typecheck && npm run build` | 通过；仍有 Vite 大 chunk 提示 |
| `miniapp/npm run build:weapp && npm run build:alipay` | 通过本地构建；无 HTTPS 目标，Nebula 专用构建和真机未运行 |
| Docker 镜像构建/推送 | 未运行，Docker daemon 不可连接；无镜像 digest |
| Nebula 发布/Pod/PVC/HTTPS/浏览器 | 未运行；无测试目标及 Git HEAD；k3d context API 握手超时 |
| 线上登录、鉴权、图片、模拟和生产守卫 | 未运行；只有既有本地测试与代码检查，不能称为集群验收 |

继续发布需要一次性确认：可供 Nebula 拉取的 Git 仓库 URL/目标分支与**首次提交、推送授权**；Nebula 控制面地址及访问方式、独立测试集群/项目；目标 registry 和构建/拉取权限；节点架构、StorageClass/容量与是否需新增付费资源；测试域名、TLS 证书来源、允许访问的 VPN 或 CIDR 及其验证方法。不要在聊天或 Git 中发送明文密码；通过平台 Secret 录入。

首次提交前已扫描未忽略文件：发现的 Taro `.swc` 编译缓存已加入忽略；此后没有发现私钥/证书、备份、日志或构建产物候选。`.env`、`backups/`、`.artifacts/`、`dist/`、`target/`、`node_modules/` 也由 `.gitignore` 排除。测试代码、脚本和 `.env.example` 有演示值/占位符，应在授权提交前再人工复核。当前 index 已有早先暂存的 `backend/.idea/.gitignore`，首次提交时需从 index 排除 IDE 配置并重新核对 `git diff --cached --name-only`、`git diff --cached --check`、密钥扫描；本轮未更改暂存区、未提交或推送。

获得首次提交和推送授权、仓库 URL 后的操作顺序：先用 `git rm --cached -- backend/.idea/.gitignore` 只从暂存区移除 IDE 文件，再 `git add .`，逐项检查 `git diff --cached --name-only`、`git diff --cached --check` 与敏感文件扫描；确认没有个人环境文件后才 `git commit -m "Prepare Orchard Mall Nebula test deployment"`。接着向用户指定的远程仓库添加 remote、推送 `main`，在 Nebula 接入该 URL/分支并选定这个不可变 commit。若远程仓库创建本身需要额外授权或费用，先停在授权节点。以上命令是待执行步骤，不是本轮执行记录。
