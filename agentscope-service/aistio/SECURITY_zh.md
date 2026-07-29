# 安全策略

English version: [`SECURITY.md`](SECURITY.md).

Aistio 是用于 AI Agent 管理的 Kubernetes 原生控制面。集群管理员负责决定运行时可用的 Agent、模型 Provider、工具、MCP Server 和凭据。

## 支持版本

安全修复优先覆盖以下版本：

| 版本 | 支持状态 |
| --- | --- |
| 最新发布版本 | 支持 |
| `main` 分支 | 尽力支持 |
| 更早发布版本 | 默认不支持，除非另行说明 |

## 漏洞报告

请不要通过公开 GitHub issue 报告疑似漏洞。

请通过邮件私密报告安全问题：

- 邮箱：`yuluo08290126@gmail.com`
- 邮件标题：`Aistio security report`

报告中建议包含以下信息：

- 受影响的包、commit、tag 或 module 版本。
- 操作系统和 Go 版本。
- 涉及的 Agent、模型 Provider、MCP 配置或 Kubernetes 资源。
- 最小复现步骤或 proof of concept。
- 预期影响，以及是否涉及凭据、集群资源或 Agent 执行。

维护者会尽量在 7 天内确认收到报告，并在 30 天内提供状态更新。修复可用后，维护者会与报告人协商披露时间。

## 安全边界

Aistio 管理 Kubernetes 上 AI Agent 的生命周期，但不会让应用默认安全。

- 模型 API Key 和其他凭据应通过 Kubernetes Secret 提供，不能提交到仓库。
- Agent 执行由集群管理员配置的 CRD 规范和 Webhook 验证控制。
- MCP Server 属于外部信任边界。连接到 Agent 前，应使用可信 Server，并审查其配置。
- ASDP 数据面连接承载配置更新。应确保网络策略限制对控制面端点的访问。
- 消息、工具结果、日志和会话数据可能包含敏感信息。集群管理员负责存储、脱敏和保留策略。

## 需要私密报告的问题

以下问题请使用私密报告渠道：

- Webhook 验证绕过。
- 通过 CRD 操作实现权限提升。
- 未授权访问 Agent Session 或 Team 通信。
- Sandbox 或内置工具中的路径遍历、非预期文件访问。
- 日志、工具输出或生成配置中的密钥泄漏。
- MCP 集成导致非可信 Server 访问超出配置范围的工具能力。
- 对本项目存在实际利用路径的依赖或供应链问题。

## 非安全问题

以下问题可以通过普通 GitHub issue 反馈：

- Agent 行为异常或模型输出不符合预期。
- Controller reconcile 缺陷。
- 文档错误。
- 新 Provider、工具或集成的功能请求。
- 管理员已明确授予对应访问权限后出现的普通缺陷。

如果无法判断问题是否属于安全问题，请优先私密报告。
