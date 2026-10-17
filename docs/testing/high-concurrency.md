# 高并发改造：测试边界、运行方法与证据

> **版本说明（2026-10-17）：** 下文是 2026-10-16 源码与环境的历史验证报告，66项回归、2791.47成功RPS、Docker故障和“未运行真实Kafka”都属于当时状态。当前鉴权每请求增加用户表查询，交通GET不再自动生成种子数据，登录锁定与eventId幂等规则也已更新；旧第4.6节及种子准备过程只作复盘。当前测试使用独立Docker PostgreSQL/Redis/Kafka，命令和验证范围见 [项目完善回归](project-completion.md)，一键入口见 [quickstart](quickstart.md)。旧压测 evidence 不修改，当前容量需以当前版本重新测量。

本文对应 2026-10-16 的并发改造，设计说明见 [高并发方案](../architecture/high-concurrency-plan.md)。2026-10-04 的 [Redis 补测](redis-regression.md) 是历史快照，其中的用例数量、已知限制和环境版本不代表本次结果。

本次测试分成应用逻辑、真实 Redis、真实 PostgreSQL、真实 HTTP 压测四层。每层验证不同事实；一个 MockMvc 用例通过，不等于真实 Kafka Broker 或数据库事务已经验证。

## 1. 当前执行与证据状态

本轮已实际执行全部回归及 HTTP 压测。完整参数、逐轮结果、服务端采样及源码/JAR 校验值见 [实测证据](evidence/high-concurrency/README.md)。

| 验证层 | 证据位置/方式 | 当前文档记录 |
| --- | --- | --- |
| Java 普通回归 | `backend/target-concurrency/surefire-reports/TEST-*.xml` | 51 项通过，零失败/错误/跳过 |
| Redis 组件集成 | `backend/target-concurrency/failsafe-reports/TEST-*.xml` | 11 项真实 Redis 测试通过 |
| PostgreSQL 事务/SQL | 同上，`TrafficIngestionPostgresIT` | 4 项通过：并发去重、失败回滚重试、提交后缓存失效、会话超时 |
| HTTP 容量/过载 | [原始 JSON/Markdown](evidence/high-concurrency/README.md) | 5 轮共 190,000 请求；50/100 并发触发 429，未通过错误率阈值 |
| Python 工具自测 | `backend/scripts/load/test_traffic_load.py` | 3 项通过；工具分类验证不代表业务容量 |

持续读场景：28,056 条记录，20 并发，150,000 次请求，53.735 秒全部成功；成功吞吐 2791.47 请求/秒，P95 14.238 ms，P99 24.374 ms。应用与压测机同机，JVM `-Xms256m -Xmx512m -XX:ActiveProcessorCount=4`，关闭模拟器和 Kafka listener，Redis 缓存开启。这个结果有上述负载和环境条件，未覆盖生产集群或混合写入容量；未运行真实 Kafka Broker。

本轮外部组件验证采用 **WSL Redis 7.0.15、PostgreSQL 16.15**；Docker runtime 当前故障，故未将 Docker Compose 启动结果作为本轮证据。使用 WSL 组件验证不改变应用接口，但环境、版本和网络路径要随报告保留。

## 2. 用例如何规定

每条用例从一个承诺或故障出发，使用可手算输入和可控制时序，检查响应及副作用。并发测试用锁存器控制“旧查询开始 → 写入提交 → 旧查询结束”等顺序，避免依靠随机 `sleep` 碰运气。

