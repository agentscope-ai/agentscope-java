---
title: "Managed 任务结果与失败处理"
en_link: /v2/en/service/managed-harness-task-outcomes
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Managed Agent 执行持久任务时，需要明确交付结果，而不是只结束一轮模型回复。本页用于理解等待、阻塞和失败后的下一步。

## 读取结果并提交反馈

本文 Outcome 描述执行器如何报告任务，不是客户端可随意 PATCH 的状态字段。业务应用使用自己的工作入口查询结果：

| 工作入口 | 读取与反馈 API |
| --- | --- |
| 已发布服务 | `GET /invoke/v1/invocations/{id}`，读取 `invocation.status/result`；用 actions、inputs 等公共命令反馈 |
| Issue | `GET /api/v1/issues/{id}/summary` 查看工作汇总；`GET /api/v1/agent-tasks/{taskId}` 查看任务；`GET /api/v1/execution-attempts/{attemptId}` 查看实际执行 |
| 人工验收 | `POST /api/v1/issues/{id}/accept`，`{expectedVersion}`；退回用 `/reject`，`{expectedVersion,reason}` |
| 原生 Managed 会话 | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}`；根据 turn 状态处理 actions、cancel 或 resume |

先读取最新 Issue 的 version 并核对产物，再提交验收。Invocation completed、运行 succeeded、原生 turn completed 和 Issue accepted 是不同资源的状态；不要直接对照名字替代业务判断。运行时的 complete/fail 回报由受限任务或执行身份发出，应用接入详见[任务反馈](/v2/zh/service/inbox)。

## 结果意图

| Outcome | 意义 | 下一步 |
| --- | --- | --- |
| succeeded | 有明确交付且执行可完成 | 检查产物并按 Issue 策略验收 |
| waiting | 等待已存在、可追踪的依赖任务 | 查看依赖 ID 和真实状态 |
| blocked | 缺少信息或条件，保留当前已完成部分 | 补充具体输入，再沿工作流程继续 |
| failed | 执行失败 | 阅读错误与部分结果，修复后决定重试 |

“稍后继续”“正在等待”这类普通文本不构成成功。还有未结束的后台任务或异常停止时，也不能据此判定成功。Runtime 为续轮和等待设置预算，超过限制时会停止自动推进。

## 子任务和交付物

子任务使用独立执行上下文，通过持久任务记录返回结果。创建子 Agent 不会自动增加它没有的网络、工具或权限。查询不到依赖时应报告错误，不能无限等待一个不存在的任务。

Lead 应把子结果整理到主 Issue 的结果和 Artifact 中，标明部分完成、失败与未确认事实。文件搜索结果被截断时需要缩小范围继续查询，而非把截断内容当作完整证据。

## 恢复与人工验收

Blocked 的协调工作可以在获得新人工输入后继续；明确 failed 的执行则保留终止记录，必要时发起新的执行。取消尽力停止底层任务，确认前检查最终状态。

Agent 可在授权范围记录验收证据，但不能改写人的要求或绕过人工审核。Run 或 Task 成功不等于报告事实正确；用户应在 [Inbox](/v2/zh/service/inbox) 对照标准检查完整结果。

## 示例：判定售前方案交付

在[CRM 方案交付案例](/v2/zh/service/cases/in-product-delivery)中，Agent 需要实际文件和来源证据才能交付。扩展为 Team 时还需核对成员结果。下面用于解释任务结果，不能用自然语言直接改写状态。

| 观察到的证据 | 应如何处理 |
| --- | --- |
| 质量复核的真实任务仍在运行 | 跟踪依赖 ID，等待复核；不要先宣布完成 |
| 有方案正文，但没有要求的文件 | 指出缺失交付物，安排补充 |
| 产品资料读取失败 | 检查权限与绑定，保留已有结果，补齐条件后继续 |
| 文件已交付，来源和待确认项齐全 | 汇总并完成协调，按 Issue 策略等待人工验收 |
| 方案承诺资料未支持的能力 | 要求修订，即使底层运行成功也不符合验收标准 |

补充输入时引用原 Issue、Task 和缺失项，例如“请补交 open-questions.md，列出未确认条件及其来源”。恢复执行依赖持久记录，不依赖某轮模型回复中的承诺。
