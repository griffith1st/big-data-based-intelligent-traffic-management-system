# 本地启动、升级与排错

## 1. 新建演示环境

仓库根目录执行 `docker compose up -d --build --wait`。默认前端3000、后端8080、PostgreSQL5432、Redis6379、Kafka9092；具体变量见 [.env.example](../.env.example)。前端通过 Nginx 同源代理 `/api/`，开发模式通过 package.json 的 proxy 连后端。

若机器已经运行另一套服务，为新实例同时设置不同的 Compose 项目名和端口。环境文件示例：

```dotenv
POSTGRES_PORT=55433
REDIS_PORT=16380
KAFKA_PORT=19093
BACKEND_PORT=18080
FRONTEND_PORT=13000
ITMS_TRAFFIC_SIMULATOR_ENABLED=false
```

保存为 `output/verification/demo.env` 后，执行：

```powershell
docker compose --env-file output/verification/demo.env -p itms-completion-demo up -d --build --wait
docker compose --env-file output/verification/demo.env -p itms-completion-demo ps
```

打开 `http://localhost:13000`。首次初始化的演示账号有 admin、manager1、driver1，初始密码123456；修改后按新密码登录。该配置关闭自动模拟，可在交通监测页面逐次生成数据，更适合学习。后端健康入口为 `http://localhost:18080/actuator/health`，接口文档为 `http://localhost:18080/swagger-ui.html`。

暂时停止这一套：

```powershell
docker compose --env-file output/verification/demo.env -p itms-completion-demo stop
```

再次使用 `up -d --wait` 启动，已有数据卷和密码保留。测试专用 `compose.test.yml` 及 `scripts/verify-project.ps1` 与该演示环境分开，不把测试运行目标指向演示库。

## 2. 已有数据升级

以下命令适用于默认项目和账号 `itms_admin / itms_bd`。若使用自定义项目、环境文件或数据库名称，对每条命令采用相同参数及实际账号。先停止写入方，备份数据库，再迁移、启动新版本；不要靠重新执行 init.sql 升级旧数据卷。

```powershell
docker compose stop backend frontend
New-Item -ItemType Directory -Force output/backup | Out-Null
docker compose exec -T postgres pg_dump -U itms_admin -d itms_bd -Fc -f /tmp/itms-before-completion.dump
docker compose cp postgres:/tmp/itms-before-completion.dump output/backup/itms-before-completion.dump
docker compose cp database/migrations/20261016_traffic_ingestion_receipts.sql postgres:/tmp/receipts.sql
docker compose cp database/migrations/20261017_project_completion.sql postgres:/tmp/completion.sql
docker compose exec -T postgres psql -U itms_admin -d itms_bd -v ON_ERROR_STOP=1 -f /tmp/receipts.sql
docker compose exec -T postgres psql -U itms_admin -d itms_bd -v ON_ERROR_STOP=1 -f /tmp/completion.sql
docker compose up -d --build --wait
```

每条检查退出码；迁移报错时先处理错误，再启动应用。20261017迁移有事务并可重复执行；重复配时行保留最新一行，其余存入 `traffic_signal_duplicate_archive`。历史错误模型标签改为 SIMULATION-legacy，清除无依据置信度。新列包括认证版本、候选配时、告警来源/时间、路线采样元数据和事件ID/摘要。

应用首次启动可将旧明文密码单向转换为 BCrypt，保留账号状态。旧Token需要重新登录。确认迁移完成后可关闭 `ITMS_MIGRATE_LEGACY_PASSWORDS`。`ITMS_DEMO_RESET_DEFAULT_USERS` 默认false，保持false可避免重启重置账户。

## 3. 回退原则

保留升级前Git提交和数据库备份。停掉新应用，将备份恢复到一个新数据库（例如 itms_restore_check）检查记录，再让旧代码指向恢复库。新版本运行后产生的数据不包含在升级前备份中，切换前单独保留。不要简单删除新增列或恢复旧明文初始化SQL。

示例验证备份可恢复，目标是新建的恢复检查库：

```powershell
docker compose exec -T postgres createdb -U itms_admin itms_restore_check
docker compose cp output/backup/itms-before-completion.dump postgres:/tmp/itms-before-completion.dump
docker compose exec -T postgres pg_restore -U itms_admin -d itms_restore_check --exit-on-error /tmp/itms-before-completion.dump
docker compose exec -T postgres psql -U itms_admin -d itms_restore_check -c 'SELECT count(*) FROM users;'
```

本轮自动化验证覆盖新库/旧库/重复迁移；上述备份恢复步骤应针对实际部署另行执行检查。

## 4. 现象与排查入口

