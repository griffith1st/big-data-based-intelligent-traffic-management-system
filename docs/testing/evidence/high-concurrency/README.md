# 2026-10-16 高并发改造实测证据

本轮完成 **66 项 Java 测试、3 项 Python 工具自测，以及 5 轮真实 HTTP 混合读压测**。Java 零失败、零错误、零跳过。详细方案见 [架构方案](../../../architecture/high-concurrency-plan.md)，执行方法见 [测试说明](../../high-concurrency.md)。

## 环境与范围

- Windows 11 / Intel Core i9-12900HX（16 核、24 逻辑处理器），可用物理内存约 31.7 GiB。应用、Python 压测机和 WSL 组件在同一台电脑，存在资源竞争。
- Oracle JDK 17.0.12、Maven 3.9.16、Python 3.11.3；Ubuntu 24.04 WSL 中独立 Redis 7.0.15、PostgreSQL 16.15。
- JVM：`-Xms256m -Xmx512m -XX:ActiveProcessorCount=4`。最后一项控制 JVM 线程池等参数估算，**不是操作系统的 4 核硬配额**。
- 应用地址 `127.0.0.1:18080`；Tomcat 64 线程，API 在途预算 24，Hikari 上限 20；SQL 预算 5 秒、锁等待 2 秒。
- 数据库 `itms_concurrency_load` 中 28,056 行：14 个路口 × 4 个方向 × 501 个时间点。首次实时查询生成 56 行，再用 [seed_history.sql](../../../../backend/scripts/load/seed_history.sql) 增加 28,000 条历史行。
- HTTP 压测用 Redis DB 2；缓存 TTL 5 秒，未禁用缓存。模拟器、Kafka listener/admin 均关闭；没有后台持续写入。
- 三个接口按约 1:1:1 轮询：realtime、heatmap、summary。闭环模型，每个 worker 一次只发一个请求，复用 HTTP 连接；无自动重试。每轮预热 300 请求，不计入正式结果。
- 通过标准：错误率 ≤ 1%，全部请求 P95 ≤ 200 ms。429 计入失败，HTTP 2xx 且业务码 200 才计成功。

## HTTP 结果

| 并发客户端 | 请求数 | 时长/秒 | 成功 | 429 | 5xx | 成功 RPS | 全请求 P95/ms | 阈值结果 | JSON |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 10 | 10,000 | 6.410 | 10,000 | 0 | 0 | 1560.15 | 20.829 | PASS | [原始数据](traffic-load-20261015T165059133386Z.json) |
| 20 | 10,000 | 3.659 | 10,000 | 0 | 0 | 2732.70 | 16.226 | PASS | [原始数据](traffic-load-20261015T165103405874Z.json) |
| 50 | 10,000 | 2.916 | 9,429 | 571 | 0 | 3233.49 | 27.767 | FAIL | [原始数据](traffic-load-20261015T165106847852Z.json) |
| 100 | 10,000 | 2.817 | 8,443 | 1557 | 0 | 2997.09 | 54.353 | FAIL | [原始数据](traffic-load-20261015T165110284951Z.json) |
| 20 | 150,000 | 53.735 | 150,000 | 0 | 0 | 2791.47 | 14.238 | PASS | [原始数据](traffic-load-20261015T165323184434Z.json) |

五轮总计 190,000 次正式请求；没有 5xx、业务码错误或客户端超时。50/100 并发的错误率分别为 5.71%/15.57%，**未通过 1% 的容量验收阈值**，但实际触发了入口拒绝保护。随后 20 并发持续轮全部成功，说明本轮过载后请求处理恢复正常。

首轮短测包含更明显的 JVM 预热影响，后续轮复用同一进程；不能用这几行直接推导并发增加造成的性能提升。没有改造前对照压测，也不据此宣称提升百分比。

持续轮约 54 秒，覆盖多个 5 秒缓存周期，P99 24.374 ms，最大 163.382 ms。这是本机单实例读接口的短期结果，未验证小时级稳定性、更多历史数据、写入混合负载或生产容量。报告名使用 UTC（2026-10-15），本地测试日期为 UTC+8 的 2026-10-16。

## 服务端观察

`server-metrics-*.json` 保留了认证后的 Actuator 快照。约每 0.5 秒加采集耗时采一轮，含预热；采样不连续，因此峰值只是“采到的最大值”。

持续轮 90 个样本中：

- `hikaricp.connections.pending` 均为 0，连接获取超时累计计数前后均为 0。
- `itms.http.admission.active` 最大采到 20；拒绝计数前后都是 2144，没有新增拒绝。2144 包含之前过载轮的预热，正式请求 429 为 2128，两者统计口径不同。
- Hikari active 样本都是 0，仅说明短查询未在抽样时被捕获，**不代表没有查数据库**。
- JVM memory.used 最大约 362.3 MiB，包含堆与非堆池，不等同于 Xmx 或进程 RSS；进程 CPU usage 最大样本约 0.1465（保留监控指标原始口径）。
- GC 累计暂停时间增加约 0.368 秒；样本及计数保留在 [持续轮指标](server-metrics-sustained-c20.json)。

Redis 前后快照显示命令增加 351,477、过期 Key 增加 20，驱逐数和拒绝连接数保持 0。全局 hits/misses 包含 JWT 黑名单不存在的 Key 查询，**不把这个比例当作交通缓存命中率**。

[PostgreSQL 末次累计快照](postgres-final-stats.csv) 记录死锁为 0。其中累计 rollback=1 来自采集统计时一条引号解析错误的诊断 SQL，已修正；它不是 HTTP 请求失败。该快照是整个临时数据库的累计数，不是逐场景的写入性能证据。

## 自动化正确性验证

[verification-summary.json](verification-summary.json) 保留每个 Java suite 和 testcase 的名称、数量与执行时间：

| 类别 | 数量 | 结果 |
| --- | ---: | --- |
| Java 普通测试 | 51 | 全部通过 |
| 真实 Redis 集成 | 11 | 全部通过 |
| 真实 PostgreSQL + Redis 集成 | 4 | 全部通过 |
| Python 压测工具自测 | 3 | 全部通过 |

PostgreSQL 用例通过真实 Spring 事务代理验证：12 路相同 Kafka 投递并发只保存一行；注入告警异常使 receipt 和交通记录一起回滚，相同 offset 可重试；提交后删除两份 Redis 缓存；应用连接的 statement/lock timeout 为 5s/2s。

Kafka 错误处理使用真实 Spring Kafka 处理器及 MockProducer；**未运行真实 Kafka Broker 的 offset 提交、重平衡、DLT 网络故障或吞吐测试**。同 offset 的数据库去重已经验证，业务跨 offset 去重和多实例缓存一致性仍未实现。

## 与代码版本对应

压测报告里的 Git commit 是当时基线 `079e144` 的完整 SHA，`working_tree_dirty=true` 表示正在验证本次未提交代码；测试通过后，代码与这份证据一起提交。不能把报告中的基线 SHA 当成“旧版本跑出的性能”。

`verification-summary.json` 同时记录被测试源码的 SHA-256（文本 CRLF 统一为 LF 后计算）和实际启动 JAR 的字节级 SHA-256，便于核对该批改动。日志中可能包含运行环境信息，因此这里提交结构化摘要与性能报告，没有提交 JWT、响应正文或完整启动日志。
