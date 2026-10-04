# Redis 教学实验

此目录提供一个隔离的 Redis 7 实验服务，示范 String TTL、Hash、List、Set、ZSet、Lua 原子计数与固定窗口尝试次数限制、令牌解锁，以及 Stream 消费者组。服务名为 `itms-redis-learning`，不映射宿主机端口；数据保存在命名卷中，脚本以只读方式挂载到 `/lab`。

在 Docker Desktop 已运行时执行：

```powershell
docker compose -f .\compose.yml up -d
.\verify.ps1
```

验证脚本不会启动、删除容器或清空数据库。它为每次运行生成 `learn:itms:<runId>:` 前缀，断言全部语义结果，并在 `finally` 中只对本次运行的精确键执行 `UNLINK`。结果写入 `verification-latest.json` 与 `verification-latest.md`，其中包含脚本版本、SHA-256、检查数量和清理状态。停止实验：

```powershell
docker compose -f .\compose.yml down
```

`failure_count.lua` 展示把 INCR 与首次 PEXPIRE 放进同一 Lua 执行的教学修复；`attempt_limit.lua` 是固定窗口的总尝试次数上限，成功与失败都会消耗名额。Stream 的 `XAUTOCLAIM` 使用 idle=0 仅适用于这个隔离实验。
