# MA1 / MA2 后续任务中断诊断

Issue：c11324b0-38c2-4da4-999b-e4ab6aad1a26（科普太阳系知识）。本次仅调查、保存证据，未修改运行状态或部署代码。

## 已确认的时间线（北京时间，2026-09-07）

- 14:56:37：MA2 的 write_file 返回 Written to solar_system_knowledge.md。随后提交 result={"path":"solar_system_knowledge.md"}，没有上传 Issue artifact。
- 15:03:27：MA1 read_file 同一相对路径，返回 File not found。不能据此认定 MA2 没有写文件；不同 Agent 的文件可见性需要通过 artifact 或共享位置明确交付。
- 15:03:46.813：MA1 的结构化 mention 成功路由到 MA2。Comment 40b05906-55ae-4896-8bd2-bca497a79416，route explicit/queued，新 Task f023ccdf-06c5-48d7-8d60-30f32b840338。
- 15:03:47.430：MA2 的 Execution Attempt 08d24626-e8c0-47b0-b525-02f1257efdc1 进入 running。
- 15:03:51.738：MA2 task.get 已收到完整追问，currentRequest 正确，随后再次发起模型请求。
- 15:03:58.591：MA1 调用 task.complete，outcome=blocked，message=“已向负责创建文档的工作代理查询了文件的具体位置，待其回复后再做进一步处理。”
- 15:03:58.670：该调用按 task.fail 处理，MA1 Attempt failed，code=objective_blocked。
- 15:03:58.697：MA2 Task cancelled。其 Node 被标记 cancelled，failureCode=fail_fast，failureMessage=cancelled after node failure。
- 15:03:58.699：Run ece47149-78c4-4c60-8d6a-41ecf5cf1b26 failed。
- 15:04:30.242：MA2 Execution Attempt 最终 cancelled。

## 根因与范围

MA2 已被唤醒并开始处理。中断发生在 MA1 将“等待已派出的后续任务”报告为 blocked 后：task.complete(blocked) 转入 task.fail，再触发 coordinator Node 失败及 Run fail_fast，取消 MA2。

代码路径：aistio/internal/httpapi/collaboration_mcp.go 的 task.complete / task.fail；aistio/internal/orchestration/engine.go 的 fail_fast 分支。成功完成的 leader follow-up 有 currentWorkerActive 等待检查，而 blocked 路径直接进入失败处理。当前提示词也未清楚区分追问后让出执行与不可恢复失败。

应补充“leader 追问并等待同一 Issue 的 worker”回归，明确等待语义，并保证不会误取消刚创建的后续任务；独立验证实际不可恢复失败仍能收敛。还需覆盖文件结果的跨 Agent 可见性。

附带状态缺口：MA2 Task/Attempt 已 cancelled，但 Task input 仍为 delivered；Run 导出的事件序列未见这次取消对应的 task/attempt/node 取消事件。上述不应作为完整状态审计通过。

## 证据

所有 API 原始响应（敏感字段已脱敏）保存在 evidence/；issue-initial.json 含 Issue、Comments、Routes、Tasks、Run graph、Attempts 和 Run events；ma1-messages.json、ma2-events.json 与 ma2-original-messages.json 提供实际工具调用。

工作目录 /Users/ken/agentscope-2/agentscope-java，分支 agentscope-service-v5。本次没有修改产品代码、提交或重启集群。
