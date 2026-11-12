# 无存储依赖的 MCP 性能测试工具

[English](README.md)

`managed-agents-long-tool-mcp` 用来模拟外部部署的 MCP 服务，只需要 `MCP_TEST_TOKEN`。
它不访问 PostgreSQL、Redis、MQ 或 OSS，不保存执行台账，也不提供持久化结果查询。
通过官方 MCP Python SDK 提供无会话的 Streamable HTTP / JSON 接口，只暴露
`long_wait(marker, seconds)`，等待范围 0.1–180 秒，支持 200ms 和 30 秒测试场景。

一次调用生成独立 call ID，异步等待指定时长，然后返回 marker、call ID、Pod 和真实开始/结束时间。
相同 marker 再次调用是新的操作。运行中的任务仅存在于进程内存，重启后丢失；HTTP 连接断开
不取消已经开始的模拟等待，但服务没有结果查询接口，也不声明持久化恢复能力。
平台自身的执行权、checkpoint 和结果持久化仍由平台负责。

## 本地构建与检查

```bash
docker build -t ma-mcp-performance:local .
docker run --rm --name ma-mcp-performance --cpus=2 --memory=4g \
  --read-only --cap-drop ALL -p 127.0.0.1:18888:8000 \
  -e MCP_TEST_TOKEN=local-regression ma-mcp-performance:local
```

在独立 Python 环境安装 `requirements.txt` 后运行：

```bash
python -m unittest -v test_pool_wait test_health test_probe_routes
MCP_TEST_TOKEN=local-regression python -m unittest -v test_server
```

可设置 `MCP_TEST_PEER_BASE` 增加第二个 HTTP 实例，验证相同 marker 返回不同 call ID；
两个实例都不需要数据库。部署清单固定已发布无存储镜像的 digest，使用 5 个实例，
沿用集群原来的每实例 4 CPU / 8GiB，已移除数据库环境变量。2026-10-26 的发布只涉及
该测试组件；Brain 保持 4 个实例，Hands 保持 2 个实例。后续源码变更仍需重新构建并发布
不可变镜像。后续切换工具时应创建新的压测 Session，
因为新工具描述不再包含执行恢复扩展；不得改写旧请求已受理的 descriptor hash。

## 观测与独立验收

`/healthz`、`/readyz` 只查看本进程；需鉴权的 `/diagnostics` 提供当前/峰值并发、开始/完成/
取消计数、事件循环延迟、日志排队及丢弃数。开始/完成日志经过有界后台 stdout 队列输出，
日志收集慢不会阻塞执行。队列满时允许丢日志，但物理调用次数验收必须据此判失败。
日志不是工具执行或恢复的依赖。

闭环压测脚本读取平台 Turn 状态及 Hands 已捕获结果，不再查询 MCP 自建表。
平台结果不能独立证明物理执行恰好一次。压测结束后收集所有 MCP 实例日志及逐 Pod 最终
`/diagnostics` 快照，执行：

```bash
python ../pts/audit_mcp_calls.py --batch BATCH \
  --admissions RUN/admissions.jsonl --terminals RUN/terminals.jsonl \
  --logs POD_A.log.gz POD_B.log.gz --diagnostics final-diagnostics.json \
  --out RUN/mcp-audit.json
```

快照结构为 `{"pods":[{"pod":"POD_NAME","value":{...}}]}`。每个已受理 marker 必须有一次开始、
一次结束，与平台捕获的 call ID 一致，日志无丢弃且已经排空。工具场景缺少此独立核验时，
`analyze_closed_loop.py` 不会判压测通过。多压测进程分别以自己的 admissions/terminals，
对照同一份完整 MCP 日志与最终快照生成核验文件。

10,000 个冷连接同时到来、持续请求吞吐、10,000 个长等待并发是三种不同的容量测试。
本地两个容器各 2 CPU / 4GiB：200ms 工具以 500 请求/秒完成 10,000 次调用，零错误，
响应 p95 205ms；30 秒工具以 20 秒爬升启动 10,000 次调用，每实例达到 5,000 个同时等待，
零错误，响应 p95 33.56 秒。零爬升冷连接测试仍出现 TCP 建连超时。这些检查不代替全链路
集群验收。需要分别记录客户端错误、事件循环延迟及设定时长外的服务开销，不能用单测通过
或调大并发配置代替容量证明。

## 历史恢复实验

[最初恢复失败](dual-replica-recovery.md)及[数据库版恢复验收](recovery-v1-acceptance.md)
属于旧版带 PostgreSQL 的测试工具，保留为历史证据，不代表当前性能组件的恢复承诺。
`verify_recovery.py` 属于那次历史 owner 重启实验，不能当作当前无存储工具应通过的验收契约。
平台仍支持真正实现恢复扩展的外部 MCP 服务。
