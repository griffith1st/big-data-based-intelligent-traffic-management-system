# ITMS-BD 智能交通管理系统

使用模拟交通数据完成“采集入库 → 监控展示 → 预测/路线/信号计算 → 告警处理 → 操作审计”的教学项目。后端为 Java 17 / Spring Boot 3.2，前端为 React 18 / TypeScript，数据保存在 PostgreSQL，Redis 承担认证状态与热点缓存，Kafka 承担异步交通数据接入。

## 先从这里开始

| 想做什么 | 文档 |
| --- | --- |
| 从零弄懂整个项目 | [八部分学习地图与代码实验](docs/project-guide.md) |
| 启动、升级、回退与排错 | [本地部署手册](docs/deployment.md) |
| 对接前后端、确认角色和字段 | [接口、权限与数据契约](docs/api-reference.md) |
| 从项目学习 Redis | [Redis 实现与面试教学](Redis实现与面试教学.md)、[独立 Redis 实验](docs/redis-lab/README.md) |
| 运行回归、理解测试依据 | [测试快速入口](docs/testing/quickstart.md)、[本轮完整回归说明](docs/testing/project-completion.md) |
| 理解高并发具体设计 | [高并发改造方案](docs/architecture/high-concurrency-plan.md)、[当前版本资源边界](docs/project-guide.md#7-多人同时访问有界资源与故障处理) |
| 使用 AI 辅助学习 | [代码阅读与协作指南](GEMINI_PROJECT_GUIDE.md) |

## 当前功能与边界

| 功能 | 当前实现 |
| --- | --- |
| 交通监控 | 静态路网中的 14 个路口、4 个方向；查询每个路口/方向各自最新记录，展示汇总、热力与有上限的历史数据 |
| 数据接入 | 定时/手动模拟器 → Kafka → 消费者 → PostgreSQL；重试/DLT、同 offset 回执、共享 eventId 的跨入口重放去重 |
| 登录和权限 | BCrypt、JWT、Redis 撤销名单、5 次登录失败锁定、当前账号状态与角色校验；三类角色权限 |
| 预测 | 趋势公式与随机噪声生成未来 5～30 分钟模拟批次；返回 `TREND_SIMULATION` / `SIMULATION-v1`，未接入训练后的 LSTM 模型 |
| 信号控制 | `WEIGHTED_RULES` 规则分配南北/东西相位；候选与当前方案分离，确认后更新数据库；未连接真实信号机 |
| 路线规划 | 静态路网上的 Dijkstra，支持最短、最快、低碳策略；时间/碳排是模型估计，包含交通数据覆盖情况 |
| 告警 | 实时/预测来源隔离、方向隔离，已读/解决状态流转，处理人来自当前登录账号 |
| 用户和日志 | 管理员用户 CRUD/分页、最后一名启用管理员保护、日志分页与组合筛选、真实操作者审计 |
| 前端交互 | 同源 HTTP JSON、Bearer Token、加载/空数据/失败状态；业务数据采用轮询刷新 |

`ai/` 和 `bigdata/` 保留为探索材料。当前 Compose 没有 Python 模型服务；已有 WebSocket 依赖也不等于页面通过 WebSocket 推送。主要业务链路以当前 Java 实现与前端 API 调用为准。

## Docker 一次启动

前提：Docker Desktop 已运行，并可拉取 PostgreSQL、Redis、Kafka、Java/Node/Nginx 构建镜像。在仓库根目录执行：

```powershell
# 已有 .env 时保留原配置
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --build
docker compose ps
docker compose logs --tail 100 backend
```

默认访问入口：

- 前端：[http://localhost:3000](http://localhost:3000)
- 后端：[http://localhost:8080](http://localhost:8080)
- API 文档：[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- OpenAPI JSON：[http://localhost:8080/api-docs](http://localhost:8080/api-docs)
- 健康状态：[http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

前端 Nginx 将 `/api/` 转发到 `backend:8080`。容器中的 Kafka 使用 `kafka:29092`；宿主机开发使用 `localhost:9092`。端口冲突时修改 `.env`，并同步本地进程的连接参数。

默认演示账号仅在账号缺失时创建，密码以 BCrypt 保存：

| 用户名 | 初始密码 | 角色 |
| --- | --- | --- |
| `admin` | `123456` | `ADMIN` |
| `manager1`、`manager2` | `123456` | `TRAFFIC_MANAGER` |
| `driver1`、`driver2` | `123456` | `DRIVER` |

`ITMS_DEMO_ENABLED=false` 关闭演示账号初始化；默认 `ITMS_DEMO_RESET_DEFAULT_USERS=false`，重启保留已有账号密码。旧库明文密码迁移由 `ITMS_MIGRATE_LEGACY_PASSWORDS` 控制，登录校验本身只接受 BCrypt；迁移完成后可关闭该开关。现有数据升级先备份，再阅读 [数据库迁移文件](database/migrations/)；初始化 SQL 只在首次创建 PostgreSQL 数据卷时执行。

默认模拟器每 5 秒生成一批数据。需要手动观察时，先登录，调用 `POST /api/traffic/simulator/stop`，再调用 `POST /api/traffic/simulator/generate-once`。发送与消费是异步的，随后查询实时接口确认数据已落库。交通和预测的 GET 查询保持只读，空库返回空结果。定时清理每 30 分钟删除 `recordTime` 超过两小时的交通记录；历史接口允许查询最近两天不等于保留两天数据。

停止服务并保留数据：

```powershell
docker compose down
```

## 本地开发

使用 JDK 17、Maven 和 Node.js 22；前端镜像也固定使用 Node 22。中间件仍由 Docker 启动：

```powershell
docker compose up -d postgres redis zookeeper kafka
```

后端在 `backend` 目录运行：

```powershell
mvn spring-boot:run
```

前端在另一个终端的 `frontend` 目录运行：

```powershell
npm ci --legacy-peer-deps --no-audit --no-fund
npm start
```

开发代理将 `/api/` 转发到 `http://localhost:8080`。数据库默认 `itms_bd` / `itms_admin` / `itms123456`，以 `.env.example` 和 [application.yml](backend/src/main/resources/application.yml) 为准；本地 Maven 进程不会自动读取根目录 `.env`，改过连接参数时用对应 `SPRING_*` 环境变量传入。

## 并发能力怎么理解

当前落地的是单实例资源保护与可验证的业务正确性：API 同时在途预算 24、Tomcat 最大工作线程 64、数据库连接池 20、查询/锁等待超时、5 秒热点缓存、同实例缓存回源合并、提交后缓存失效、模拟批次互斥、Kafka 有限重试与 DLT。

这几个数各自控制不同资源，24 表示同时在途请求数，和每秒请求数不同。每个认证请求还会查询 Redis 撤销状态和 PostgreSQL 当前用户，因此业务缓存命中也有认证数据库成本。共享 `eventId` 的相同业务内容可跨 HTTP/Kafka/新 offset 去重，内容冲突返回 409；该去重只在交通记录保留期间有效。多实例缓存版本同步、持久失败队列、集群高可用和生产容量仍需专项设计与测量。

[2026-10-16 的 HTTP 压测证据](docs/testing/evidence/high-concurrency/README.md) 保留用于复盘。它属于旧认证链路、指定机器和负载，当前版本的吞吐量应重新测量。新验证入口和结果边界见 [本轮回归说明](docs/testing/project-completion.md)。
