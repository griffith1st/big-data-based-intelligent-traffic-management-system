# Redis 教学实验

此目录提供隔离的 Redis 7 服务，练习 String/TTL、Hash、List、Set、ZSet、Lua、令牌校验解锁和 Stream 消费者组。Compose **项目名**是 `itms-redis-learning`，**服务名**是 `redis`。它没有宿主机端口映射，数据存命名卷，脚本以只读方式挂载到 `/lab`。

## 运行方法

在仓库根目录、Docker Desktop 已运行时：

```powershell
docker compose -f .\docs\redis-lab\compose.yml up -d
pwsh -File .\docs\redis-lab\verify.ps1
```

也可进入本目录再运行 `docker compose -f .\compose.yml up -d` 与 `.\verify.ps1`。脚本最低支持 PowerShell5.1，推荐PowerShell7。

验证脚本不会启动或删除容器，也不会清空数据库。每次生成 `learn:itms:<runId>:` 前缀，执行40个语义检查，并在 finally 中只对该轮精确Key执行UNLINK。结果写入 `verification-latest.json` 与 `verification-latest.md`，记录脚本版本、SHA-256、检查数和清理状态。40是脚本定义的预期检查数，实际是否通过应查看本轮报告。

单条命令实验：

```powershell
docker compose -f .\docs\redis-lab\compose.yml exec -T redis redis-cli PING
```

停止并保留实验卷：

```powershell
docker compose -f .\docs\redis-lab\compose.yml down
```

## 教学实验与真实项目的差别

| 实验 | 本目录在教什么 | ITMS实际业务 |
| --- | --- | --- |
| String/TTL | 到期、缺失、数字字符串 | 三类Redis使用均以String保存 |
| Hash/List/Set/ZSet | 数据结构与命令语义 | 当前业务未用这些结构实现用户、队列或排名 |
| failure_count.lua | INCR与首次PEXPIRE原子执行，首次失败起固定窗口 | 业务Lua还在第5次失败时重置为完整10分钟锁定 |
| attempt_limit.lua | 固定窗口总尝试次数，成功失败都消耗名额 | 登录业务按认证失败计数，成功只清未达阈值计数 |
| unlock.lua | 校验令牌后删除，避免删别人的锁 | 业务缓存使用本地锁/Future/generation，未使用这把Redis锁 |
| Stream消费者组 | 消费、确认、待处理与转移 | 当前交通流使用Kafka；实验XAUTOCLAIM idle=0仅便于观察 |

这里的实验服务与应用认证/缓存Redis独立。它验证数据结构及命令，不替代应用的真实Redis集成测试，更不证明PostgreSQL事务和Kafka消费。

继续阅读：[从零Redis教程](../../Redis实现与面试教学.md)、[八部分项目学习地图](../project-guide.md)、[项目测试入口](../testing/quickstart.md)。