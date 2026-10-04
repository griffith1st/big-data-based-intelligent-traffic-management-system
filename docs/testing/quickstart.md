# Redis 补测：运行、判定与 Git 仓库

## 正确的仓库

代码提交目标：https://github.com/griffith1st/big-data-based-intelligent-traffic-management-system ，分支 main。

本地目录：E:\BaiduNetdiskDownload\3D\resume_work\source_repos\itms-bd。

这个目录最初克隆自 Gitee oxw123，原 origin 指向该仓库且分支为 master。2026-10-04 同步时，将该远程保留为 upstream，将 origin 设置为 griffith1st 的 GitHub，并以 GitHub main 为基线接入 Redis 实现与测试。VS 显示仓库归属取决于 .git/config，目录名相同不代表远程相同。

检查当前目录和跟踪关系：

```powershell
git rev-parse --show-toplevel
git remote -v
git branch -vv
```

预期：当前分支 main 跟踪 origin/main；origin 是 griffith1st；upstream 是 oxw123 的历史来源。日常推送使用 git push origin main。Git 提交的旧作者记录仍会保留，这是代码来源历史；不需要修改作者记录。

## 运行测试

前提：JDK 17、Maven；真实 Redis 集成组额外需要 Docker Desktop 和 PowerShell 7。测试不需要模型 API Key。

```powershell
Set-Location 'E:\BaiduNetdiskDownload\3D\resume_work\source_repos\itms-bd\backend'
# 19 个普通用例；不连接外部 Redis/PostgreSQL
mvn -B -ntp test

# Docker Desktop 启动后：19 个普通用例 + 9 个真实 Redis 组件集成用例
pwsh -File .\scripts\test-redis.ps1

# 只运行某一类，或某一个普通测试方法
mvn '-Dtest=TrafficDataServiceImplTest' test
mvn '-Dtest=TrafficDataServiceImplTest#cacheHitReturnsPayloadWithoutDatabaseAccess' test
```

脚本启动独立临时 Redis、取得随机本机端口、执行 Maven verify，最终清理该容器。终端应同时显示普通组 Tests run: 19、集成组 Tests run: 9，两组 Failures/Errors/Skipped 均为 0，并显示 BUILD SUCCESS。仅看到编译成功不代表运行了集成测试。

报告：target/surefire-reports、target/failsafe-reports。脚本完整日志：target/redis-test-evidence/maven.txt。

## 用例是怎么规定的

不是按“凑够 28 条”制定，而是按业务承诺、分支边界、故障与并发时序划分。每条遵循 Arrange（准备）→ Act（调用真实项目方法）→ Assert（判定结果与副作用）。

| 需求或风险 | 准备与动作 | 通过条件 |
| --- | --- | --- |
| 缓存命中应减少 DB 访问 | Redis 给出车流 17，调用实时查询 | 返回 17，Repository 零调用 |
| miss 应查库并填充缓存 | DB 行车流 600、均速 25 | 返回映射字段正确；写入 JSON，TTL 为 5 秒 |
| 缓存坏数据/Redis 命令失败 | 注入坏 JSON 或 GET/SET 异常 | 仍返回 DB 结果；DB 自身失败则抛出错误 |
| 写入后应失效两个缓存 | 保存交通记录、告警成功 | 按 DB 保存→告警→删除 realtime 与 summary 的顺序执行 |
| 汇总要按车流加权 | 100 辆/10 km/h 与 300 辆/30 km/h | 总车流 400、均速 25；不是简单平均 20 |
| 登出撤销应隔离 Token | 两个有效 Token，登出其中一个 | 摘要 Key 存在、TTL 接近剩余时间；该 Token 不再认证，另一个正常 |
| TTL 应真实生效 | 短有效期 JWT、5 秒交通缓存 | 等待实际过期后，撤销 Key 消失或查询重新访问 DB |
| 当前登录失败计数行为 | 错误密码 5 次，再正确登录 | 计数为 5，正确密码仍成功并清理计数；这是现状而非封禁验收 |
| 旧查询回填风险 | latch 控制旧读→新写/删除→旧读回填 | 下一读得到旧值 600，证明现有竞争窗口 |

判定值来自可手算的小数据和业务约定；不调用被测方法生成“预期答案”。Mockito 只替换外部边界，Service、JWT 和序列化仍执行项目代码。真实 Redis 组验证命令效果与 TTL，但数据库和告警仍是 mock。

## 两类“通过”要分清

- 22 条一般回归：预期能力成立，例如缓存命中不查库、已撤销 JWT 不认证。
- 6 条 known-limitation 特征测试：准确记录已知问题，包括告警异常阻断失效、黑名单异常外抛、撤销写失败被吞掉、INCR/EXPIRE 分离、5 次错误没有封禁、并发旧值回填。通过表示复现了问题，不代表问题解决。

后续修复某个问题时，应先把相应现状测试换成预期业务断言，观察旧实现失败，再修生产代码使其通过，并更新文档/标签。

新增测试确实能抓回归的证据：在独立副本删除写后缓存失效调用，对应用例因缺少 DEL 而失败；原代码全套通过。详细逐用例清单与验证边界见 [redis-regression.md](redis-regression.md)。

这些用例不证明生产吞吐量、完整 HTTP 状态码契约、真实 PostgreSQL 事务或 Kafka 可靠性；此类能力需要另建集成场景和测量条件。
