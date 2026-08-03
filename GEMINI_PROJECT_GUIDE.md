# ITMS-BD 智能交通系统掌握与 Gemini 协作指南

这份指南针对没有 WSL、Docker，且新增软件需要审批的公司 Windows 环境。项目仍然可以通过 VSCode 做完整代码掌握：先理解后端业务闭环、前端 API 绑定和数据流，再把 Docker/中间件当作部署知识，而不是学习前置条件。

## 1. 项目要解决什么问题

ITMS-BD 是一个课程设计型智能交通管理系统。它用模拟交通数据驱动实时监控、预测、信号优化、路线规划、告警和日志；AI 与大数据目录主要提供可运行的示例模块，核心业务闭环在 Spring Boot 后端和 React 前端。

掌握验收标准：

1. 能画出“模拟器 -> PostgreSQL/Kafka -> 后端查询 -> React 页面”的数据链路。
2. 能解释 JWT 登录、Spring Security 过滤器、Controller、Service、Repository、Entity 的职责边界。
3. 能从实时拥堵数据解释 Dijkstra 路线权重如何变化，并区分 SHORTEST、FASTEST、LOW_CARBON。
4. 能说明预测、信号、告警和日志模块的数据来源、定时任务和接口。
5. 能在不运行 Docker 的情况下读懂一条前端 API 请求，并提出一个有代码证据的改进。

## 2. 系统架构

```text
React/TypeScript/Ant Design/ECharts
  frontend/src/pages + services/api.ts
              |
              | HTTP JSON + Bearer JWT
              v
Spring Boot 3.2 / Java 17
  Controller -> Service -> Repository -> Entity
       |             |             |
       |             |             +--> PostgreSQL
       |             +----------------> Redis（配置/扩展点）
       +------------------------------> Kafka traffic-data
              |
              +--> 定时模拟器 / 预测 / 告警 / 日志

Python AI、bigdata 目录：课程演示和可替换算法模块
```

Docker Compose 中的服务是 `postgres`、`redis`、`zookeeper`、`kafka`、`backend`、`frontend`。在公司学习时，不需要启动它们也能完成代码阅读和接口推演。

## 3. 推荐阅读顺序

### 第一遍：从页面走到接口

1. `frontend/src/App.tsx`、`layouts/MainLayout.tsx`：页面和路由入口。
2. `frontend/src/pages/Login.tsx`：登录表单、token 保存、跳转。
3. `frontend/src/services/api.ts`：Axios base URL、请求拦截器、所有业务 API。
4. `frontend/src/pages/Dashboard.tsx`、`TrafficMonitor.tsx`：实时数据和图表如何刷新。
5. `backend/src/main/java/com/itms/controller/*`：找出接口对应的 Controller。

第一遍要画出一条请求：

```text
Login.tsx -> authApi.login -> AuthController -> AuthServiceImpl
          -> UserRepository -> JwtUtil -> LoginResponse
```

### 第二遍：理解后端业务层

按模块阅读：

| 模块 | 入口 | 继续阅读 |
| --- | --- | --- |
| 认证 | `AuthController` | `AuthServiceImpl`、`JwtUtil`、`SecurityConfig`、`User` |
| 实时交通 | `TrafficDataController` | `TrafficDataServiceImpl`、`TrafficDataRepository`、`TrafficData` |
| 预测 | `PredictionController` | `TrafficPredictionServiceImpl`、`TrafficPrediction` |
| 信号 | `SignalController` | `TrafficSignalServiceImpl`、`TrafficSignal` |
| 路线 | `RoutePlanController` | `RoutePlanServiceImpl`、`TrafficNetwork`、`RoutePlan` |
| 告警 | `AlertController` | `TrafficAlertServiceImpl`、`TrafficAlert` |
| 日志 | `SystemLogController` | `SystemLogServiceImpl`、`SystemLog` |
| 用户 | `UserController` | `UserRepository`、`User` |

### 第三遍：看数据是怎样被制造的

1. `TrafficSimulatorServiceImpl`：定时生成模拟交通数据。
2. `TrafficKafkaConsumer`：消费 `traffic-data` 主题并进入业务处理。
3. `database/init.sql`：表结构、初始路网或种子数据。
4. `TrafficNetwork`：路口和边的静态拓扑。
5. `TrafficPredictionServiceImpl`：从实时数据构造预测和风险告警。
6. `RoutePlanServiceImpl`：实时拥堵如何进入 Dijkstra 权重。
7. `bigdata/kafka/traffic_producer.py`、`bigdata/spark/traffic_streaming.py`、`ai/*`：了解扩展方向，不把它们误认为当前主链路的必需服务。

## 4. 关键业务链路

### 登录与鉴权

- `POST /api/auth/login` 不需要 JWT。
- `AuthServiceImpl` 查询用户、校验演示密码、写登录日志并调用 `JwtUtil`。
- `SecurityConfig` 的过滤器从 `Authorization: Bearer ...` 读取 token，验证成功后写入 `SecurityContext`。
- 其他业务接口默认需要认证；`/actuator/**`、Swagger 和 WebSocket 有单独规则。

### 实时交通

- 模拟器按配置的时间间隔写入交通数据。
- 后端提供 `/api/traffic/realtime`、`/summary`、`/history`、`/heatmap` 等读取接口。
- 前端通过 Axios 拦截器自动带 token；401 时清理本地 token 并回到登录页。

