# 项目完善：回归场景、复现与验收边界

本文件记录 2026-10-17 版本的验证入口。它与 [项目学习地图](../project-guide.md)、[API 契约](../api-reference.md) 配合使用。历史 [2026-10-16 高并发证据](evidence/high-concurrency/README.md) 保持原样；当前认证增加每请求数据库账号查询，旧吞吐量不外推到当前版本。

最终本地全量验证于2026-10-17 01:24完成：149个Java测试（失败/错误/跳过均0）、25个前端测试（7个套件）、新旧数据库两类迁移及重复执行、Java打包与前端构建通过。执行环境与报告见[本轮证据](evidence/project-completion/README.md)。本轮最后的Docker镜像重建受本机Docker Desktop启动故障影响，验证使用WSL原生依赖；镜像重建与GitHub CI状态单独记录。

## 1. 一键执行与结果入口

环境要求：Docker Desktop、JDK17、Maven、Node22/npm、Python3.11、PowerShell7。仓库根目录：

```powershell
pwsh -File .\scripts\verify-project.ps1
```

脚本 [verify-project.ps1](../../scripts/verify-project.ps1) 创建独立 Compose 测试项目，执行迁移验证、Maven clean verify、前端安装/测试/构建。默认结束清理该测试项目；`-KeepDependencies` 保留依赖用于调试，`-SkipInstall` 复用已安装前端依赖。

已有独立的WSL依赖时，可以执行 `pwsh -File ./scripts/verify-project.ps1 -WslDistribution Ubuntu-24.04 -SkipInstall`。此模式只连接预先准备的55432/16379/19092及固定测试库，不启动或停止依赖；演示库使用单独的itms_demo和Redis DB2。依赖准备条件见部署文档，日常数据库不要放入这些测试库名。

报告目录：

| 内容 | 位置 |
| --- | --- |
| Java 普通测试 | backend/target-completion/surefire-reports |
| Java 集成测试 | backend/target-completion/failsafe-reports |
| 一键摘要 | output/verification/summary.json |
| 前端测试 | Jest 终端报告 |
| 前端构建 | frontend/build |
| HTTP 压测 | 指定 output-dir 下的 JSON 与 Markdown |

确切用例数量、失败/跳过数与执行结果由本轮报告记录；执行入口与场景定义本身不构成“已通过”的证据。

一键摘要每轮重写，status=passed 才是该轮全部成功；failed 或遗留的 running 需要排查。测试JVM默认768MB堆、384MB元空间、4个可用CPU，可用 `-Ditms.test.jvm.args=...`覆盖；脚本在未设置环境变量时给Maven512MB、Node1536MB堆上限。Docker镜像构建和完整回归建议顺序执行，避免本机资源争抢导致引擎或JVM退出。

## 2. 隔离组件：为何与日常 Compose 分开

[compose.test.yml](../../compose.test.yml) 启动 PostgreSQL16、Redis7、Kafka7.5 单节点。默认回环端口 55432/16379/19092，避免占用日常5432/6379/9092。

PostgreSQL 使用独立 itms_concurrency_it、itms_auth_it、itms_logs_it、itms_kafka_it 等测试库；迁移脚本使用固定测试库 itms_migration_it。Redis 测试分配独立 DB 编号。测试会清理自己使用的表/Key，所以 `test.*.isolated=true` 只是使用者确认隔离的标志，不会把一个业务库自动变成隔离库。

手动复现完整后端组（仓库根目录）：

```powershell
docker compose -f compose.test.yml -p itms-completion-tests up -d --wait --wait-timeout 180
python scripts/verify-migrations.py --project itms-completion-tests
mvn -B -ntp -f backend/pom.xml '-Ditms.build.directory=target-completion' -Pconcurrency-it '-Dtest.postgres.port=55432' '-Dtest.postgres.isolated=true' '-Dtest.redis.port=16379' '-Dtest.redis.isolated=true' '-Dtest.kafka.port=19092' '-Dtest.kafka.isolated=true' clean verify
```

本地 `ITMS_TEST_*_PORT` 环境变量若覆盖 Compose 默认值，Maven 参数也需同步；一键脚本自动协调。手动排查结束：

```powershell
docker compose -f compose.test.yml -p itms-completion-tests down -v
```

这里的 -v 仅针对明确命名的隔离测试项目。日常业务停止使用 `docker compose down` 保留数据。

## 3. 用例设计方法

