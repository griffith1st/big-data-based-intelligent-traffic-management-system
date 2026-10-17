# 2026-10-17 项目完善验证证据

对应提交：包含本证据目录的“Complete traffic workflows and add an eight-part learning guide”提交；不将前一版本的压测数据迁移为本版本性能结论。

## 完整回归

仓库根目录执行：

```powershell
./scripts/verify-project.ps1 -WslDistribution Ubuntu-24.04 -SkipInstall
```

2026-10-17 01:24（Asia/Shanghai）结束，退出码0。

| 项目 | 实际结果 |
| --- | --- |
| Java普通测试 | 100，通过；失败/错误/跳过均0 |
| Java真实组件集成测试 | 49，通过；失败/错误/跳过均0 |
| Java合计 | 149，29个测试类；由Surefire/Failsafe XML汇总 |
| Jest | 25，7个套件，通过 |
| 数据库迁移 | 新库初始化+重复迁移、带数据旧库升级+重复迁移，两类通过 |
| 构建 | Maven clean verify打包通过；React生产构建通过 |
| 启动检查 | 最终JAR在18080健康UP，Swagger公开重定向后200；前端13000的SPA路由200 |

[verification-summary.json](verification-summary.json)保留各测试类的实际计数、测试方法名、运行时间，以及最终JAR/前端主包SHA256。完整原始日志保留在执行机器的`%TEMP%/itms-completion-native-final.txt`，原始XML在`backend/target-completion`中；不提交含动态账号/请求信息的原始日志。

## 环境与范围

- Windows宿主机，Intel i9-12900HX，16核24逻辑处理器，物理内存34075226112字节。
- 后端：Oracle Java17.0.12；测试JVM堆768MB、元空间384MB、ActiveProcessorCount=4；Maven堆512MB。
- 本机前端构建使用Node24.19.0，Node堆上限1536MB；Docker和GitHub CI配置为Node22，其结果独立记录。
- WSL Ubuntu-24.04：PostgreSQL16.15（55432）、Redis7.0.15（16379）、Apache Kafka3.6.0 KRaft（19092）。Kafka堆128～384MB，CPU数2。
- 测试使用固定隔离库及独立Redis DB；演示使用itms_demo和Redis DB2。模拟器默认关闭；Kafka消费者开启。
- KafkaWorkflowIT真实验证合法消息落库与回执、坏消息有限重试后进入DLT、后续消息继续处理和offset推进；不是Mock Broker。
- 告警排序6用例包含真实事务等待后的最新状态判断。eventId7用例覆盖跨入口重复与冲突。路线4用例覆盖空库、过期、单端和双端采样。

## Docker与CI的独立状态

较早版本的Compose镜像曾实际构建并启动，页面已有浏览器操作证据。最终代码的镜像重建时，本机Docker Desktop退出，重启停在`sailor-ingest.sock`通信文件错误；并发运行的测试JVM同时发生过本地内存分配失败。随后限制JVM/Node内存、顺序执行，并使用上述WSL依赖取得本报告的完整通过结果。

因此，本报告确认最终源代码的测试、构建和原生服务运行，不确认最终Docker镜像重建成功。Docker数据保留。GitHub工作流随提交新增，远程执行状态以Actions为准；本地通过不等于远程CI通过。

## 验证边界

没有在这一轮生成新的容量基准，不沿用旧版本2791.47成功RPS。尚未覆盖生产长时间负载、多节点故障切换、DLT Broker故障、持久失败队列、跨实例强缓存一致性、真实设备或训练模型。规则预测、信号应用及碳排/行程时间都按模拟/估算解释。

实际浏览器操作见[验收记录](browser-acceptance.md)；19张截图只记录本机演示数据，不含Token。最终构建核对了用户增改删、告警已读/解决、管理员审计、交通管理员与驾驶员权限、本人路线、退出等实际页面流程。
