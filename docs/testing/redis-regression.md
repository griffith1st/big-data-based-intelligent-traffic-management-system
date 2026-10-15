# ITMS Redis 补测说明

> 历史快照：本文与 `evidence/` 文件保留的是 2026-10-04 的 28 项补测结果。2026-10-16 高并发改造已更新缓存并发控制、事务提交后失效和登录计数 Lua 等行为；本文当时的 known-limitation、用例数量和 Docker/Redis 版本不代表当前状态。最新设计见 [高并发方案](../architecture/high-concurrency-plan.md)，最新运行方法与验证范围见 [高并发测试说明](high-concurrency.md)。历史证据保留用于追溯。

日期：2026-10-04。基于当前本地工作区补充测试，保留既有业务代码。本次新增测试、测试运行入口、Maven 集成测试 profile 和证据文档。

## 结果与层次

实测 **28 个用例通过，失败 0、错误 0、跳过 0**。

| 测试类 | 数量 | 实际使用的组件 |
| --- | ---: | --- |
| JwtAuthenticationFilterTest | 6 | 真实 JWT 签发/校验、项目过滤器；RedisAuthService mock；Servlet 测试请求 |
| RedisAuthFailureTest | 3 | 真实 RedisAuthServiceImpl 和 JWT；Redis 命令 mock 注入故障 |
| TrafficDataServiceImplTest | 10 | 真实业务 Service、Jackson；Redis/JPA/告警为 mock |
| RedisApplicationIT | 9 | **真实 Redis 7.4.9、Lettuce、JWT、业务 Service、项目过滤器、Jackson**；JPA/告警为 mock |

这是服务与 Redis 的组件集成验证。PostgreSQL SQL/JPA 映射、真实 HTTP 服务器及完整 SecurityFilterChain、Kafka、前端、整体吞吐量不在本次验证范围。Mock 故障注入验证应用分支，真实网络中断、连接超时行为留待后续补测。

环境：Windows、JDK 17.0.12、Maven 3.9.16、Docker Engine 29.7.2；Redis 测试容器为 redis:7-alpine，本轮解析到 Redis 7.4.9。Mockito 运行时有 JVM class-data-sharing 提示，不影响测试断言。历史完成时间和逐用例耗时保存在 evidence/result.json；这些耗时不作为性能指标。

## 如何运行

在项目 backend 目录打开 PowerShell。

普通单元测试，无需 Docker、Redis 或 PostgreSQL：

```powershell
mvn -B -ntp test
```

启动 Docker Desktop 后，执行全部普通测试与真实 Redis 集成测试：

```powershell
.\scripts\test-redis.ps1
```

脚本创建唯一命名的临时 Redis 容器，随机分配一个仅监听 127.0.0.1 的端口；不挂载业务卷、不访问现有 ITMS Redis。测试间只清空该临时容器的数据库，结束或异常时删除自己创建的容器。Docker Desktop 本身继续运行。使用 PowerShell 7 为本轮验证环境；如本机执行策略拦截脚本，按本机策略允许后运行。

Maven 默认 test 只运行 *Test；redis-it profile 通过 Failsafe 在 verify 阶段运行 *IT。集成测试缺少隔离标志/端口会失败，不会静默跳过。完整运行由脚本提供参数：

```text
mvn -B -ntp -P redis-it -Dtest.redis.port=<临时端口> -Dtest.redis.isolated=true verify
```

此参数组合只用于独立测试 Redis；脚本负责建立这个前提。不要把端口指向已有业务 Redis。

报告位置：

- backend/target/surefire-reports：普通测试 XML/文本。
- backend/target/failsafe-reports：集成测试 XML/文本。
- backend/target/redis-test-evidence：本次 Maven 控制台与 Redis 版本。
- 本文同目录 evidence/result.json、cases.tsv：已提取的历史用例结果，不含 Maven 环境属性。

## 用例清单与捕捉的回归

以下方法名与源码、结果报告一一对应。标有“现状”的用例使用 known-limitation 标签；它们通过意味着**复现了现有边界**，不是缺陷已经修复。

### 交通缓存：TrafficDataServiceImplTest（10）

| 方法 | 能捕捉的行为变化 |
| --- | --- |
| cacheHitReturnsPayloadWithoutDatabaseAccess | 缓存命中仍访问数据库、丢失缓存返回值 |
| cacheMissMapsDatabaseProjectionAndCachesJson | DB 投影字段映射错误、缺少 JSON 写回或过期时间 |
| malformedJsonFallsBackToDatabase | 缓存损坏导致业务读取失败 |
| redisReadAndWriteOutageStillReturnsDatabaseResult | Redis GET/SET 异常阻断 DB 结果返回 |
| databaseFailureIsNotHiddenAsEmptySuccess | DB 故障被错误转换为空结果或缓存污染 |
| summaryUsesWeightedSpeedAndWorstCongestion | 车流加权均速被错误替换为简单平均、方向数/拥堵值错误 |
| saveNormalizesAndInvalidatesBothCachesAfterDatabaseAndAlert | 写入规范化丢失、告警/双 Key 删除遗漏或顺序错误 |
| cacheDeleteFailureDoesNotDiscardSuccessfulSave | DEL 失败让已完成保存抛出异常 |
| failedDatabaseSaveDoesNotPublishAlertOrInvalidateCache | DB 保存失败仍产生告警/缓存失效副作用 |
| characterizesAlertFailureSkippingInvalidationAfterSave | 现状：保存成功后告警异常导致两个缓存未删除 |

