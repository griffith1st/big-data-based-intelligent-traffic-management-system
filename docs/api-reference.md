# API、角色权限与数据契约

对应 2026-10-17 代码。完整参数可通过运行服务的 [Swagger](http://localhost:8080/swagger-ui.html) 和 [OpenAPI](http://localhost:8080/api-docs) 查看；下面列出前后端对接最需要统一的约定。

## 1. 请求与响应

前端生产部署走同源 `/api/`，Nginx 转发到后端。请求体为 JSON，除登录等公开接口外携带 `Authorization: Bearer <token>`。跨域来源由 `ITMS_CORS_ALLOWED_ORIGINS` 白名单控制，默认 localhost:3000 与 127.0.0.1:3000；CORS 预检允许的方法与头部见 SecurityConfig。

业务接口成功通常返回 HTTP 200：

```json
{"code":200,"message":"操作成功","data":{}}
```

失败返回对应 HTTP 状态，`code` 与状态一致、`data=null`。分页响应放在 `data` 中，使用 Spring Page 字段 `content/number/size/totalElements/totalPages`，页号从 0 开始。健康检查和 OpenAPI 文档使用其框架原生格式。

| HTTP 状态 | 典型情况 | 前端处理 |
| --- | --- | --- |
| 400 | JSON/参数格式错误、数值越界、非法策略/路口 | 展示字段或请求错误 |
| 401 | 未登录、Token 无效/过期/撤销、账号禁用、认证版本失效、密码错误 | 非登录接口清理会话并回登录页 |
| 403 | 角色不足、操作其他人的路线数据 | 保留会话，展示权限提示 |
| 404 | 指定用户/告警等资源不存在 | 展示资源不存在 |
| 409 | 用户名/邮箱冲突、最后启用管理员保护、缺少可应用信号候选 | 刷新数据或调整操作 |
| 429 | 在途请求预算耗尽，或登录失败已达锁定阈值 | 遵循 Retry-After 稍后再试 |
| 503 | 认证依赖失败、缓存重建等待超时、数据库连接/查询/锁等待问题、模拟器正忙 | 保留会话，展示稍后重试 |
| 500 | 其他内部故障 | 展示通用故障，不暴露内部堆栈 |

准入 429 与一般暂不可用 503 默认 `Retry-After: 1`。登录锁定 429 使用实际剩余锁定秒数。前端 Axios 超时 8 秒，读取 Retry-After 并提示；写操作应先确认幂等条件，再决定是否重试。

## 2. 角色矩阵

A=`ADMIN`，M=`TRAFFIC_MANAGER`，D=`DRIVER`。“本人”由服务端认证主体判定。

| 能力 | 未登录 | A | M | D |
| --- | --- | --- | --- | --- |
| POST 登录、健康/info、Swagger/OpenAPI | 是 | 是 | 是 | 是 |
| 当前账号、退出 | 否 | 是 | 是 | 是 |
| 交通/预测/信号/告警 GET | 否 | 是 | 是 | 是 |
| 交通数据保存、模拟器控制 | 否 | 是 | 是 | 否 |
| 触发预测、优化/应用信号、处理告警 | 否 | 是 | 是 | 否 |
| 路网读取、规划路线、本人历史 | 否 | 是 | 是 | 是 |
| 用户管理、系统日志 | 否 | 是 | 否 | 否 |
| Actuator metrics 等其他监控端点 | 否 | 是 | 否 | 否 |

权限在 [SecurityConfig](../backend/src/main/java/com/itms/config/SecurityConfig.java) 执行。角色来自数据库当前用户，JWT 中旧角色不决定当前权限。路线操作传入别人的 userId 返回 403，包括管理员账号。

## 3. 认证与用户

| 方法与路径 | 请求 | 结果 |
| --- | --- | --- |
| POST `/api/auth/login` | `username,password` | token、userId、username、realName、role |
| GET `/api/auth/me` | Bearer Token | 当前用户资料 |
| POST `/api/auth/logout` | 当前 Token | 撤销该 Token，Redis 保存摘要及剩余 TTL |
| GET `/api/users/page` | page=0、size=20（1～100） | 用户资料分页 |
| GET `/api/users/list` | 无 | 兼容旧版数组接口；新页面优先分页 |
| POST `/api/users` | 创建 DTO | 无密码的用户资料 |
| PUT `/api/users/{id}` | 更新 DTO | 更新后的无密码资料 |
| DELETE `/api/users/{id}` | 无 | 删除成功，data=null |

登录前先查询锁定状态。前 4 次密码错误返回 401，第 5 次失败开始 10 分钟锁定并返回 429，锁定期间正确密码也返回 429。锁定期间请求不续期；低于阈值时成功登录清理失败计数。用户名摘要采用 trim+lowercase，用于失败计数归并。

用户 DTO 接受字段：username、password、realName、email、phone、role、enabled、district、avatarUrl；未知字段返回 400。创建用户名为 3～50 位字母/数字/`_.-`；密码至少 6 个字符，BCrypt 输入至多 72 个 UTF-8 字节。更新需要 role 和 enabled，username 保持原值，空 password 表示不改密码；资料字段按本次提交替换。邮箱和用户名冲突为 409。响应不返回密码或密码哈希。

改密码、将启用账号禁用会递增 authVersion。重启默认保留已有演示账号；显式演示重置是单独配置，不属于登录行为。

## 4. 交通数据

| 方法与路径 | 参数/请求体 | 约定 |
| --- | --- | --- |
| GET `/api/traffic/realtime` | 无 | 每路口/方向各自最新；同时间取更大 id |
| GET `/api/traffic/summary` | 无 | 总数量、加权均速、路口数量、拥堵/畅通数量 |
| GET `/api/traffic/intersection-summary` | 无 | 按路口汇总，含方向详情与最严重等级 |
| GET `/api/traffic/heatmap` | 无 | 复用路口汇总 |
| GET `/api/traffic/history` | intersectionId 必填；minutes 或 limit | minutes 默认60、1～2880、至多1000条；limit 1～1000 优先、最近两天；时间正序 |
| POST `/api/traffic/save` | 一条交通 JSON | 校验后保存并处理告警，提交后失效缓存 |
| POST `/api/traffic/batch-save` | 1～500 条 JSON 数组 | 整批先校验、同一事务写入 |
| POST `/api/traffic/simulator/start` | 无 | 开启后续定时批次 |
| POST `/api/traffic/simulator/stop` | 无 | 停止后续定时批次，已发送消息继续消费 |
| POST `/api/traffic/simulator/generate-once` | 无 | 生成并发起发送一批，异步消费；忙时 503 |

写入例子：

```json
{
  "intersectionId":"INT-001",
  "eventId":"manual-20261017-001",
  "direction":"NORTH",
  "vehicleCount":600,
  "avgSpeed":25.0,
  "occupancyRate":50.0,
  "congestionLevel":2,
  "dataSource":"MANUAL"
}
```

| 字段 | 类型/范围 | 单位或解释 |
| --- | --- | --- |
| id | 写入时省略 | 服务端生成 |
| eventId | 可选1～64字符，首字符字母或数字，其余允许字母/数字/冒号/下划线/连字符 | 缺省UUID；跨请求重试需保留同一ID |
| intersectionId | 静态路网已知 ID，如 INT-001 | 路口编号 |
| direction | NORTH/SOUTH/EAST/WEST | 方向 |
| vehicleCount | 整数，0～1,000,000 | 当前采样数量；未做小时归一化 |
| avgSpeed | 必填有限数，0～300 | km/h |
| occupancyRate | 可选有限数，0～100 | % |
| congestionLevel | 可选整数，0～3 | 0畅通、1轻度、2中度、3严重 |
| longitude/latitude | 可选有限数，±180/±90 | 经/纬度；保存时以已知路网规范化 |
| recordTime | ISO 本地日期时间，可省略 | 省略时后端当前时间；协议中没有单独时区偏移 |
| dataSource | 最多20字符，可省略 | 缺省 UNKNOWN；模拟器 SIMULATOR；手工实验 MANUAL |

读取兼容别名：`averageSpeed/avgSpeed`、`vehicleCount/avgVehicleCount`、`recordTime/timestamp`。注意 `avgVehicleCount` 只是兼容字段，实际等于该行数量。数值拥堵等级与文字 `SMOOTH/LIGHT/MEDIUM/HEAVY` 分开解释。交通 GET 空库返回空列表/零汇总，不负责造数。

相同 eventId 与相同原始业务内容重放返回 HTTP200 和同一数据库 id，不重复告警/缓存失效；内容冲突409。服务端对 intersectionId、direction、vehicleCount、avgSpeed、occupancyRate、congestionLevel、recordTime、dataSource 的原始值生成 SHA-256，补默认值前计算。payloadHash 不接受客户端指定，也不输出；派生名称/坐标/id/createdAt 不进入摘要。重试应重放原请求，别把带数据库 id 的响应对象直接回传。缺 eventId 的独立请求会得到不同 UUID。

定时清理每30分钟删除 recordTime 超过两小时的交通记录，eventId 去重随记录保留，历史查询允许48小时不等于保留48小时。旧记录 eventId 为 null 的历史行不会由迁移自动补齐业务身份。Kafka offset 回执另存且当前无自动清理。

## 5. 预测与信号

| 方法与路径 | 参数 | 行为 |
| --- | --- | --- |
| GET `/api/prediction/{intersectionId}` | 路口 | 查询已有预测 |
| GET `/api/prediction/overview` | 无 | 已有预测概览 |
| GET `/api/prediction/latest` | 可选 intersectionId | 最新批次；空库 batchId=null、items=[] |
| GET `/api/prediction/risk` | 无 | 已有批次中的拥堵风险 |
| POST `/api/prediction/trigger` | 无 | 新模拟预测批次 |
| GET `/api/signals/{id}/state` | 路口 | 当前/候选状态、有效性说明、算法标记 |
| GET `/api/signals/{id}/current` | 路口 | 当前信号列表 |
| GET `/api/signal/overview` | 无 | 信号概览 |
| GET `/api/signals/intersections` | 无 | 路口列表 |
| POST `/api/signals/{id}/optimize` | strategy 默认 BALANCED | 生成候选，当前方案保持 |
| POST `/api/signals/{id}/apply` | 无 | 校验完整候选后应用到数据库 |

预测兼容前缀 `/api/predictions`，旧 `/lstm/trigger` 路径仍触发相同模拟方法。预测输出 `algorithm=TREND_SIMULATION`、`mode=SIMULATION`、`modelVersion=SIMULATION-v1`、`confidence=null`；5～30 分钟，每 5 分钟一个点，数量与速度使用对应交通单位。

信号兼容 `GET /api/signal/{id}`、`POST /api/signal/optimize/{id}`、`POST /api/signal/apply/{id}`。策略为 BALANCED、CONGESTION_FIRST、EMERGENCY_FIRST；实现是 `WEIGHTED_RULES`，`executionMode=SIMULATION`。灯色时长和周期单位为秒；候选标记 OPTIMIZED，应用后为 APPLIED。等待时间/改善比例均是模型估计，可能出现负改善值。旧无效方案有 planValid/validationMessage。首次信号 GET 可能初始化缺失默认配置，和交通/预测只读查询的语义不同。

## 6. 路线与告警

| 方法与路径 | 请求/结果 |
| --- | --- |
| GET `/api/routes/intersections`、`/network` | 静态路口与边 |
| POST `/api/routes/plan` | JSON：startIntersectionId、endIntersectionId、strategy（SHORTEST/FASTEST/LOW_CARBON）；userId 可省略，使用当前人 |
| POST `/api/route/plan` | query：startLng、startLat、endLng、endLat、strategy；匹配最近路口 |
| POST `/api/route/green` | 坐标 query，同低碳策略 |
| GET `/api/routes/history` | 当前用户最近至多500条；可选userId必须是本人 |
| GET `/api/route/history/{userId}` | 兼容本人历史 |
| GET `/api/alerts`、`/unread`、`/unread-count`、`/summary` | 最新列表、未读列表、全量未读数量、统计 |
| POST `/api/alerts/{id}/read` | 标为已读 |
| POST `/api/alerts/{id}/process` | 标为解决，处理人来自认证主体 |

路线返回 `algorithm=DIJKSTRA`；`totalDistance` 为 km，`estimatedTime` 为 min，`carbonEmission` 为 g；边字段分别为 distanceKm/estimatedTimeMin/carbonEmissionGram。routePoints/intersections/routeEdges 是 JSON 字符串字段。trafficDataStatus 为 LIVE/PARTIAL/UNAVAILABLE，采样方向/路口数帮助解释覆盖情况；LIVE 表示方向覆盖完整，不代表额外的“数据新鲜度 SLA”。

每条规划边另外包含 trafficDataStatus 和 sampledEndpointCount：两端均有5分钟内采样为 LIVE，一端有为 PARTIAL，两端均无为 UNAVAILABLE；这是该边两端覆盖情况，与顶层全路网方向覆盖口径不同。缺少任一端时 congestionLevel=null、congestionLevelText=UNKNOWN，仍使用静态参数和已有数据计算时间/碳排估算。前端显示未知，不把估算默认值当作观测到的畅通。历史路线保留生成时的快照。

告警兼容 `/api/alert/list`、`/unread`、`/unread-count`、`/statistics`；操作兼容 `PUT /api/alert/read/{id}` 与 `PUT /api/alert/resolve/{id}`。旧 handler 参数不决定真实操作者。列表上限500，未读总数可大于当前列表长度。状态为 UNREAD→READ→RESOLVED 或 UNREAD→RESOLVED；已解决为终态。source 为 REALTIME/PREDICTION，活动键按来源、路口、方向隔离。

持久化实时观测在条件事务锁内，以交通表最新 `(record_time,id)` 判定是否还能改变告警；首次畅通后迟到的旧拥堵也会被忽略，同一时间以更大id代表后来的观测。预测来源独立处理，历史已解决行保留终态。

## 7. 审计日志与监控

`GET /api/logs/page?logType=OPERATION&userId=1&page=0&size=20` 支持类型与用户条件同时生效（AND），按 createdAt、id 降序。logType 允许 LOGIN/OPERATION/SYSTEM/ERROR；userId≥1、page≥0、size 1～100。

`GET /api/logs` 是前100条兼容接口；`GET /api/logs/summary` 返回数据库真实聚合（总量、操作、登录、失败、类型/日期分组）。操作者来自认证主体；后台任务按系统身份记录，客户端不指定审计人。

健康/info 公开；其他开放的 Actuator 端点需要管理员。实际暴露列表以 application.yml 为准，当前包括 health、info、metrics。

## 8. 前后端对接验收

- 同一字段明确类型、单位、空值含义；空结果展示空态，不用固定假数据填补。
- 区分读取和触发操作：预测 GET 不生成批次，信号 optimize 不直接覆盖 current。
- POST 返回成功与 Kafka 消费完成是两件事；查询确认入库后再刷新统计。
- 401 清理身份，403 保留身份；429/503 保留状态并读取 Retry-After。
- 管理员页面和按钮按角色呈现，服务端接口同样执行权限检查。
- 表单只提交 DTO 允许字段，更新用户时完整传递 role/enabled 与需保留的资料字段。
