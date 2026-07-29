# FAQ

本页回答接入 AgentScope Go 时最常见的选型问题。更完整的接口说明请查看快速开始和高级主题中的对应章节。

## 什么时候只用 ChatModel

普通聊天、摘要、分类、结构化抽取和模型供应商统一封装，通常只需要 `model.ChatModel` 和 `message.Message`。

如果应用需要模型主动调用工具、处理权限确认、返回事件流或压缩长上下文，再使用 `agent.Agent`。

## 什么时候需要 Agent

需要自动执行“模型推理 -> 权限检查 -> 工具执行 -> 结果回填”的循环时，使用 Agent。

如果调用方希望完全控制每一轮模型请求和工具执行，也可以只使用 `model` 和 `tool.Toolkit` 手写循环。

## 什么时候需要 Sandbox 沙箱

工具需要文件系统、Shell、Skill、MCP 持久化或大内容 offload 时，使用 Sandbox 沙箱。

纯函数工具、普通模型调用和不涉及文件环境的业务工具不需要沙箱。

## Call 和 Stream 怎么选择

`Call` 返回一次完整响应，适合服务端内部处理、摘要和分类任务。

`Stream` 返回响应通道，适合前端实时展示文本、工具状态、模型思考块或多模态输出。

## 工具执行如何做权限控制

高风险工具应通过 `permission.Engine` 做执行前决策。常见模式包括只读探索、人工确认、自动分类和受控环境下的 bypass。

文件写入、Shell、外部系统访问和会修改业务状态的工具都应配置明确规则。

## 如何配置本地 pre-commit

首次克隆仓库后运行：

```bash
make setup
make install-tools
```

提交前可以手动运行：

```bash
.venv/bin/pre-commit run --all-files
```