### JWT 过滤器：JwtAuthenticationFilterTest（6）

| 方法 | 能捕捉的行为变化 |
| --- | --- |
| validTokenCreatesAuthenticatedIdentity | 有效 JWT 未建立正确的认证身份 |
| revokedTokenDoesNotAuthenticate | 已撤销 JWT 仍通过过滤器认证 |
| expiredTokenNeverReachesRedis | 过期 JWT 认证成功或仍查询 Redis |
| malformedTokenNeverReachesRedis | 非法 JWT 认证成功或仍查询 Redis |
| missingTokenDoesNotAuthenticate | 无 Token 请求被设置认证身份 |
| characterizesBlacklistOutagePropagatingFromFilter | 现状：Redis 黑名单异常从过滤器向外传播 |

此组仅断言项目过滤器设置的 SecurityContext；未将其结果描述成完整 HTTP 403/401 集成验证。

### 认证异常：RedisAuthFailureTest（3）

| 方法 | 能捕捉的行为变化 |
| --- | --- |
| invalidOrExpiredTokenDoesNotCreateRevocationEntry | null、空白、非法或已过期 Token 创建撤销记录 |
| characterizesLogoutSwallowingRedisWriteFailure | 现状：有效 Token 的撤销 SET 失败被宽泛捕获 |
| characterizesFailureBetweenIncrementAndExpire | 现状：INCR 成功后 EXPIRE 失败向上传播；mock 注入命令间故障，未宣称复现真实永久 Key |

### 真实 Redis：RedisApplicationIT（9）

| 方法 | 验证闭环 |
| --- | --- |
| logoutRevokesOnlyThatTokenAndUsesRemainingLifetime | 过滤器认证成功 → AuthService.logout → Redis 摘要 Key/剩余 TTL → 原 Token 被拒绝、另一 Token 仍有效 |
| revokedEntryDisappearsWhenTokenLifetimeEnds | 实际短有效期 JWT，Redis 撤销记录自然过期，JWT 同时失效 |
| failureWindowDoesNotRefreshAndSuccessfulClearResetsCounter | 用户名规范化、独立用户计数、首次 600 秒窗口、追加失败不刷新、清理后从 1 开始；中间用测试端缩短窗口为 30 秒，避免等待 10 分钟 |
| fiveWrongPasswordsStillAllowCorrectPasswordAndClearCounter | 现状：五次密码错误真实计数为 5；正确密码仍签发有效 JWT 并清理计数，未实现封禁 |
| invalidAndDisabledUsersDoNotReceiveTokens | 不存在/禁用用户在生成 Token 前失败，不更新用户登录时间 |
| coldQueryPopulatesCacheAndHotQueryAvoidsDatabase | 冷查询真实写 JSON/TTL，热查询避免第二次 Repository 调用 |
| expiredTrafficCacheReloadsChangedDatabaseValue | 等待实际 5 秒 TTL 到期，下一查询读取更新后的数据 |
| summaryUsesRealtimeCacheAndSuccessfulWriteInvalidatesBoth | 汇总复用实时缓存，写入删除两个真实 Key，随后汇总刷新 |
| oldReadCanRefillStaleValueAfterWriterInvalidates | 现状：CountDownLatch 控制旧查询读取/写入/删除/旧值回填时序，证明随后的读取仍命中旧值 |

并发用例：数据库边界返回旧车流 600 并暂停；写路径保存 900、删除缓存；数据库边界切换至 900；恢复旧查询后把 600 写回真实 Redis；下一次读取仍得到 600。使用有超时的 latch/future 和 finally 清理线程，不以碰运气的 sleep 制造交错。

## 验证测试确实能失败

在独立 backend 副本中删除 saveTrafficData 的 evictTrafficCaches 调用，运行 saveNormalizesAndInvalidatesBothCachesAfterDatabaseAndAlert：**1 个用例、1 个断言失败**，原因是两个缓存 Key 没有被删除。原工作区保留原生产代码，全套 28 项通过。失败摘要保存于 evidence/mutation-check.txt。

这是新增既有行为的回归测试，未为了制造红灯修改业务实现。第一次集成运行有一个测试夹具错误：when(...).thenReturn 在重设 stub 时执行了旧的阻塞 Answer；改用 doReturn(...).when(...) 后通过。此失败不是业务缺陷证据。

## 仍需后续处理

1. INCR 与 EXPIRE 非原子；明确要不要真正封禁，以及用户名匹配策略。
2. 黑名单查询失败与撤销写失败的对外语义。
3. 告警失败后的缓存失效、Service 事务边界与并发旧值回填。
4. PostgreSQL/JPA/完整 HTTP 链路验证，以及真实 Redis 网络中断测试。
5. 固定机器、数据量、并发、时长后的 QPS/P95/P99/命中率测量；当前没有新增此类指标。

面试表达：已为认证和交通缓存补充 28 项回归，其中 9 项使用真实 Redis，覆盖撤销、TTL、冷/热缓存和双缓存失效；同时用确定性交错复现旧值回填。数据库与告警采用 mock，所以不把它说成完整系统压测或所有一致性缺陷已解决。
