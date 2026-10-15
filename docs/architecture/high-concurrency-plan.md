# ITMS 高并发改造与验收方案

> 文档用途：把当前课程设计版本的并发能力整理成一套可实施、可压测、可回滚的方案。
> 本文严格区分“当前源码已经具备的能力”和“需要改造后才能声称的能力”。未经过压测的吞吐量、延迟和并发数均不是生产指标。

具体测试层次、命令及证据入口见 [高并发测试说明](../testing/high-concurrency.md)。

## 1. 改造前基线与本次落地范围

以下是本次改造之前的链路，便于说明问题来源；当前行为见 1.1 节：

```text
浏览器/客户端
      |
      v
Spring Boot + Tomcat
      |----------------------> Redis（JWT 黑名单、失败计数、5 秒交通缓存）
      |                         |
      |                         `-- 未命中/异常回退 PostgreSQL
      v
Kafka Producer -> traffic-data（原代码声明 1 分区、1 副本）
      v
Kafka Listener（未配置 concurrency，默认单监听容器并发）
      v
PostgreSQL（Hikari 最大连接池 30）
```

改造前配置与代码事实：

| 组件 | 改造前实现/配置 | 并发含义 |
| --- | --- | --- |
| HTTP | Spring Boot 内嵌 Tomcat，未设置 `server.tomcat.threads.max` | 使用容器默认工作线程；不是自定义线程池 |
| 定时任务 | `@EnableScheduling`，模拟器 fixed delay 5 秒，清理任务每 30 分钟 | 未提供自定义调度线程池，任务可能互相延迟 |
| Kafka Topic | `KafkaTopicConfig` 声明 1 分区、1 副本 | 同一消费组的有效并行度上限受 1 分区限制 |
| Kafka 消费 | `@KafkaListener` 未设置 `concurrency` | 当时尚未提供消费者并发配置 |
| Kafka Producer | `retries: 0`，`max.block.ms: 1000` | 生产端没有应用级重试保障 |
| 数据库 | Hikari `maximum-pool-size: 30`，连接超时 5 秒 | 所有请求、消费者和定时任务共享数据库连接上限 |
| PostgreSQL | Docker `max_connections=50` | 数据库最大连接还要覆盖管理连接和其他客户端 |
| Redis | 交通缓存 TTL 5 秒；认证状态使用 TTL | Redis 是加速/短期状态层，不是业务事实来源 |
| 交通模拟器 | 定时调用和 HTTP 手动生成都可进入 `generateOnce()` | `previousCounts` 的读取-计算-写入不是原子复合操作 |

这套基线能演示异步链路和基础缓存，但缺少过载保护、缓存并发回源合并和消息失败处理。以下列明本次已落地的代码，再给出后续扩展与容量验收计划。

### 1.1 本次已落地的代码

| 范围 | 当前行为 | 边界与验收 |
| --- | --- | --- |
| HTTP 入口 | `RequestAdmissionFilter` 用 `Semaphore` 限制单实例在途 API 请求；超过上限返回 HTTP 429、业务码 429 和 `Retry-After: 1` | 它是并发准入，不是按秒计数的 QPS 限流器；集群总限额还需网关协调 |
| 过载错误契约 | 入口 429、缓存/模拟器忙 503 及已识别数据库资源异常均返回 `Retry-After: 1`；CORS 暴露 `Retry-After` 响应头 | 浏览器能读取退避提示；1 秒是建议最小等待，不保证下一次请求一定成功 |
| HTTP 资源 | 默认 Tomcat 最大 64 线程、最大连接 1024、accept count 64；在途 API 默认 24 | 参数是保守起点，不是经过认证的容量结论 |
| 数据库资源 | 默认 Hikari 上限 20、获取连接超时 2 秒；关闭 Open Session in View；本次配套 PostgreSQL 会话预算为语句 5 秒、锁等待 2 秒 | Hikari 超时只限制取连接，SQL 执行超时由 PostgreSQL 控制；实际会话用 `SHOW` 验证 |
| 缓存重建 | 同实例同 Key/代次的并发未命中共享一个 `CompletableFuture`；等待者默认等 1500 ms 后返回 503 | 这是 JVM 内单飞，未实现跨实例分布式重建锁；回源执行者仍受 DB 超时等下游约束 |
| 缓存回填一致性 | 缓存发布和失效共用本地锁，先完成 DEL 再推进 generation；旧代次结果不再回填 Redis | 防止删除过程中 summary 读到旧依赖后作为“新代次”回填；跨实例线性一致性仍不在保证内 |
| 事务与缓存 | 事务内查询绕过共享缓存和单飞；写入只在 `afterCommit` 失效缓存，回滚保留原已提交缓存 | 无外层事务的保存沿用落库后失效；原请求仍可能读到旧快照，Redis 删除失败仍由短 TTL 收敛 |
| 登录失败计数 | Lua 把 `INCR` 和缺失 TTL 时的 `PEXPIRE` 放到同一次 Redis 执行，并修复无 TTL 的历史计数 | 计数窗口 10 分钟，单次增加原子；登录全流程“检查 + 认证 + 计数”并未变成一个原子事务 |
| 模拟器共享状态 | `volatile running` 保证可见性；`ReentrantLock.tryLock()` 串行化一批数据生成及发起发送 | 定时遇忙跳过，手动遇忙返回 503；异步发送完成及数据库 fallback 不在批次锁覆盖范围 |
| 调度线程 | Spring 调度池大小设为 2 | 缓解清理和生成互相阻塞，尚未按任务建立完全独立的调度池 |
| Kafka 并行入口 | 源 Topic、DLT 的分区/副本及消费者并发均可配置 | 演示默认仍为 1 分区、1 副本、1 并发；改配置不等同于已扩成生产集群 |
| Kafka 失败处理 | 消费异常交给 `DefaultErrorHandler`，默认间隔 1 秒重试 2 次，随后发 `.DLT` 同分区；DLT 发送失败继续暴露失败 | 原消费 1 次 + 重试 2 次；DLT 需要人工排查和受控回放，未加入事件幂等唯一键 |
| Kafka 重复投递 | 独立 `TrafficIngestionServiceImpl.ingest()` 外层事务内，先以 group/topic/partition/offset 的 SHA-256 申领 receipt，再保存交通记录和告警 | 相同消费组下同一 offset 重投只生效一次；新 offset、不同组或 DLT 重新生产属于新投递，业务 eventId 去重仍待补 |
| Kafka 生产 | `acks=all`、幂等生产者、3 次重试、10 秒交付超时 | Kafka 生产者幂等不等于 PostgreSQL 消费幂等，也不消除数据库直写降级的重复风险 |
| Kafka 确认 | 关闭自动提交，监听器 `ack-mode=record` | 成功处理或按容器恢复策略处理后才前进 offset，仍是至少一次语义 |
| 指标 | 增加 `itms.http.admission.active` 和 `itms.http.admission.rejected`；通过认证后的 Actuator metrics 查看 | health/info 匿名可访问，metrics 需要 JWT；完整 Redis/Kafka/SQL 看板与告警仍是后续工作 |
| 压测工具 | Python 标准库 HTTP 混合读脚本，输出 JSON/Markdown，区分成功吞吐和 429 | 工具自测覆盖结果分类；真实容量以具体环境的实测证据为准 |

### 1.2 当前可调参数

| 环境变量/配置项 | 默认值 | 调整依据 |
| --- | ---: | --- |
| `ITMS_HTTP_THREADS` | 64 | HTTP 工作线程；结合 CPU、I/O 阻塞及下游资源观察 |
| `ITMS_MAX_IN_FLIGHT` | 24 | 单实例在途 API 上限；过量返回 429 |
| `ITMS_DB_POOL_SIZE` | 20 | 每实例数据库连接预算 |
| PostgreSQL `statement_timeout` | 5 秒 | 单条 SQL 执行预算，需用应用连接会话核验 |
| PostgreSQL `lock_timeout` | 2 秒 | SQL 等待锁预算，需用应用连接会话核验 |
| `itms.traffic.cache.wait-timeout-ms` | 1500 | 单飞等待者最大等待时间（毫秒） |
| `ITMS_KAFKA_PARTITIONS` | 1 | 源 Topic 与 DLT 分区数 |
| `ITMS_KAFKA_REPLICAS` | 1 | Topic 副本数，不能超过可用 Broker 数 |
| `ITMS_KAFKA_CONCURRENCY` | 1 | 每个消费者实例的监听线程并发 |
| `itms.kafka.consumer.retry-interval-ms` | 1000 | 消费失败重试间隔（毫秒） |
| `itms.kafka.consumer.max-retries` | 2 | 首次失败后的最大重试次数 |

参数之间的关系：Kafka 的有效消费并行度上限是分区数；总消费者线程数大于分区数时，多余线程空闲。API 上限也不应简单等同于数据库连接数，因为缓存命中请求、Kafka 和后台任务的资源占用不同，需要混合压测决定最终配比。

## 2. 目标与边界

先定义工作负载，再定义容量。建议把目标拆成三类，而不是给整个系统一个含糊的 QPS：

1. **读流量**：`/api/traffic/realtime`、`/summary`、`/heatmap` 等高频查询。
2. **写流量**：Kafka 交通数据消费、手动保存和模拟器发布。
3. **控制流量**：登录、登出、告警处理、信号灯优化和路线规划。

压测阶段可先采用以下“验收目标”，它们是测试目标，不是现有生产数据：

| 场景 | 初始验收目标 | 约束 |
| --- | ---: | --- |
| 实时查询（缓存命中） | 200 逻辑并发，P95 <= 200 ms，错误率 < 1% | 单实例、固定数据集，记录机器配置 |
| 实时查询（缓存绕过） | 50 逻辑并发，P95 <= 800 ms，错误率 < 1% | 用于测数据库保护，不代表线上目标 |
| 交通消息消费 | 目标速率按 `分区数 × 单分区可处理速率` 逐级提升 | 必须记录 Kafka lag、落库成功数 |
| 登录失败计数 | 100 并发用户名请求，计数无丢失 | 验证 Redis `INCR` 和窗口过期 |
| 混合流量 | 读 70%、写 20%、控制 10% | 观察连接池、Redis、Kafka 和 DB 的联动 |

上表的 200/50 并发等是后续调优与扩容目标，尚未达到验收结论。本轮已测 10/20/50/100 并发；20 并发持续 150,000 次读请求全部成功，50/100 并发发生 429 并未通过错误率阈值，详见 [实测证据](../testing/evidence/high-concurrency/README.md)。默认准入预算 24，提升容量前应根据真实混合负载决定调参或扩实例。

测试应同时记录：CPU、堆内存、GC 暂停、Tomcat 活跃线程、Hikari active/pending、Redis ops/latency、Kafka consumer lag、PostgreSQL 活跃连接与慢查询。本轮采集范围及尚缺的指标已在证据中说明。

## 3. 后续扩展的推荐架构

```text
                   +----------------------+
                   | 反向代理/网关        |
                   | TLS、限流、超时、熔断 |
                   +----------+-----------+
                              |
                 +------------+------------+
                 |                         |
        +--------v--------+       +--------v--------+
        | ITMS API 实例 1 |  ...  | ITMS API 实例 N |
        | 无状态 HTTP     |       | 无状态 HTTP     |
        +---+----------+---+       +---+----------+---+
            |          |               |          |
            |          +---------------+----------+
            |                          |
       +----v-----+              +-----v------+
       | Redis HA |              | PostgreSQL  |
       | 缓存/状态|              | 主库/只读副本|
       +----------+              +------------+
            ^
            |
       +----+-----------------------------+
       | Kafka 集群：多分区、复制、DLQ     |
       +----+-----------------------------+
            |
       +----v-----------------------------+
       | 独立消费者实例（按分区水平扩展）   |
       +-----------------------------------+