| 测试类/场景 | 模拟的风险 | 核心通过条件 | 外部依赖边界 |
| --- | --- | --- | --- |
| `RequestAdmissionTest` | 正在处理的请求已占满预算，又来新请求 | 新请求为 HTTP/业务码 429，带 `Retry-After: 1`，CORS 暴露此头；原请求结束/异常后名额归还；预检可通过 | 真实 Spring Security/MockMvc，无真实网络服务器 |
| `RequestAdmissionTest` 的 503 分支 | 缓存重建忙或数据库容量异常被误转成 400 | HTTP 状态和 body code 都保留 503，并带 `Retry-After: 1` | 真实异常处理器；已映射 QueryTimeout/CannotCreateTransaction/DataAccessResourceFailure/CannotAcquireLock |
| `TrafficDataConcurrencyTest` | 并发未命中或 Redis 故障造成大量相同查询 | 同实例同 Key/代次仅一个数据库加载，跟随者复用结果 | 真实 Service/并发协调，Redis/Repository mock |
| 旧值回填场景 | 旧查询返回前新写已失效缓存 | 旧结果可以返回给原调用者，但不再写回共享缓存 | 通过锁存器控制时序 |
| summary 依赖场景 | 汇总关联仍在执行的旧实时查询 | 新代次不加入旧实时 flight，旧依赖不污染新 summary | 真实两层缓存调用 |
| DEL/SET 交错场景 | DEL 尚未完成，summary 读到旧依赖 | DEL 完成后才推进代次，旧代次结果不发布 | 检查缓存发布与删除互斥 |
| 跟随者超时场景 | 一个等待请求超时就删除 owner flight | 返回 503，但 owner 保留；后来请求不另开相同回源 | owner 结束后才释放 flight |
| owner 失败场景 | 首次回源失败，后续请求永远等待失败对象 | 首次错误透传并清理 flight；后续能重新加载 | 真实完成/异常传播 |
| `TrafficDataTransactionTest` | 事务未提交便缓存新值或删除旧值 | 事务内查询绕过共享缓存/flight；afterCommit 才失效；回滚不失效 | 手工事务同步上下文，未代表真实 PG 回滚 |
| `TrafficSimulatorConcurrencyTest` | 手动生成与定时生成同时修改方向状态 | 一批生成及发起发送串行，定时遇忙跳过、手动返回 503 | Kafka send 边界 mock；未覆盖异步发送完成顺序 |
| `RedisAuthFailureTest` | 自增成功但设置 TTL 失败产生永久计数 | 调用单次 Lua；返回计数或抛出错误，不伪装成零 | Redis mock 用于故障分支 |
| `RedisApplicationIT` | Lua、TTL 和真实序列化语义错误 | 并发自增不丢计数、窗口不滑动、无 TTL 旧 Key 被修复、缓存过期真实生效 | 真实 Redis/Lettuce/JWT，DB 与告警 mock |
| `KafkaTopicConfigTest` | 源 Topic 扩容而 DLT 分区不足 | 源与 DLT 使用同一分区/副本配置；默认保留 1/1 | 配置 Bean，不连接 Broker |
| `KafkaConsumerConfigTest` | 消费错误被吞掉，或 DLT 发送失败被当作恢复成功 | 默认两次重试后发同分区 DLT；DLT 失败仍暴露未恢复 | 真正 Spring Kafka 处理器 + MockProducer，无真实 Broker |
| `TrafficKafkaConsumerTest` | 丢失源 topic/partition/offset 或吞异常 | 完整 ConsumerRecord 传给入库服务，失败继续上抛 | 入库服务 mock |
| `TrafficIngestionServiceImplTest` | 重复 offset 二次插入；失败留下 receipt | 同投递位置只保存一次，不同位置/组区分；业务失败调用事务回滚 | Repository 与事务管理边界 mock，需 PG 实测补充 |
| `TrafficIngestionPostgresIT` | 唯一约束、外层事务、缓存失效只在 mock 中成立 | 真实并发同 offset 只提交一条交通记录；告警故障使 receipt/traffic 回滚，之后可重试；提交后缓存删除；应用 SQL 超时配置有效 | 真实 PostgreSQL、Redis、Spring 事务代理；告警仅故障分支使用 Spy 注入；Kafka listener/admin 关闭 |

## 3. Kafka receipt 的专项验证

本次唯一键不是业务 `eventId`，而是：

```text
SHA256(consumerGroup + 分隔符 + topic + 分隔符 + partition + 分隔符 + offset)
```