| 现象 | 检查方法 |
| --- | --- |
| 宿主机端口被占用 | 调整对应端口并使用独立项目名；不要结束不属于本项目的服务 |
| Kafka启动失败 | `docker compose ps` 和 `logs --tail 100 zookeeper kafka`；Kafka需要等ZooKeeper健康，外部客户端使用配置的宿主机端口，容器使用kafka:29092 |
| 页面登录请求失败 | 浏览器Network检查 `/api/auth/login`；生产前端走同源Nginx；开发前端检查proxy与后端端口 |
| 跨域403 | `ITMS_CORS_ALLOWED_ORIGINS`列出实际来源；Nginx应保留Host端口；跨域允许列表不代替身份授权 |
| 交通页面空 | GET不会生成数据；手动生成或开启模拟器，检查Kafka消费与数据库行数 |
| 旧Token失效 | 改密/禁用/迁移会要求重新登录；普通无权限操作为403 |
| 429 | 检查Retry-After；登录锁定与准入过载是两类不同原因 |
| 503 | 查看认证依赖、Hikari等待、SQL超时、缓存回源等待；按响应建议重试 |
| 消息没有成为交通数据 | 检查DLT与日志；使用原eventId、原业务内容重放，冲突内容返回409 |

当前使用单节点本地依赖。没有真实传感器接入、训练模型推理、实际信号机下发、多副本高可用或持久降级队列。详见[项目学习地图](project-guide.md)与[测试证据](testing/project-completion.md)。

## 5. 本轮本机学习环境（WSL 依赖）

2026-10-17本机Docker Desktop在最终构建时退出，重启停在通信文件错误，因此最后版本通过独立WSL依赖完成全量验证。原有Docker数据保留，最终镜像重建待Docker恢复。下面是本机已准备环境的使用方式；其他机器按前面的Compose方式启动，或自行准备同版本依赖。

| 组件 | 本机位置/参数 |
| --- | --- |
| 前端 | http://127.0.0.1:13000；构建产物由本地Node代理服务提供，`/api/`转发18080 |
| 后端 | http://127.0.0.1:18080；Java17，最大堆768MB；健康入口`/actuator/health` |
| PostgreSQL | WSL Ubuntu-24.04，16.15，127.0.0.1:55432，postgres/空密码，本机演示库itms_demo |
| Redis | WSL，7.0.15，127.0.0.1:16379，演示DB2；测试使用其他DB编号 |
| Kafka | WSL，Apache3.6.0 KRaft单节点，127.0.0.1:19092；本地对比Compose的ZooKeeper模式 |
| 状态与日志 | WSL `/var/tmp/itms-completion-native`；Windows仓库`output/runtime` |

端口均绑定回环地址。演示密码仍为README所列初始账号密码。自动模拟已关闭；首次登录后可以手动生成一批，或按学习指南保存单条数据。WSL Redis未启用持久化，重启会清空缓存与认证临时状态，该模式仅用于本机学习。

在本仓库根目录查看运行情况：

```powershell
Invoke-RestMethod http://127.0.0.1:18080/actuator/health
wsl -d Ubuntu-24.04 -- pg_isready -h 127.0.0.1 -p 55432
wsl -d Ubuntu-24.04 -- redis-cli -p 16379 -n 2 PING
Get-Content output/runtime/backend.stdout.log -Tail 60
wsl -d Ubuntu-24.04 -- tail -n 40 /var/tmp/itms-completion-native/kafka.log
```

本机临时启动助手留在被Git忽略的output中，保留当前环境即可复用；这些助手和Kafka下载包不随仓库分发。停止后恢复：

```powershell
wsl -d Ubuntu-24.04 -- bash /mnt/e/BaiduNetdiskDownload/3D/resume_work/source_repos/itms-bd/output/runtime/start-dependencies.sh
./output/runtime/start-backend.ps1
# 前端进程未运行时，在另一个终端启动：
node output/playwright/serve-demo.mjs
```

依赖助手保留既有PG/Kafka数据，不重复初始化；Kafka加入IPv4选项以兼容本机WSL端口转发。先等后端健康为UP再打开前端。启动助手需要本轮构建出的`backend/target-completion/itms-bd-backend-1.0.0.jar`与`frontend/build`。

停止时先关前端和后端，再停依赖。若前端在当前终端启动，按Ctrl+C；本轮隐藏进程可先用`Get-CimInstance Win32_Process`核对CommandLine为本仓库的serve-demo.mjs，再按它的ProcessId停止。后端进程号记录在output/runtime/backend.pid，核对CommandLine为本仓库JAR后再停止，避免PID复用时影响其他进程。WSL依赖按自己的数据目录停止：

```powershell
wsl -d Ubuntu-24.04 -- bash -lc 'kill -TERM $(cat /var/tmp/itms-completion-native/kafka.pid)'
wsl -d Ubuntu-24.04 -- redis-cli -p 16379 SHUTDOWN NOSAVE
wsl -d Ubuntu-24.04 -- runuser -u postgres -- /usr/lib/postgresql/16/bin/pg_ctl -D /var/tmp/itms-completion-native/pgdata -m fast stop
```

Kafka停止前同样核对pid对应当前Kafka进程。上述操作保留PG/Kafka数据目录，不清理其他WSL或Docker实例。