```

### 3.1 请求层

- API 实例保持无状态，JWT 继续负责身份；不要在单个 JVM 保存跨请求会话状态。
- 网关设置连接超时、读取超时和请求体大小上限；长耗时的预测、信号灯优化和路线规划不要占用普通查询线程。
- 读接口和写接口使用不同的限流桶。限流维度至少包括用户/IP、接口和实例；登录接口还要按用户名做失败窗口限制。
- 不建议直接把 Tomcat 最大线程数调到很大。线程数应由压测和下游容量反推，避免请求堆积把数据库连接耗尽。

### 3.2 Redis 层

继续使用 Cache-Aside：

```text
GET -> Redis 命中 -> 返回
    -> 未命中 -> PostgreSQL -> SET 5 秒 -> 返回
写入 PostgreSQL 成功 -> 删除相关缓存
```

建议补充以下约束：

- Key 保持命名空间：`itms:traffic:*`、`itms:auth:*`；缓存值带 `schemaVersion`，避免字段升级时旧 JSON 反序列化失败。
- 热点 Key 增加随机 TTL（例如 4--6 秒），减少同一时刻批量过期造成的缓存击穿。
- 对同一个缓存 Key 的重建使用短时互斥（优先使用 Redis `SET key value NX PX` 或单飞机制），锁只保护重建，不保护数据库事务。
- 读取 Redis 超时后快速回退 PostgreSQL，并设置回退限流；Redis 故障时不能让所有请求同时打满数据库。
- 认证黑名单和登录失败计数与交通缓存分开命名、分开监控。认证状态查询失败的处理策略必须显式配置，不能无意中把异常当成“用户已通过认证”。

### 3.3 Kafka 层

当前默认仍保留 1 分区/1 副本以适配单 Broker 演示；分区、消费者并发和重试/DLT 已完成可配置化。进一步扩容顺序：

1. 先按交通数据吞吐把 `traffic-data` 扩为至少 6 个分区（最终数量通过压测决定）。Key 继续使用 `intersectionId:direction`，同一路口方向保持有序。
2. 生产集群至少 3 Broker，副本因子至少 3；演示环境可保留单 Broker，但不能把它当作高可用结论。
3. Consumer 实例数和 `concurrency` 不超过分区数。部署 3 个消费者实例、每实例 `concurrency=2` 时，最多同时处理 6 个分区。
4. 本次已让失败抛给容器，并加入重试/DLT；扩容时必须验证 DLT 分区数量与源 Topic 一致，以及故障后 offset 行为。
5. 为消息增加 `eventId`、`eventTime`、`schemaVersion`。以 `eventId` 建唯一约束或幂等表，防止重试导致重复写入。
6. 配置生产端有限重试、退避和发送超时；重试预算必须小于上游请求超时，避免线程长期阻塞。

当前已落地的是“至少一次 + 同组同 offset 消费去重”。幂等生产者只处理 Kafka 生产协议范围内的重试；应用重新发送、不同 offset 的同一业务事件，需要后续 eventId 约束。面试应明确这两层幂等的范围。

### 3.4 PostgreSQL 与连接池

- 保留 Hikari 连接池作为数据库访问边界。连接池最大值不能超过 PostgreSQL 可安全承载的连接预算。
- 连接预算公式：

  ```text
  max_connections >= API实例数 × 每实例Hikari上限
                    + 消费者/任务连接
                    + 管理和迁移余量
  ```

  例如 2 个 API 实例各 15 条连接、1 个消费者实例 10 条连接，再预留 10 条管理连接，数据库需要约 50 条连接预算；最终值必须结合内存和实际 SQL 测试决定，不能照抄示例数字。

- 高并发读优先走 Redis；历史查询强制分页、限制最大时间窗口和返回条数，禁止把两天数据全部加载到 JVM 后再 `stream().skip()`。
- 给 `traffic_data` 增加与查询条件匹配的复合索引（例如路口、方向、记录时间），用 `EXPLAIN (ANALYZE, BUFFERS)` 证明索引有效。
- 批量落库使用真正的批处理或 `COPY`/批量 INSERT，避免每条消息单独 flush；批次大小以锁等待、WAL 和内存压测确定。
- 清理任务使用时间分区或分批删除，并在独立调度线程执行，避免一次大 DELETE 长时间占用连接和锁。
- 事务只包住必要的数据库操作。Kafka 消费、远程调用和 Redis 网络操作不要放在长事务中。

### 3.5 定时任务与后台工作

- 配置独立 `TaskScheduler`，将模拟数据、历史清理、预测任务分到不同调度池；每个任务设置执行超时和重入保护。
- 本次已将 `TrafficSimulatorServiceImpl.running` 设为 `volatile`，并用 `tryLock` 让整批生成串行化，保护 `previousCounts` 的读取-计算-写入。
- 后台任务应有“跳过本轮”策略，不能在上一轮未结束时无限排队。
- 预测和信号灯优化等耗时操作采用受限的业务执行器，队列有界并明确拒绝策略；不要直接创建无界线程。

## 4. 限流、超时和降级矩阵

当前已生效的错误契约：

- 在途 API 预算耗尽返回 HTTP 429、body.code=429。
- 缓存跟随者等待超时、模拟器手动生成遇忙返回 HTTP 503、body.code=503。
- Controller/Service 路径抛出的 `QueryTimeoutException`、`CannotCreateTransactionException`、`DataAccessResourceFailureException`、`CannotAcquireLockException` 由全局异常处理器映射成 503，避免把数据库容量故障误报为客户端 400。
- 以上 429/503 带 `Retry-After: 1`，跨域响应通过 `Access-Control-Expose-Headers: Retry-After` 允许前端读取。前端 GET 可以结合随机抖动进行有上限重试；写请求先确认幂等边界。压测脚本刻意不自动重试，以保留真实失败率。

这些映射覆盖应用识别的异常路径；网关自身超时、Servlet 过滤器中的其他异常和 Kafka Listener 异常分别受各自处理链控制，不能据此断言所有故障都变成 HTTP 503。

下面是进一步扩展时的建议，尚未全部实现：

| 接口类型 | 限流建议 | 超时建议 | 降级行为 |
| --- | --- | --- | --- |
| 实时读 | 用户/IP + 全局令牌桶；缓存命中优先 | 1 秒 | 返回最近一次缓存或短暂旧值；Redis/DB 都失败返回明确错误码 |
| 历史查询 | 按用户并发数限制，强制 `limit` 上限 | 2--3 秒 | 缩短时间窗口或要求分页 |
| 手动批量保存 | 按请求体条数和用户限流 | 5 秒 | 转 Kafka 异步任务，返回任务 ID |
| 预测/信号灯优化 | 每路口串行或单飞；同一操作幂等 | 5--10 秒 | 返回最近一次已发布方案 |
| 登录 | IP + 用户名双维度失败窗口 | 2 秒 | 达到阈值返回统一错误，不暴露账号存在性 |
| Kafka 消费 | 由分区和消费者数限制 | 单条消息处理超时 | 重试队列/DLQ，保留原始 payload 和原因 |

所有超时要小于上游超时，并留出返回错误的时间；否则调用方超时而服务端仍继续占用线程和连接。

## 5. 幂等与一致性设计

### 5.1 交通数据消费

本次为什么增加 receipt：新增消费重试后，如果交通记录已写入而告警失败，再重试整条消息可能重复插入交通数据，使实时流量重复求和。因此增加独立的事务入口，保证 Kafka 同一位置的重投有持久化去重依据。

当前实现：

```text
Kafka ConsumerRecord
  -> 独立 Spring Service 的 @Transactional ingest()
  -> 解析 payload
  -> event_key = SHA256(group + 分隔符 + topic + 分隔符 + partition + 分隔符 + offset)
  -> INSERT traffic_ingestion_receipts ... ON CONFLICT(event_key) DO NOTHING
       -> 影响 0 行：此前已提交，直接返回
       -> 影响 1 行：保存 traffic_data + 更新 alert
  -> PostgreSQL 提交 receipt、traffic、alert
  -> afterCommit 失效 Redis 交通缓存
  -> 监听器成功返回，由容器推进 offset