### 路线规划

`RoutePlanServiceImpl` 先找到起止路口，再使用 Dijkstra。边权重不是固定距离：

```text
dynamicCongestionWeight = baseCongestionWeight * (1 + avgCongestionLevel * 0.4)
```

- `SHORTEST`：距离优先并加入拥堵因素。
- `FASTEST`：基础时间、拥堵和红绿灯数量。
- `LOW_CARBON`：距离、碳排因子、拥堵和红绿灯。

要掌握的重点是“实时数据怎样改变图搜索权重”，而不是只记住 Dijkstra 名称。

### 预测和信号

当前代码提供可运行的模拟结果：预测服务生成未来窗口和风险，信号服务生成/应用配时方案。阅读时要区分“可运行业务闭环”和“可替换的真实 LSTM/DQN 模型”。

## 5. 无 Docker/WSL 的公司学习路径

### Level 0：VSCode 静态掌握

- 使用 Java Extension Pack、Spring Boot、ESLint/TypeScript 等已经批准的插件；不新增环境。
- 用 `Find All References` 从 `api.ts` 的接口函数跳到 Controller，再跳到 Service 和 Repository。
- 用 VSCode 的 Outline 和调用层级阅读一个模块，不要一次打开整个仓库。
- 对 `TrafficNetwork` 画 14 个路口和若干边，手算一次路线权重。
- 用 `database/init.sql`、Entity 注解和 Repository 方法做三方对照。

### Level 1：只使用现有命令

只检查，不安装：

```powershell
java -version
mvn -version
node --version
npm --version
```

如果这些命令已存在，可分别做编译验证：

```powershell
mvn -f backend\pom.xml test
npm --prefix frontend ci
npm --prefix frontend run build
```

后端本地启动仍需要 PostgreSQL、Redis、Kafka；前端开发服务器需要 Node。公司缺少这些服务时，保留静态路线和接口推演，不把安装审批当作学习阻塞。

## 6. 练习任务

### 练习 A：从登录到 Dashboard

记录以下对象和边界：

1. `Login.tsx` 发出的 JSON 字段。
2. `LoginRequest` 如何接收字段。
3. `AuthServiceImpl` 返回哪些用户信息。
4. token 存在哪个浏览器位置。
5. 后续 Axios 请求在哪里追加 `Authorization`。
6. 401 的前端行为是什么。

### 练习 B：一次实时数据刷新

从 `TrafficMonitor.tsx` 或 `Dashboard.tsx` 反向追踪：定时器/生命周期、API 方法、后端查询、响应字段、ECharts 数据转换。最后写出“数据库没有数据时页面会看到什么”。

### 练习 C：路线规划手算

选两个相邻或间隔两个节点，分别用三种策略计算一条候选路径。改变一条边的拥堵等级，说明为什么最优路径可能改变。代码证据放在 `RoutePlanServiceImpl.weight` 和 `dynamicCongestionWeight`。

### 练习 D：新增一个只读接口

不要直接改业务代码，先写设计：

1. API 路径和响应结构。
2. Controller、Service、Repository、Entity 是否各需要变化。
3. 前端 API 方法和页面如何消费。
4. 需要的数据库索引和权限。
5. 如何用一个单元测试或接口样例验证。

## 7. Gemini 提示词模板

### Java 端到端追踪

```text
你是 Spring Boot 项目导师。只依据我粘贴的 Java、配置和 SQL 回答。
请按 Controller -> Service -> Repository -> Entity -> 数据库的顺序追踪请求。
每一层列出输入、输出、异常/边界、代码证据和下一步应该打开的文件。
不要把 Spring 的常见默认行为当成当前代码事实。
请求：[HTTP 方法和路径]
代码：[粘贴相关文件片段]
```

### 前后端契约检查

```text
请比较这段 frontend/src/services/api.ts、页面调用代码和后端 Controller。
输出：路径是否一致、HTTP 方法是否一致、请求参数是否一致、响应字段是否一致、认证是否一致。
只报告能从代码证明的问题，并给出最小修复位置。
```

### 算法讲解

```text
请根据 TrafficNetwork 和 RoutePlanServiceImpl 解释路线规划。
先画出图模型，再解释三种 strategy 的权重公式；用一个 4 节点、两条候选路径的数字例子手算。
最后指出实时拥堵数据从哪里来、没有数据时的 fallback 是什么。
```

### 面试模拟

```text
请以 Java 后端实习面试官身份考我 ITMS-BD。
范围：JWT/Spring Security、JPA、Kafka 消费、定时模拟器、Dijkstra 动态权重、React API 联调。
一次只问一个问题；我回答后按“代码证据、正确点、遗漏点、追问”点评。
```

## 8. 掌握记录模板

```text
模块：
用户动作/HTTP 请求：
前端入口：
Controller：
Service 业务规则：
Repository/SQL：
数据库表：
异步来源（定时器/Kafka）：
失败和权限路径：
我能向面试官解释：
下一条验证问题：
```

完成标准是能拿一个页面功能，沿着前端、接口、业务、数据库和异步数据源完整讲完，并能指出当前代码中“模拟实现”和“可替换扩展点”的区别。
