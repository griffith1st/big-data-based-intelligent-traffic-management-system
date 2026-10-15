# ITMS HTTP 读接口压测工具

`traffic_load.py` 是一个仅依赖 Python 标准库的闭环压测工具。它轮询请求：

- `GET /api/traffic/realtime`
- `GET /api/traffic/heatmap`
- `GET /api/traffic/summary`

每个 worker 同一时刻只保留一个请求，连接会在 worker 内复用；工具不会自动重试或跟随重定向。`HTTP 2xx + JSON body.code == 200` 才算成功。429、5xx、超时、无效 JSON 和其他业务码都计入失败，429 不会被算入成功吞吐。

## 运行前提

- Python 3.10 或更高版本；不需要安装第三方包。
- 默认目标是 `http://127.0.0.1:8080`。默认值只允许回环地址，避免误压测外部服务。
- Token 只从环境变量读取，不会写入终端、JSON、Markdown 或异常信息。变量默认名为 `TOKEN`，值不要带 `Bearer ` 前缀。
- 访问其他主机时必须显式写 `--base-url`；不要只通过 `BASE_URL` 环境变量切换到远程地址。

## PowerShell 示例

在 `backend` 目录下执行：

```powershell
$env:TOKEN = "JWT_TOKEN"
python .\scripts\load\traffic_load.py `
  --requests 1000 `
  --concurrency 50 `
  --timeout 3 `
  --output-dir .\target\load-results
Remove-Item Env:TOKEN
```

如果确实要对明确的测试环境执行：

```powershell
$env:TOKEN = "JWT_TOKEN"
python .\scripts\load\traffic_load.py `
  --base-url "http://192.0.2.10:8080" `
  --requests 1000 `
  --concurrency 50 `
  --max-error-rate 0.01 `
  --p95-ms 200
Remove-Item Env:TOKEN
```

脚本会返回：

- `0`：错误率不超过 `--max-error-rate` 且（若设置）P95 不超过 `--p95-ms`；
- `1`：请求完成但验收条件不通过；
- `2`：参数、Token 或本地资源错误。

每次运行在输出目录写入同名的 `.json` 和 `.md` 两份证据，包含：参数、UTC 时间、压测机 Python/CPU/内存信息、Git 提交号、成功/失败分类、429、5xx、业务错误、P50/P95/P99、完成 RPS 和成功 RPS。Token 和响应正文均不写入证据。

## 参数

| 参数 | 默认值 | 说明 |
| --- | ---: | --- |
| `--base-url` | `http://127.0.0.1:8080` | API 根地址；远程主机必须显式传入 |
| `--requests` | `300` | 正式统计请求数 |
| `--concurrency` | `10` | 最大并发 worker，不能超过 1000 |
| `--timeout` | `3` | 单个 socket 操作超时（秒） |
| `--warmup-requests` | `0` | 预热请求数，不进入正式统计 |
| `--max-error-rate` | `0.01` | 失败率验收阈值；429 算失败 |
| `--p95-ms` | `0` | 可选 P95 阈值；0 表示不设阈值 |
| `--token-env` | `TOKEN` | Token 所在的环境变量名 |
| `--output-dir` | `backend/target/load-results` | 证据输出目录 |

## 解读结果

`completed_rps` 是所有完成请求的速率；`success_rps` 只包含业务成功请求。发生 429 时，前者可能看起来很高，但后者会下降，报告中的 `error_rate` 也会增加。容量结论必须同时附上服务器 CPU/内存、JVM 参数、数据库数据量、Redis 命中率、Hikari active/pending、Kafka lag 和 PostgreSQL 慢查询等服务端证据。

这个工具只覆盖 HTTP 读接口。Kafka 消费吞吐、重复消息幂等、Redis 故障和数据库故障应使用独立场景验证，不能用 HTTP 结果替代。

## 自测

工具附带本地 `unittest`，会启动回环 HTTP 假服务，验证成功路径、429/5xx/业务错误统计、远程地址保护和 Token 不落盘：

```powershell
python -m unittest discover -s .\scripts\load -p test_traffic_load.py -v
```
