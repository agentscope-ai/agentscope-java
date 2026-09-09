# 第一个 Session

完成本页后，你应能在控制台发送消息、看到 Agent 回复，并在刷新页面后找回会话历史。

## 1. 准备模型与环境

确认服务已按[快速上手](quickstart.md)启动。配置一个可用模型及其凭据。可信本地体验可启用 Local；其他安装先建立 Sandbox 或 Self-hosted Environment，确认其连接状态。

## 2. 创建 Agent

在 Agents 中创建 Managed Agent，设置名称与指令，例如“用简短中文回答，执行前说明需要的文件”。选定模型、Workspace 和 Environment。第一次体验只开启任务需要的工具。

保存后检查 Agent 的环境绑定。创建配置本身不会启动一次执行。

## 3. 创建 Session 并发送消息

进入 Sessions，选择刚创建的 Agent，创建 Session。发送“请用一句话介绍你能完成的工作”。观察消息、工具调用和状态变化；需要工具确认时，在当前会话中处理确认。

如果 Agent 需要文件，再把一份小型示例文件加入 Workspace，要求它列出或概括内容。验证它使用的是预期执行环境。

## 4. 验证结果

刷新页面后重新打开会话，确认消息历史仍可见。运行中刷新页面不应被当作重新发送消息。任务是否完成要结合结果和状态判断，不能只看流式连接是否结束。

## API 对应关系

| 操作 | API |
| --- | --- |
| 登录 | `POST /api/auth/login` |
| 创建 Agent | `POST /api/v1/agents` |
| 创建 Environment | `POST /api/environments` |
| 创建 Session | `POST /api/sessions` |
| 发送消息 | `POST /api/sessions/{id}/events` |
| 查询历史 | `GET /api/sessions/{id}/events` |
| 订阅事件 | `GET /api/sessions/{id}/events/stream` |

创建 Agent 使用 `/api/v1/agents`，请求包含 `agentKey`、`displayName`、`binding` 和 `definition`。未指定 Namespace 时使用当前账号的个人空间；不要在新安装中假定存在一个名为 `default` 的共享空间。

发送消息的请求体形如 `{"events":[{"type":"user.message","payload":{"text":"你好"}}]}`。登录后使用用户 Bearer token；内部令牌不用于浏览器调用。

没有回复时按[排障指南](troubleshooting.md)逐层检查模型、Environment 与 Dataplane。
