---
title: "第一次对话与交付"
---

[English](/v2/en/service/first-session)

本教程从 Chat 开始，完成一次回复，再把工作转为可验收的 Issue。已有可用 Agent 时可跳过创建步骤。

## 1. 准备一个 Managed Agent

登录控制台。新安装先按[快速安装](/v2/zh/service/quickstart)配置模型凭据和 Environment。在 **DESIGN → Agents** 创建“资料助手”，Runtime 明确选择 **AgentScope Managed**，Instructions 填写：

```text
根据用户提供的材料整理要点。区分事实和待确认信息。
没有足够材料时说明缺失项，不虚构来源。
```

在 Advanced settings 选择可用 Environment。首次只做文本工作，暂不添加额外外部工具，保存并打开 Agent。

## 2. 在 Chat 验证回复

打开 **WORK → Chat → New chat**，选择资料助手，发送：

```text
请将以下会议记录整理为待办清单：
周五前完成安装说明；负责人小李。下周一评审，时间待确认。
请列出任务、负责人、期限与待确认事项。
```

检查回复是否只包含提供的事实，继续追问“哪项信息还需要确认？”。刷新页面，重新打开同一 Chat，确认两轮历史保留。

## 3. 创建可交付的工作

点击 Chat 的 **Create issue**，把标题改为“整理安装说明待办”，在说明中加入上述材料，选择资料助手作为负责人，并确认 Sharing。创建后添加验收要求：列出负责人、期限和待确认事项，不补造会议时间。

查看 Executions 中的工作进展，读取结果评论和交付物。若没有开始执行，检查负责人和运行时就绪信息；若被阻塞，按最新说明补充信息。

## 4. 验收

当采用人工验收策略的 Issue 进入 In review，在 **WORK → Inbox** 打开 Review result。对照验收要求，满足则 **Accept result**；不满足则 **Request changes**，写明缺失内容，再安排后续执行。

Done 表示该工作按策略完成。成功标准包括实际内容符合要求，而不是只看到绿色执行状态。这个小例子通过后，再加 Workspace 文件和一个只读工具，详见 [Managed Agent](/v2/zh/service/managed-agent)。