对应表为 `traffic_ingestion_receipts`；迁移 SQL 为 [20261016_traffic_ingestion_receipts.sql](../../database/migrations/20261016_traffic_ingestion_receipts.sql)。`INSERT ... ON CONFLICT (event_key) DO NOTHING` 返回影响行数：首次 1，已经处理过则 0。

真实 PostgreSQL 需要单独证明：

1. **顺序重投**：调用同组同 Topic/partition/offset 两次，receipt 为 1 行，交通记录只增加 1 行。
2. **并发重投**：两个事务竞争相同 receipt，最终一个业务提交；另一方在已提交冲突后跳过。
3. **业务失败**：receipt 申领后，交通或告警保存抛异常，整次事务回滚；receipt 和本轮业务数据均不残留。
4. **失败后重试**：前一事务回滚后相同投递位置再次执行，可以申领并成功保存。
5. **提交后重投**：模拟数据库已提交但 offset 尚未提交的情况，再投原位置仍不增加流量。
6. **位置改变**：新 offset 或新消费组是新投递；用例应证明这种边界，不能把它写成业务事件去重已完成。

只运行手写 SQL 能证明 PostgreSQL 唯一约束/事务行为；要证明应用 receipt、交通记录和告警属于同一事务，还需要通过真实 Spring 事务代理调用入库服务，并在相应表检查结果。

本次没有 DLT 自动回放工具。人工重新生产 DLT 内容会获得新 offset，应先核对原事务状态；同 payload 新 offset、模拟器失败回调直写、Topic 重建后 offset 重用均需要业务 eventId 等进一步设计。

## 4. 如何运行

所有 Maven 命令在 `backend` 目录执行。Token、数据库密码保存在本机环境中；提交证据前只保留必要参数和结果。本轮用 `-Ditms.build.directory=target-concurrency` 将构建和报告与 IDE 的默认 `target` 分开，避免互相覆盖。

### 4.1 Java 普通回归

```powershell
mvn -B -ntp '-Ditms.build.directory=target-concurrency' test
```

该命令运行普通 `*Test`；数量以 `target-concurrency/surefire-reports` 本次 XML 为准。需针对性复查时：

```powershell
mvn -B -ntp '-Ditms.build.directory=target-concurrency' '-Dtest=RequestAdmissionTest,TrafficDataConcurrencyTest,TrafficDataTransactionTest,TrafficSimulatorConcurrencyTest,TrafficIngestionServiceImplTest,KafkaConsumerConfigTest,KafkaTopicConfigTest,TrafficKafkaConsumerTest' test
```

### 4.2 真实 Redis 组件集成

Docker 可用时继续使用隔离容器脚本：

```powershell
pwsh -File .\scripts\test-redis.ps1
```

本轮采用 WSL 独立测试 Redis。先在 WSL 中选择一个未占用端口启动专用临时实例，以下以 16379 为例：

```bash
redis-server --bind 127.0.0.1 --port 16379 --save '' --appendonly no \
  --daemonize yes --pidfile /tmp/itms-redis-it.pid --logfile /tmp/itms-redis-it.log
redis-cli -h 127.0.0.1 -p 16379 ping
redis-server --version
```

然后在能连通此回环端口的 Maven 环境中运行：

```powershell
mvn -B -ntp -P redis-it '-Ditms.build.directory=target-concurrency' '-Dtest.redis.port=16379' '-Dtest.redis.isolated=true' verify
```

WSL 网络模式不同可能影响 Windows 到回环端口的映射，先验证连接。集成测试在每条用例前清空专用实例的数据库，因此端口应始终指向本轮临时实例。接下来第 4.3/4.6 节继续使用 16379，全部验证结束后再按第 4.3 节的清理命令停止。

### 4.3 PostgreSQL 配置和事务验证