一个用例由四件事构成：业务承诺、最小输入、动作/并发时序、可观察断言。预期值从业务定义手算或独立定义，避免调用被测实现再生成它自己的“正确答案”。

并发测试优先用 CountDownLatch/栅栏控制开始与提交顺序；需要真实 TTL 的用例使用真实 Redis 并有界等待；数据库事务/约束/锁依赖真实 PostgreSQL；Kafka 通过真实 Broker 观察投递和消费结果。

## 4. 后端回归场景

| 场景 | 测试类 | 核心判定 |
| --- | --- | --- |
| 参数与异常契约 | ApiErrorContractTest | 错误JSON/类型/参数为400，约束冲突409，容量异常503，保留Retry-After |
| 默认用户初始化 | DefaultUserInitializerTest | 新用户BCrypt；已有用户默认不重置；明确重置或旧密码迁移按约定执行 |
| 登录/认证 | AuthServiceTest、JwtAuthenticationFilterTest、RedisAuthFailureTest | 密码与禁用校验、锁定429、当前DB角色/版本、Redis/DB异常503 |
| 角色边界 | SecurityBoundaryTest | ADMIN/MANAGER/DRIVER读写矩阵与401/403 |
| 用户管理 | UserManagementTest、UserManagementPostgresIT | DTO校验、响应不泄露密码、唯一约束、最后管理员保护、并发账号删除与旧Token失效 |
| 用户状态竞态 | UserManagementPostgresIT | 旧登录时间更新不覆盖新密码或禁用状态 |
| 路线归属 | RouteOwnershipTest | 当前主体决定userId，冒用他人ID为403 |
| 审计身份与筛选 | SystemLogActorTest、SystemLogPostgresIT | 真实操作者，组合条件AND，稳定分页，全量聚合 |
| 交通查询/数值 | TrafficDataServiceImplTest、TrafficBusinessContractTest | 加权速度、空结果、最新方向、参数上下界、模拟算法标记 |
| 最新与历史SQL | TrafficBusinessPostgresIT | 每路口/方向一个最新值，同时间按id；DB端LIMIT，输出时间正序 |
| 交通事务 | TrafficDataTransactionTest、TrafficBusinessPostgresIT | 单条/整批告警失败回滚；事务内绕过缓存；提交后失效 |
| 缓存并发 | TrafficDataConcurrencyTest、RedisApplicationIT | 同Key合并回源、等待上限、旧版本不回填、过期与失效真实生效 |
| 信号状态 | TrafficBusinessPostgresIT | 并发初始化无重复方向，候选/应用分离，周期约束 |
| 告警状态 | TrafficBusinessPostgresIT | 来源/方向隔离，同条件并发只一条活动告警，终态保护、恢复与迟到记录处理 |
| 预测事务 | TrafficBusinessPostgresIT | 告警处理失败回滚整个预测批次；低风险结束对应预测告警 |
| 接口准入 | RequestAdmissionTest | 满额429、CORS头、结束/异常归还许可、预检行为 |
| 模拟器竞争 | TrafficSimulatorConcurrencyTest | 手动与定时批次互斥、忙时503/跳过 |
| Kafka配置与消费分支 | KafkaConsumerConfigTest、KafkaTopicConfigTest、TrafficKafkaConsumerTest | Topic/DLT配置、重试边界、坏消息、恢复失败处理 |
| 消费回执事务 | TrafficIngestionServiceImplTest、TrafficIngestionPostgresIT | 同组同offset并发只一条，失败回滚可重试，提交后失效，数据库等待有界 |
| 业务事件幂等 | TrafficEventIdPostgresIT | 同eventId跨HTTP/Kafka/offset只一条、内容冲突409、并发重放、整批冲突回滚 |
| 告警乱序 | TrafficAlertOrderingPostgresIT | 首次畅通后迟到拥堵不新建；旧恢复不关闭新告警；同时间按id判定；真实事务等待后读取最新状态 |
| 路线数据缺失 | RouteTrafficFreshnessTest、前端DataViews | 空库/过期/单端缺失返回未知，仍可做静态估算；完整端点数据才展示采样等级 |
| 模拟器投递故障 | TrafficSimulatorDeliveryTest | 同步/异步发送失败沿用eventId落库，批内同步失败避免重复阻塞 |

事件ID、告警乱序、路线缺数据与模拟器投递分别由上述用例覆盖，执行计数以最终生成报告为准。

## 5. 真实 Kafka 到数据库链路

