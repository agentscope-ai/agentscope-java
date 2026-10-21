# POC 连续体验指南

[English](README.en.md)

示例支持 Bash 和 zsh。按下面顺序阅读和执行。集群使用 `local-developer` 开发身份，无需登录或获取 Token。

| 顺序 | 文档 | 操作 |
| --- | --- | --- |
| 1 | [API 快速体验](api-quickstart.md) | 统一配置变量和请求函数，创建 Agent/Session，完成两轮对话 |
| 2 | [事件与流式交互](events-and-streaming.md) | 沿用同一 Session，继续观察 SSE、查询历史、发送消息及中断重定向 |
| 3 | [工具调用与执行中交互](tool-collaboration.md) | 复用配置，新建工具 Session，测试 Brain/Hands、MCP、插话、排队和中断重定向 |
| 4 | [API Reference](api-reference.md) | 沿用已有上下文，按需查阅完整接口契约和路由目录 |

## 一次配置，全程复用

地址、作用域和基础请求函数只在 Quickstart 初始化。后续文档复用同一份上下文；工具篇另建启用工具的测试 Session：

| 变量 / 函数 | 用途 |
| --- | --- |
| `BASE_URL` | 集群 Gateway 地址 |
| `TENANT`、`NAMESPACE` | 请求作用域 |
| `ENVIRONMENT_ID` | 选定的运行环境，Quickstart 默认使用集群文本验收环境 |
| `AGENT_ID`、`SESSION_ID` | Quickstart 创建的 Agent 和 Session |
| `RUN_ID` | 本次体验的请求键前缀，全程保持不变 |
| `D` | 本次体验的临时目录；事件篇使用 `events/`，工具篇使用 `tools/` |
| `api` | 统一的 cURL 请求函数 |

保持 Quickstart 的终端 A、B 继续操作即可。使用新终端时，执行 Quickstart 第 3 步打印的完整 `source /实际路径/client.sh` 命令，恢复全部变量和函数；不要在新终端重复初始化地址或 Quickstart Session。工具篇按自己的第 1 步创建工具测试 Session，并打印专用上下文加载命令；原 `SESSION_ID` 保留不变。

Quickstart、事件篇和工具篇使用不同的幂等键后缀，确保跨篇连续执行不会复用同一个请求键。每次发送下一条普通消息前，等待上一条消息的答复及 idle；重试同一请求时保留原正文和原键。
