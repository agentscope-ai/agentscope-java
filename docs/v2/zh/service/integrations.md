---
title: "SDK 与组件选择"
---

[English](/v2/en/service/integrations)

选择 SDK 前，先确定你是在“调用能力”还是“接入运行时”。业务应用调用 Endpoint 不需要安装运行时 SDK；已有 Agent 应用才需要注册与适配。

| 目标 | 使用组件 | 接下来 |
| --- | --- | --- |
| 应用调用 Agent/Team/Workflow | 普通 HTTP 客户端 | [Endpoint](/v2/zh/service/endpoints) |
| Java 应用加入目录 | `io.agentscope:agentscope-extensions-aistio` | [External Agent](/v2/zh/service/external-agent) |
| Python 框架接入 ASDP | `aistio-sdk` | [External Agent](/v2/zh/service/external-agent) |
| DeepSeek Harness 接入 | `@agentscope/dsh-aistio` 插件 | 安装插件并配置 HTTP 合约与 ASDP 地址 |
| 本机 Coding Agent 接入 | `agentscope` CLI 与 Runtime Host | [Hosted Agent](/v2/zh/service/hosted-agent) |

## 安装包与版本

Service、Java、Python 和 DSH 包独立版本化。按 Release 的 `release-manifest.json` 选择配套版本，不把镜像版本直接填进所有包管理器。

```bash
python -m pip install "aistio-sdk==$AISTIO_SDK_VERSION"
npm install "@agentscope/dsh-aistio@$DSH_AISTIO_VERSION"
```

在对应应用目录执行，变量设为 manifest 中的 SDK 版本。安装 DSH npm 包只是提供插件文件，还需要将插件加入你的 DSH profile，并配置控制面 HTTP、ASDP gRPC 和控制面可达的 contract 地址。provider 登录和应用生命周期仍由 DSH 管理。

## 检查能力与传输

标准完整 Service 使用 standalone HTTP；Java 提供 HTTP 注册与合约能力。ASDP 接入另外需要启用对应 listener 的部署。Python 自动注册依赖其 ASDP 路径，关闭 gRPC 并不能代替这一前提。

先验证目录身份，再验证会话/history，最后验证支持的派发、取消和结果回传。自定义框架通过适配器扩展，不能仅通过修改框架名称宣称新增能力。代码片段、凭据和网络细节见 [External Agent](/v2/zh/service/external-agent)。