KafkaWorkflowIT 使用真实 Kafka、PostgreSQL、Redis和应用消费配置：

1. 投递合法交通消息，等待数据库业务记录与消费回执落库。
2. 投递坏消息，观察有限重试后进入对应 DLT。
3. 再投递合法消息，验证坏消息没有永久阻断分区后续消费。
4. 验证已处理消息的数据库副作用，结合消费回执确认完成边界。

这验证本地单 Broker 的投递/消费/DLT流程。多 Broker 故障转移、分区扩容时的容量、网络分区、DLT 持久运维重放工具仍需单独验证。默认副本数1不构成高可用集群。

## 6. 迁移验证

[verify-migrations.py](../../scripts/verify-migrations.py) 在固定测试库验证：

- 空库初始化后的迁移。
- 带旧账号与业务数据的升级，保留已有账号状态。
- 重复执行迁移的幂等性。
- 必需索引、事件ID/摘要、认证版本、候选信号等结构。
- 重复旧信号先归档，再建立方向唯一约束。
- 历史未验证模型标记改为 SIMULATION-legacy，清理无依据的置信度。

迁移实验通过与真实业务库升级完成是两件事；升级现有部署先备份、阅读迁移 SQL 并核对应用版本。初始化脚本不会自动重新应用到已有 PostgreSQL 数据卷。

## 7. 前端验证与手动验收

在 frontend 目录：

```powershell
$env:CI = 'true'
npm ci --legacy-peer-deps --no-audit --no-fund
npm test -- --watchAll=false --runInBand
npm run build
```

api/useResource 测试覆盖401会话清理、403/429/503提示、Retry-After、请求取消与请求状态。App/MainLayout 关注角色页面和会话；BusinessFlows/DataViews/UserManagement 关注页面状态、业务操作、用户表单和展示契约。具体断言以当前测试文件为准。

再在真实浏览器按顺序验收：

1. 三种角色登录，检查页面/按钮与服务端权限一致。
2. 暂停模拟器后手动生成一批，确认实时和历史变化。
3. 切换路口、等待一次刷新，选择保持；无数据展示空态。
4. 触发模拟预测后批次变化；只刷新查询时批次不变。
5. 生成信号候选，当前方案保持；应用后当前方案变化。
6. 规划路线，历史属于当前用户；单位为km/min/g。
7. 已读/处理告警后状态刷新；处理人是实际登录账号。
8. 管理员创建、修改、禁用测试用户，查看组合筛选日志。
9. 退出后旧Token访问返回401；普通错误不误清登录状态。

浏览器验收需单独记录所用构建与日期；Jest 和构建成功不替代真实浏览器记录。

## 8. 当前版本 HTTP 小规模压测

工具：[traffic_load.py 用法](../../backend/scripts/load/README.md)。只在本机演示服务或明确隔离的测试环境运行。先准备数据，再停止模拟器形成可重复读负载；当前交通GET不会自动造数，旧文档依赖GET初始化56行的步骤已失效。

仓库根目录 PowerShell：

```powershell
$base = 'http://127.0.0.1:8080'
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body '{"username":"admin","password":"123456"}'
$headers = @{ Authorization = "Bearer $($login.data.token)" }
Invoke-RestMethod -Method Post -Uri "$base/api/traffic/simulator/stop" -Headers $headers
Invoke-RestMethod -Method Post -Uri "$base/api/traffic/simulator/generate-once" -Headers $headers | Out-Null
# 再查询并确认Kafka消费后的记录已出现，随后开始压测。
Invoke-RestMethod -Uri "$base/api/traffic/realtime" -Headers $headers
$env:TOKEN = $login.data.token
python backend/scripts/load/traffic_load.py --base-url $base --requests 1000 --concurrency 5 --timeout 3 --warmup-requests 100 --output-dir output/load-current
Remove-Item Env:TOKEN
```

同样条件下逐级试1/5/10/20并发，比较成功RPS、P95/P99、429/5xx/超时比例，再决定是否加压。该工具是闭环固定并发，每worker同时一个请求；429计入错误率，不算成功吞吐。

每轮至少记录Git提交、机器CPU/内存、JVM参数、数据库行数、模拟器/监听器是否开启、认证方式、请求组合、并发、时长、服务端Hikari active/pending与异常。当前每请求会查用户表，所以旧2791.47成功RPS仅属于2026-10-16旧版条件。

功能回归证明行为，压测报告描述指定条件下容量；当前文档没有通过“新增更多测试”推导新的QPS。