```

receipt 使用 `event_key` 主键解决同一投递被并行执行时的竞争，而不使用“先 SELECT 再 INSERT”。任一步抛出事务错误时，receipt 和本轮业务写入一起回滚，后续重试仍有机会重新申领。进程在 PostgreSQL 提交后、Kafka offset 提交前退出，重投会命中已提交 receipt 并跳过重复业务保存。

`TrafficDataServiceImpl` 在事务内直接读取数据库，不读写共享缓存，也不加入非事务查询正在执行的单飞；缓存失效在提交后执行，回滚时不删除原已提交结果。无外层事务的 HTTP 直写仍按当前保存路径处理，不能将 Kafka 事务保证推广到全部入口。

边界：

- 同一 payload 被生产到新 offset，会形成新 receipt；当前不是业务事件去重。
- DLT 消息重新生产到源 Topic 通常获得新 offset，也会形成新 receipt；回放前需要确认原投递的事务状态，后续优先引入稳定 eventId。
- 修改消费组表示新的消费身份，会产生独立 receipt。
- receipt 表的保留时长应覆盖允许的重放窗口；本次未加入自动清理策略。Topic 删除重建后的 offset 重用也需要纳入命名/生命周期策略。

后续用于业务事件去重的消息结构建议（尚未落地）：

```json
{
  "eventId": "uuid-or-device-sequence",
  "intersectionId": "I001",
  "direction": "NORTH",
  "eventTime": "2026-10-15T10:30:00+08:00",
  "schemaVersion": 1,
  "vehicleCount": 620
}
```

后续在 PostgreSQL 侧增加唯一键 `(event_id)`，补足不同 offset 对应同一业务事件时的去重：

```text
收到消息
  -> 校验 schema 和 eventId
  -> 插入幂等记录/交通数据
  -> 成功提交 offset
  -> 删除或失效实时缓存