本轮使用 WSL Ubuntu 24.04 中的 PostgreSQL 16.15，二进制目录为 `/usr/lib/postgresql/16/bin`，专用数据目录为 `/tmp/itms-concurrency-20261016/pg`，端口为 **55432**。验证结束后停止临时实例并保留数据目录；重跑先检查并复用已有目录，不重复 `initdb`，不操作系统默认 PostgreSQL 服务。WSL 需预先安装 PostgreSQL 16 和 Redis，Windows 需具备 JDK 17、Maven、Python。

以下初始化示例在 **WSL 内 bash** 执行。目录不存在时创建；发现已有数据目录但缺少 `PG_VERSION` 时停止，避免覆盖不明文件。`trust` 仅用于这个绑定 `127.0.0.1` 的临时测试实例：

```bash
set -euo pipefail
itms_pg_bin=/usr/lib/postgresql/16/bin
itms_run_dir=/tmp/itms-concurrency-20261016
itms_pg_dir="$itms_run_dir/pg"

sudo install -d -o postgres -g postgres -m 700 "$itms_run_dir"
if sudo test -f "$itms_pg_dir/PG_VERSION"; then
  if [ "$(sudo cat "$itms_pg_dir/PG_VERSION")" != '16' ]; then
    echo 'Existing test data directory is not PostgreSQL 16.' >&2
    exit 1
  fi
elif sudo test -e "$itms_pg_dir"; then
  echo 'Existing data path has no PG_VERSION; inspect it before continuing.' >&2
  exit 1
else
  sudo -u postgres "$itms_pg_bin/initdb" -D "$itms_pg_dir" -A trust -U postgres
fi

if sudo -u postgres "$itms_pg_bin/pg_ctl" -D "$itms_pg_dir" status; then
  echo 'Reusing the running isolated PostgreSQL instance.'
else
  sudo -u postgres "$itms_pg_bin/pg_ctl" -D "$itms_pg_dir" \
    -l "$itms_run_dir/postgres.log" \
    -o "-h 127.0.0.1 -p 55432 -k $itms_run_dir" -w start
fi

"$itms_pg_bin/psql" -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 \
  -U postgres -d postgres -c 'SHOW data_directory;' \
  -c 'SHOW listen_addresses;' -c 'SHOW port;'

for itms_db in itms_concurrency_it itms_concurrency_load; do
  if [ "$("$itms_pg_bin/psql" -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55432 \
    -U postgres -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname='$itms_db'")" != '1' ]; then
    "$itms_pg_bin/createdb" -h 127.0.0.1 -p 55432 -U postgres "$itms_db"
  fi
done
```

检查输出中的数据目录必须为 `/tmp/itms-concurrency-20261016/pg`、监听地址为 `127.0.0.1`、端口为 55432。端口冲突时先识别占用者，不停止系统默认实例。

用应用实际取得的连接核对以下值：

```sql
SELECT version();
SHOW statement_timeout;
SHOW lock_timeout;
```

配套预算为 `statement_timeout=5s`、`lock_timeout=2s`。Hikari 的 2 秒只限制等待取得连接，和 SQL 执行超时是两个不同边界；单独开一个 `psql` 会话看到的值可能与应用初始化后的会话不同。

在专用测试库执行迁移并检查第 3 节的提交/回滚场景。保留每步表计数、预期错误和事务结果，记录是否调用了真实应用入口；禁止把 mock 事务测试记成真实 PostgreSQL 事务证据。

仓库已提供 `TrafficIngestionPostgresIT` 和 Maven `concurrency-it` profile。复现它的环境约定是：

- PostgreSQL 回环地址 `127.0.0.1`，端口由 `test.postgres.port` 提供，数据库名固定 `itms_concurrency_it`。
- 测试配置的 PostgreSQL 用户为 `postgres`、空密码，适用于本轮隔离测试实例；若现有实例要求密码，先准备符合此测试约定的隔离实例，而不是调整业务数据库认证。
- Redis 为同一轮专用实例，端口由 `test.redis.port` 提供；RedisApplicationIT 清理 DB 0，PostgresIT 使用 DB 1。
- PostgresIT 会清理测试库内 receipt/traffic 数据并让 JPA 更新表结构；两个 `isolated=true` 标志是测试前提声明，不会自动创建隔离实例。
- Kafka listener/admin 和模拟器在此测试中关闭，因此测试不需要真实 Broker，也不验证 Broker 的网络、提交 offset 和 DLT 故障恢复。

