# 测试快速入口：运行、判定与仓库核对

当前文档对应 2026-10-17。逐项场景与证据边界见 [完整回归说明](project-completion.md)，历史 [2026-10-04 Redis 补测](redis-regression.md) 与 [2026-10-16 压测](high-concurrency.md) 不代表当前代码的全部行为。

## 1. 先确认打开了正确仓库

提交目标：[griffith1st/big-data-based-intelligent-traffic-management-system](https://github.com/griffith1st/big-data-based-intelligent-traffic-management-system)，目标分支 main。

本轮本地目录：`E:\BaiduNetdiskDownload\3D\resume_work\source_repos\itms-bd`。终端执行：

```powershell
git rev-parse --show-toplevel
git remote -v
git branch -vv
```

origin 应指向上述 griffith1st 仓库。upstream 若显示 oxw123 是保留的历史来源；旧提交作者不会因切换 origin 而改变。相同文件夹名字不保证同一个 .git 配置，VS/VS Code 的仓库归属取决于当前打开根目录与远程地址。

## 2. 推荐：一条命令完成主要回归

前提：Docker Desktop、JDK 17、Maven、Node.js 22/npm、Python 3.11、PowerShell 7。在仓库根目录运行：

```powershell
pwsh -File .\scripts\verify-project.ps1
```

脚本依次执行：独立测试 Compose 健康就绪 → 新库/旧库/重复迁移验证 → Java 普通测试与全部组件集成测试 → npm ci → 前端测试 → 前端生产构建。

默认项目名 `itms-verify`，PostgreSQL/Redis/Kafka 端口为 55432/16379/19092。它使用 `compose.test.yml`，与正常业务 Compose 分开。结束时只清理该指定测试项目及其测试数据；保留依赖便于排查：

```powershell
pwsh -File .\scripts\verify-project.ps1 -SkipInstall -KeepDependencies -ProjectName itms-completion-tests
```

仅在前端依赖已按当前锁文件安装时使用 -SkipInstall。端口冲突可传 -PostgresPort、-RedisPort、-KafkaPort。保留测试依赖后，沿用同一 project 名再次运行；完成排查后只清理该测试项目。

## 3. 单独运行某一层

在 backend 目录运行普通 Java 测试：

```powershell
mvn -B -ntp test
mvn '-Dtest=TrafficDataServiceImplTest' test
mvn '-Dtest=TrafficDataConcurrencyTest' test
```

真实 Redis 专项：

```powershell
pwsh -File .\scripts\test-redis.ps1
```

该脚本创建随机宿主机端口的临时 Redis，运行普通测试和 RedisApplicationIT，finally 清理容器。该集成类中的业务 Repository 仍有 mock；真实 PostgreSQL/Kafka 语义由完整集成组覆盖。

在 frontend 目录执行：

```powershell
$env:CI = 'true'
npm test -- --watchAll=false --runInBand
npm run build
```

## 4. 看什么才算验证完成

- 普通测试报告：`backend/target-completion/surefire-reports/TEST-*.xml`。
- 集成测试报告：`backend/target-completion/failsafe-reports/TEST-*.xml`。
- 一键验证摘要：`output/verification/summary.json`；每轮更新状态，只有 `status=passed` 表示本轮全部步骤成功，失败或中断应检查 failed/running 与时间戳。
- 单独 `mvn test` 默认输出到 `backend/target`，与一键脚本目录不同。
- 前端 Jest 终端输出确认 suites/tests 和失败数；构建检查 `frontend/build` 生成及命令退出码。

同时看 tests、failures、errors、skipped。只看到编译成功或普通测试绿色，不表示集成测试已运行。当前确切计数以本轮报告为准；历史文档里的19/9/28/66是对应旧版本的数字。

## 5. 用例怎样规定

每条从业务承诺或故障风险出发，采用“准备 → 动作 → 断言”，检查返回结果和副作用：

| 承诺 | 例子 | 应断言什么 |
| --- | --- | --- |
| 正确汇总 | 100辆/10km/h与300辆/30km/h | 加权速度25，避免简单平均20 |
| 并发回源有上限 | 多请求同时miss | 同Key/版本仅一个执行者；等待超时不删除其任务 |
| 事务一致性 | 交通写入后告警失败 | 数据/回执回滚，缓存保持；重试可成功 |
| 登录锁定 | 连续5次错误再正确密码 | 第5次与锁定期间均429，有剩余Retry-After |
| 当前权限生效 | 旧Token对应账号被禁用/降权 | 禁用401，越权403；身份来自数据库 |
| 真实组件语义 | TTL到期、唯一约束、Kafka坏消息 | 分别检查实际Key、数据库行数、DLT及后续消费 |

旧报告中“复现已知限制”的测试通过只表示重现当时现状。当前代码已把相应目标改为预期正确行为，具体以 [本轮测试清单](project-completion.md) 为准。
