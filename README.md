# ITMS-BD 智能交通管理系统

基于 Spring Boot、React、PostgreSQL、Redis、Kafka 和 Python AI 服务的智能交通管理系统示例项目。

## Redis 回归测试与面试复习

- [运行测试、用例判定与 Git 仓库说明](docs/testing/quickstart.md)
- [28 项补测清单、证据与已知边界](docs/testing/redis-regression.md)

在 `backend` 运行 `mvn test` 执行 19 个普通测试；启动 Docker Desktop 后，使用 PowerShell 7 运行 `pwsh -File ./scripts/test-redis.ps1` 执行普通测试及 9 个真实 Redis 组件集成测试。临时 Redis 自动创建、清理；PostgreSQL 和告警依赖采用 mock，测试结果不代表整套系统压测。

## 功能模块

- 实时交通监控：车流量、速度、拥堵等级、路口热力图
- 交通预测分析：模拟 LSTM 未来 30 分钟流量预测与拥堵风险识别
- 信号灯优化：模拟 DQN 信号灯配时优化与方案应用
- 路线规划：基于 Dijkstra 的最短/最快/低碳路径规划，集成实时交通拥堵数据动态计算预计时间与碳排放
- 告警管理：告警查询、标记已读、处理告警、统计图表
- 用户管理：用户列表、新增、编辑、删除
- 系统日志：系统操作日志查询与可视化

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17, Spring Boot 3.2, Spring Security, JPA, JWT |
| 前端 | React 18, TypeScript, Ant Design 5, ECharts |
| 数据库 | PostgreSQL 16及以上 |
| 缓存/消息 | Redis 7, Kafka |
| AI 示例 | Dijkstra 路径规划, LSTM 预测, DQN 信号灯优化 |
| 部署 | Docker Compose |

## 1.快速启动
## 注意Node不要使用 24版本，因为Node 24 太新 + react-scripts 5.0.1 太老 → 完全不兼容！ 建议使用Node 18
### Docker Compose
在docker设置的docker engine里可以使用：
```bash
{
  "builder": {
    "gc": {
      "defaultKeepStorage": "20GB",
      "enabled": true
    }
  },
  "experimental": false,
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.xuanyuan.me"
  ]
}
```

```bash
cd itms-bd
docker-compose up -d
```

访问地址：

- 前端：http://localhost:3000
- 后端 API：http://localhost:8080
- Swagger：http://localhost:8080/swagger-ui.html

## 2.部分本地开发
也就是：
PostgreSQL / Redis / Kafka 用 Docker
前端 / 后端 用 VS Code 本地跑
查看服务名：
```bash
cd D:\try\itms-bd
docker compose config --services

然后只启动：

docker compose up -d postgres redis zookeeper kafka
```
如果你的服务名是 itms-postgres 这种，不用容器名，用 compose 里的服务名。服务名要以 docker compose config --services 输出为准。


后端：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

前端：

```bash
cd frontend
npm install
npm start
```

## 3.完全 Windows 本地部署（比较麻烦，感兴趣可以自己尝试）
其中Windows 原生 Redis 比较麻烦，官方主要支持 Linux。用docker最方便。


## 默认账号

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| admin | 123456 | 系统管理员 |
| manager1 | 123456 | 交通管理员 |
| manager2 | 123456 | 交通管理员 |
| driver1 | 123456 | 驾驶员 |
| driver2 | 123456 | 驾驶员 |

## 主要接口

- `POST /api/auth/login` 登录
- `GET /api/traffic/realtime` 实时交通数据
- `GET /api/traffic/heatmap` 拥堵热力数据
- `GET /api/prediction/overview` 预测概览
- `POST /api/prediction/trigger` 触发预测
- `GET /api/signal/overview` 信号灯概览
- `POST /api/signal/optimize/{intersectionId}` 优化信号灯
- `POST /api/route/planByIntersection` 按路口 ID 路线规划（支持 SHORTEST/FASTEST/LOW_CARBON 策略，拥堵数据动态变化）
- `GET /api/route/intersections` 路网路口列表
- `GET /api/route/network` 路网拓扑（节点+边）
- `GET /api/alert/list` 告警列表
- `GET /api/users/list` 用户列表
- `GET /api/logs` 系统日志

## 说明

当前 AI 算法和大数据模块以课程设计演示为主，后端服务中已提供可运行的模拟数据与模拟算法结果，方便无外部模型服务时完成主要业务闭环。

## 路线规划与实时交通联动

路线规划使用 Dijkstra 算法，边权重动态计算依赖实时拥堵数据：

- **拥堵数据来源**：`traffic_data` 表最新一批记录的 AVG 拥堵等级（连续值 0.0–3.0）
- **动态权重公式**：`1.0 + avgCongestionLevel × 0.4`，乘以路段基础拥堵系数，实现连续插值（避免离散等级造成的阶梯效应）
- **三种策略**：
  - `SHORTEST` — 距离优先，带拥堵惩罚因子
  - `FASTEST` — 时间优先，考虑红绿灯数量（每个 +0.5min）
  - `LOW_CARBON` — 低碳优先，权重公式 `距离 × 碳排因子 × 拥堵系数 + 红绿灯 × 0.2`
- **前端可视化**：路网边按拥堵等级着色（绿/黄/橙/红），每 5 秒轮询实时交通数据，规划路径高亮显示