完成上述 PostgreSQL 初始化和第 4.2 节 Redis 初始化后，在 WSL bash 中进入仓库的 `backend` 目录运行：

```bash
cd /mnt/e/BaiduNetdiskDownload/3D/resume_work/source_repos/itms-bd/backend
mvn -B -ntp -P concurrency-it -Ditms.build.directory=target-concurrency \
  -Dtest.postgres.port=55432 -Dtest.postgres.isolated=true \
  -Dtest.redis.port=16379 -Dtest.redis.isolated=true verify
```

这个 profile 会运行普通测试以及 `*IT`，包括 RedisApplicationIT 和 TrafficIngestionPostgresIT；只验证 Redis 时使用上一节的 `redis-it` profile。报告在 `target-concurrency/surefire-reports` 与 `target-concurrency/failsafe-reports`，打包 JAR 也在 `target-concurrency`。WSL 运行这些命令需要 WSL 内的 JDK 17 与 Maven；若本机工具装在 Windows，可在 Windows backend 目录运行等价命令，但先确认两个回环端口可达。

完成**全部集成测试和 HTTP 压测**后，在 WSL bash 中只停止本轮专用实例，保留目录供核查与复跑：

```bash
sudo -u postgres /usr/lib/postgresql/16/bin/pg_ctl \
  -D /tmp/itms-concurrency-20261016/pg -m fast -w stop
redis-cli -h 127.0.0.1 -p 16379 shutdown nosave
```

停止前先结束仍连接它们的压测服务。这里不执行 `systemctl stop postgresql`、不停止 6379 上的业务 Redis，也不删除数据目录；`/tmp` 目录在系统清理后可能消失，长期复现时需重新初始化。

### 4.4 Python 工具自测

```powershell
python -m unittest discover -s .\scripts\load -p test_traffic_load.py -v
```

本地假服务测试检查成功、429/5xx/业务错误分类、远程地址需显式参数及 Token 不出现在证据中。

### 4.5 实际 HTTP 混合读压测

先启动完整后端，取得测试用户 JWT 后注入 `TOKEN` 环境变量。脚本固定混合 realtime/heatmap/summary 三个 GET 接口：

```powershell
$env:TOKEN = 'JWT_TOKEN'
python .\scripts\load\traffic_load.py `
  --base-url http://127.0.0.1:8080 `
  --requests 1000 --concurrency 10 --timeout 3 `
  --warmup-requests 60 --output-dir .\target\load-results
Remove-Item Env:TOKEN
```

分两类运行：

- **正常负载**：例如并发 1、10、20，记录成功吞吐、P95/P99 和依赖状态。
- **过载保护**：逐级提升到并发 50、100，确认返回 429、名额释放、系统恢复；此时有意触发的 429 仍然是业务请求失败，默认错误率阈值可能令工具退出 1。过载保护机制正常，不等于该负载满足容量目标。

`completed_rps` 是所有完成请求速率；`success_rps` 只包括 HTTP 2xx 且 body.code=200。每次保存 JSON/Markdown；Token、响应正文均不落盘。报告包含的是**压测机** CPU/内存，服务器和容器资源要另记。

HTTP 契约同时检查响应头：过载 429、缓存等待/模拟器忙 503，以及全局异常处理器识别的数据库容量异常 503，均应有 `Retry-After: 1`。带 `Origin` 的跨域请求还应有 `Access-Control-Expose-Headers: Retry-After`，让前端读取退避提示。此处仅覆盖已实现的异常路径；Kafka 消费错误走容器重试/DLT，不产生 HTTP 503。