```

唯一键冲突视为“已处理”，可以提交 offset；数据库异常则重试或进入 DLQ。

### 5.2 手动控制接口

信号灯应用、批量保存和预测触发接口接收 `Idempotency-Key`。服务端保存请求摘要、状态和响应，重复 Key 返回原响应，不重复执行副作用。

### 5.3 缓存一致性

当前有外层事务时注册 `afterCommit` 失效，回滚不删除原已提交缓存；事务内查询绕过缓存以避免共享未提交数据。失效在本机锁内先 DEL、再推进代次，和 SET 发布互斥。Redis DEL 失败时依靠原 5 秒 TTL 收敛；错误指标仍是待补项。

## 6. 可观测性

至少提供以下指标（Micrometer/Prometheus 或等价工具均可）：

- HTTP：按接口统计 RPS、P50/P95/P99、4xx/5xx、超时、限流拒绝数。
- Tomcat：活跃线程、队列、最大线程、请求处理时间。
- Hikari：active、idle、pending、timeout、usage time。
- Redis：命中率、GET/SET/DEL 延迟、连接错误、回退次数、热点 Key。
- Kafka：生产失败/重试、consumer lag、DLQ 数、每条消息处理耗时。
- PostgreSQL：活跃连接、锁等待、慢查询、缓存命中、WAL 生成速率。
- 业务：交通数据生成数、落库成功数、重复 eventId 数、告警生成数。

日志必须带 `traceId`；消息消费还应带 `eventId`、partition、offset。密码、JWT 原文和完整认证凭证不得写日志。

告警阈值先以压测基线的相对变化定义，例如“P95 连续 5 分钟超过基线 2 倍”“consumer lag 持续增长”“Hikari pending 大于 0 且持续 1 分钟”，不要未经测试写固定生产阈值。

## 7. 压测计划

### 7.0 已落地的 HTTP 读压测工具

仓库已新增 [backend/scripts/load/traffic_load.py](../../backend/scripts/load/traffic_load.py) 和使用说明 [backend/scripts/load/README.md](../../backend/scripts/load/README.md)。它使用 Python 标准库对 `realtime`、`heatmap`、`summary` 做轮询混合请求，支持并发数、请求数、超时、P95 和错误率阈值，输出 JSON/Markdown 证据，并保存压测机信息、参数和 Git 提交号。

工具当前已经落地的行为：

- 默认只访问 `127.0.0.1`；远程地址必须显式传 `--base-url`。
- Token 只从环境变量读取；不打印、不写报告、不写异常信息。
- 只有 HTTP 2xx 且响应体 `code == 200` 才算成功。
- 429、5xx、超时、无效 JSON 和其他业务码计入失败；`success_rps` 不包含 429。
- 不自动重试；每个 worker 使用一个有界并发请求循环。

它已经有回环假服务自测，但尚未替代真实 ITMS 服务的容量压测。真实运行仍需附带服务端 CPU/内存、JVM、数据库、Redis 和 Kafka 指标。

### 7.1 准备

1. 固定 Git 提交、JDK、容器镜像、机器 CPU/内存、数据库数据量和 Kafka 分区数。
2. 关闭或单独统计模拟器，避免后台任务污染纯 HTTP 结果。
3. 准备匿名健康检查和带 JWT 的业务请求；压测账号、Token 和测试数据与真实环境隔离。
4. 开启指标采集和 PostgreSQL `pg_stat_statements`（若环境允许）。

### 7.2 场景

```text
S1 读缓存命中：realtime/summary/heatmap，固定 5 秒窗口
S2 隔离 Redis 故障：验证本机回源合并、等待超时与 DB 保护
S3 Kafka 写入：逐级提高每秒消息数，观察 lag 和落库耗时
S4 混合流量：读 70%，消息 20%，控制 10%
S5 故障注入：暂停 Redis、Kafka、PostgreSQL 连接，验证降级和恢复
S6 当前重复投递：相同 group/topic/partition/offset 重投，验证只落库一次
S7 后续业务幂等：不同 offset 的相同 eventId（待实现 eventId 后验收）
```

### 7.3 结果判定

每个场景至少运行预热、稳定和冷却三个阶段。报告必须给出：

- 请求/消息数量、成功和失败数量；
- P50/P95/P99 和最大延迟；
- 错误分类（超时、限流、连接池耗尽、反序列化、数据库错误）；
- Redis 命中率、Kafka lag、Hikari pending、PostgreSQL 慢查询；
- 结果对应的提交号和配置文件。

只有当数据、机器配置、持续时间和并发模型一并记录后，才可以把结果写进简历；没有这些证据只能写“完成压测脚本和瓶颈分析”。

## 8. 分阶段落地与回滚

### 8.0 本次部署顺序与可执行检查

1. 记录当前部署的 Git 提交、JAR/镜像版本、消费组和已提交 offset，保留可恢复的旧制品。停止旧后端或暂时关闭监听器，避免迁移时继续写入；关闭监听器可使用 `SPRING_KAFKA_LISTENER_AUTO_STARTUP=false` 后重启。模拟器也应使用 `ITMS_TRAFFIC_SIMULATOR_ENABLED=false` 暂停。
2. 在**与 traffic_data 相同的数据库/schema**执行新增 receipt 表迁移。项目没有 Flyway/Liquibase 自动迁移入口；`ddl-auto=update` 不代替受控迁移。将 PostgreSQL 的 `PGHOST`、`PGPORT`、`PGDATABASE`、`PGUSER` 与本机密码文件配置好，然后在仓库根目录运行：

   ```powershell
   psql -X -v ON_ERROR_STOP=1 -f .\database\migrations\20261016_traffic_ingestion_receipts.sql
   psql -X -v ON_ERROR_STOP=1 -c "SELECT current_database(), current_schema(), to_regclass('traffic_ingestion_receipts');"
   psql -X -v ON_ERROR_STOP=1 -c "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='traffic_ingestion_receipts' ORDER BY ordinal_position;"
   psql -X -v ON_ERROR_STOP=1 -c "SELECT constraint_type FROM information_schema.table_constraints WHERE table_schema=current_schema() AND table_name='traffic_ingestion_receipts';"
   ```

   预期存在 `event_key`（varchar，非空）、`received_at`（timestamp，非空）以及主键。迁移采用 `CREATE TABLE IF NOT EXISTS`；若已存在同名但结构不同的表，仍需检查结构，脚本不会自动纠正它。迁移账号与应用账号不同时，还需给应用账号授予 schema USAGE 及 receipt 表的必要读写权限；不要只用管理员连接验证建表成功。
3. 安装本次已验证制品，先保持默认 Kafka 1 分区/1 副本/1 并发。启动时核实源 Topic 和 `.DLT` 的分区数一致；对于已存在的 Topic，副本调整要走 Kafka 副本迁移，修改应用配置本身不会完成它。分区扩容也需要先决定 Key 重映射后的顺序边界。
4. 应用加载的是 `ITMS_HTTP_THREADS=64`、`ITMS_MAX_IN_FLIGHT=24`、`ITMS_DB_POOL_SIZE=20` 等环境变量。直接启动 JAR 时读取进程环境；Docker Compose 需要把覆盖值写进 `backend.environment` 或覆盖文件，单独在宿主机设置同名环境变量并不会自动注入当前 Compose 文件。
5. 用 `/actuator/health` 检查启动，用登录后的 JWT 检查实时查询及认证后的 metrics；用应用连接验证 `statement_timeout=5s` 和 `lock_timeout=2s`。确认无迁移/配置错误后，再恢复监听器和模拟器并观察 lag、receipt 增长、错误率。

回滚时停止新版本消费者并记录最后提交的 offset，恢复此前保留的制品及配置，保持原消费组身份，保留 receipt 表及其中数据。旧制品若不识别 receipt，重放已经处理过的 offset 会重新产生副作用，因此恢复点应采用核对后的已提交位置；不要为了回退而自动 reset offset 或清空 receipt。

上述步骤还需要部署方填入实际数据库连接、Kafka 管理地址、消费组快照和旧制品路径/镜像标签。本仓库中的默认 Compose 属于演示部署；当前测试环境走 WSL 组件，实际执行记录见测试文档。

### 阶段 A：已落地和待补项

- 已落地：模拟器可见性及批次串行化、缓存单飞和代次保护、事务内绕过缓存/提交后失效、Lua 计数过期、HTTP 并发准入、Kafka 重试/DLT 及同 offset receipt 事务去重。
- 待补：缓存/数据库细化指标、历史查询数据库分页与最大窗口、跨 offset 的业务 eventId 去重约束。

回滚：先停止消费，确认已提交 offset，再回退应用版本；保留 `traffic_ingestion_receipts` 数据。回退到没有 receipt 的旧消费者后，重放旧 offset 又可能重复写入，需结合已提交 offset 决定恢复起点。对同一消费组清空 receipt 会失去历史投递去重能力。

### 阶段 B：资源隔离（部分落地）

- 已配置大小为 2 的公共调度池；后续进一步按任务隔离调度池和受限业务执行器；
- 将消费者从 API 进程拆为独立部署；
- 为 API、消费者和迁移任务分别配置数据库连接预算。

回滚：通过部署开关恢复单进程消费者；关闭新执行器入口，但保留数据格式向后兼容。

### 阶段 C：水平扩展

- Kafka 扩分区并提高副本数；
- Redis 切换到高可用部署；
- API 无状态多实例，网关启用限流和健康检查；
- PostgreSQL 增加只读副本或读写分离前，先用慢查询和连接池数据证明必要性。

回滚：分区扩容通常不能直接缩容，必须提前设计兼容的消费者和迁移窗口；网关和实例数可通过部署编排快速恢复到上一版本。

## 9. 面试中的准确表述

本次代码完成后可以这样描述；吞吐数字只引用实测报告：

> 我从高并发时资源耗尽和数据正确性两个方面做了改造。入口通过单实例并发准入保护后端，过载返回 429；热点缓存未命中用单飞合并回源，用代次防止本机旧值回填，事务内绕过缓存、提交后才失效。登录计数通过 Lua 保证自增和 TTL 原子完成，模拟器通过 volatile 和批次锁保护共享状态。Kafka 分区和消费并发可配置，失败有限重试后进入 DLT；为避免重试重复插入，我用 group/topic/partition/offset 生成 receipt 主键，与交通数据和告警一起提交。这个去重范围是相同投递位置，不是所有业务重复。我还设置了 Tomcat、Hikari 和数据库执行预算，提供区分成功吞吐和 429 的压测证据。跨实例缓存一致性、不同 offset 的业务 eventId 去重和高可用集群是后续扩展项。

完成阶段 A/B/C 并取得压测报告后，才能把“支持高并发”替换成带条件和数据来源的简历描述。