默认脚本 socket 超时为 3 秒，小于 PostgreSQL 5 秒语句预算。若专项观察 SQL 超时如何映射为 HTTP 503，可使用 `--timeout 8` 给应用返回错误留出时间，并单独保存结果；3 秒脚本超时不代表服务端 SQL 已被取消。

### 4.6 本轮 28,056 行数据的 HTTP 复现

本轮后端和压测客户端运行在 Windows，PostgreSQL/Redis 运行在 WSL。HTTP 端口为 **18080**；数据库为 `itms_concurrency_load`，与第 4.3 节会被测试清理的 `itms_concurrency_it` 分离；缓存使用专用 Redis 的 **DB 2**。Kafka listener/admin 与模拟器关闭，因此这组结果只对应 HTTP 读链路。

先完成第 4.3 节并取得 `backend/target-concurrency/itms-bd-backend-1.0.0.jar`。然后在 **Windows PowerShell** 的 `backend` 目录，用 JDK **17.0.12** 启动后端，保持此终端运行：

```powershell
java -Xms256m -Xmx512m -XX:ActiveProcessorCount=4 '-Dfile.encoding=UTF-8' `
  -jar .\target-concurrency\itms-bd-backend-1.0.0.jar `
  '--server.address=127.0.0.1' '--server.port=18080' `
  '--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/itms_concurrency_load' `
  '--spring.datasource.username=postgres' '--spring.datasource.password=' `
  '--spring.data.redis.host=127.0.0.1' '--spring.data.redis.port=16379' `
  '--spring.data.redis.database=2' `
  '--spring.kafka.listener.auto-startup=false' '--spring.kafka.admin.auto-create=false' `
  '--itms.traffic.simulator.enabled=false'
```

`-XX:ActiveProcessorCount=4` 只是让 JVM 按 4 个可见处理器配置内部并行度，**不是操作系统的 4 核硬配额或 CPU 亲和性限制**。`-Xmx512m` 是 Java 堆上限，也不等同于整个进程的内存上限。Tomcat 64 线程、在途 API 24、Hikari 20 等仍使用本次默认配置。

在第二个 **Windows PowerShell** 终端进入 `backend`。服务健康后登录，将 Token 保存在环境变量中，不打印登录响应或 Token；首次实时查询在空表中建立 14 路口 × 4 方向共 56 条种子记录：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
$itmsLoginBody = @{ username = 'admin'; password = '123456' } | ConvertTo-Json -Compress
$itmsLogin = Invoke-RestMethod http://127.0.0.1:18080/api/auth/login `
  -Method Post -ContentType 'application/json' -Body $itmsLoginBody
if ($itmsLogin.code -ne 200) { throw 'Isolated load-test login failed' }
$env:TOKEN = $itmsLogin.data.token
$itmsHeaders = @{ Authorization = ('Bearer ' + $env:TOKEN) }
$null = Invoke-RestMethod http://127.0.0.1:18080/api/traffic/realtime -Headers $itmsHeaders
Remove-Variable itmsLogin,itmsLoginBody,itmsHeaders
```

接着切到 **WSL 内 bash**，运行仓库保存的 [seed_history.sql](../../backend/scripts/load/seed_history.sql)。该文件与本轮已执行的 `backend/target-concurrency/seed_history.sql` 原样一致：先断言库名必须为 `itms_concurrency_load` 且恰好有 56 行，再为每条记录生成 500 个五秒间隔的历史样本，执行 `ANALYZE`。总量为 `56 + 56 × 500 = 28,056`，该 SQL 不是可重复追加脚本。

```bash
cd /mnt/e/BaiduNetdiskDownload/3D/resume_work/source_repos/itms-bd/backend
itms_pg_bin=/usr/lib/postgresql/16/bin
itms_load_rows=$("$itms_pg_bin/psql" -X -v ON_ERROR_STOP=1 \
  -h 127.0.0.1 -p 55432 -U postgres -d itms_concurrency_load \
  -Atc 'SELECT count(*) FROM traffic_data;')
if [ "$itms_load_rows" = '56' ]; then
  "$itms_pg_bin/psql" -X -v ON_ERROR_STOP=1 \
    -h 127.0.0.1 -p 55432 -U postgres -d itms_concurrency_load \
    -f scripts/load/seed_history.sql
elif [ "$itms_load_rows" = '28056' ]; then
  echo 'Reusing the existing 28,056-row load fixture; not executing seed SQL again.'
else
  echo "Unexpected fixture row count: $itms_load_rows; inspect this isolated database first." >&2
  exit 1
fi
```

回到持有 `TOKEN` 的 **Windows PowerShell** 终端，复现本轮梯度请求数与持续请求数；结果写到新的本地输出目录，不覆盖仓库内历史证据：

```powershell
foreach ($itmsConcurrency in 10,20,50,100) {
  python .\scripts\load\traffic_load.py `
    --base-url http://127.0.0.1:18080 --requests 10000 `
    --concurrency $itmsConcurrency --warmup-requests 300 --timeout 3 --p95-ms 200 `
    --output-dir .\target-concurrency\load-reproduction
  if ($LASTEXITCODE -gt 1) { throw 'Load tool setup or execution failed' }
}
python .\scripts\load\traffic_load.py `
  --base-url http://127.0.0.1:18080 --requests 150000 `
  --concurrency 20 --warmup-requests 300 --timeout 3 --p95-ms 200 `
  --output-dir .\target-concurrency\load-reproduction
Remove-Item Env:TOKEN
```

退出码 1 代表该轮未达到设定错误率/P95 阈值，结果仍需保存分析；脚本不把 429 算成成功。上面复现的是请求模型与数据准备，服务端指标另按第 5 节同步采集。机器、系统负载、缓存状态、历史数据清理任务和运行时长均可能影响结果，重新运行的数据应作为新报告记录。

全部结束后，在第一个启动 Java 的 PowerShell 终端按 `Ctrl+C` 停止该后端，再使用第 4.3 节的 WSL 清理命令，仅停止专用目录下的 PostgreSQL 和 16379 Redis。

## 5. 运行时观察

Actuator 的 `health`、`info` 可匿名查看；`metrics` 需通过 JWT 认证。可以使用与业务压测相同的环境变量：

```powershell
$headers = @{ Authorization = ('Bearer ' + $env:TOKEN) }
Invoke-RestMethod http://127.0.0.1:8080/actuator/metrics/itms.http.admission.active -Headers $headers
Invoke-RestMethod http://127.0.0.1:8080/actuator/metrics/itms.http.admission.rejected -Headers $headers
Remove-Variable headers
```

补充采集 Hikari 活跃/等待连接、JVM 堆/GC、Redis 延迟、PostgreSQL 慢查询和 Kafka lag。仅指标端点可访问不代表已经部署 Prometheus 或建立告警。

## 6. 最终证据需要包含什么

执行后应追加到本文或链接独立报告：

| 项目 | 应记录内容 |
| --- | --- |
| 代码 | Git 提交号、工作区是否有未提交改动 |
| 机器 | 压测机与服务器分别记录 CPU/内存/系统；同机竞争也要注明 |
| 组件 | JDK/Maven、Redis、PostgreSQL、Kafka 版本；本轮是否启用真实 Kafka |
| 数据 | 路口数、交通记录数、缓存冷热、模拟器是否开启 |
| 参数 | Tomcat、入口上限、Hikari、SQL 超时、Kafka 分区/消费者数 |
| 测试 | 普通/集成用例数、失败/错误/跳过数、报告时间 |
| 性能 | 请求数、并发、时长、成功数、429/5xx/其他错误、成功 RPS、P95/P99 |
| 故障 | 故障注入时间、响应行为、恢复后的连接/缓存/lag 状态 |

本次结构化测试和工具为可复现证据提供入口。实际吞吐结论只对应被记录的环境与负载；单实例读压测也不能代替多实例一致性、真实 Broker 故障或长期数据增长测试。
