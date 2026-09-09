# 人类授权基于自身知识回答后仍要求 KEY：排查与修复

初次排查为只读，未修改业务代码、issue 状态或评论；后续修复与部署情况见文末。代码目录 `/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。

## 已确认事实

两个子 issue 属于相同父 issue `12d535ba-5faa-48ed-b479-4cf5ba8f7a24`，相同 team、相同 worker `678ae47f-0848-43d9-95e5-a79f5ed9fa72`。两条人类输入均为“你直接根据自己的认知回答就好了”。

- 手机 issue `d83331ee-40a4-43c6-bc1c-daffa3257c1e`：10:40:32 人类回复，10:40:33 新任务执行。Session `ed682f3c-7457-42a9-aeab-661e2722acfc` 的事件 #6（task.get 结果）中 currentRequest 与 inputs.comment.content 均包含完整原话。#8 仍调用 web_search，#10 返回 TAVILY_API_KEY 未设置，#12 主动调用 task.fail(MISSING_CREDENTIALS)，attempt/task failed，issue 回到 blocked。
- 新能源 issue `8ab478cb-24b8-4d9f-91a6-b4f204875813`：10:40:38 人类回复。Session `1ded3391-2b22-4b7f-86e7-87fdf1bf0e07` 同样在 #6 收到完整 currentRequest；#8 同样调用 web_search，#10 同样缺 KEY。区别在 #12 调用 task.complete 并提交自身知识报告；attempt succeeded、task completed，随后 Lead 接受，issue done。
- 手机失败后的 Lead Session `1afa2551-b5f5-49c0-be37-8ba14abf3fb8`：task.get.coordinatorChildren 中该子 issue 的 humanUpdates 包含完整人类原话；Lead 仍调用 issue.comment.add 请求 KEY，再 task.complete 结束自己的决策轮。
- 两个 worker 的初始唤醒指令相同，已经包含“Read task.get currentRequest first”和“routed comments ... override older Issue requirements”。因此不是缺少这一句提示，也不是人类回复传输丢失。

## 判断与边界

现有记录支持：当前人类指令已经交付，但模型仍选用了原 issue“最新发展调研”的联网路线。新能源场景只是工具失败后选择了知识回答；不能据最终成功判定它从开始就正确遵循了免联网要求。Lead 对手机失败也没有根据 humanUpdates 重新评估 KEY 是否仍是必要条件。

这是新要求在执行与验收中落实不稳定的问题；记录不支持“上下文没带过去”。更稳妥的修复方向是明确当轮有效目标、允许的数据来源和证据边界，按当轮目标判断工具是否必要，失败与 Lead 决策均重新核对最新人类授权。不能把任意工具报错一律等价为目标不可完成，也不能在仍要求联网核验的任务中擅自用模型知识宣告成功。

当前 session context API 返回 503，未据此猜测未返回的系统提示。上述结论来自执行当时已持久化的 task.get 工具结果、工具调用、task/attempt 以及 issue 评论和活动记录。原始响应已在 evidence 中脱敏留存。


## 修复与部署（2026-09-08）

代码直接修改于主目录 `/Users/ken/agentscope-2/agentscope-java`、分支 `agentscope-service-v5`，未使用独立 worktree。此次改动在 Go 控制平面：

- `task.get.executionBrief` 将原始目标、触发消息、人类修订分开。修订按时间排列，并限制在当前 task 的创建/合并输入时间边界内，避免消费后来另行路由的指令。
- managed session 的实际唤醒消息携带这些内容，让 agent 在首次选工具之前看到人类修订；不只在一大段历史上下文里留下回复。
- Worker 在请求 KEY 或失败前重新判断工具对修订后目标是否仍必要；Lead 验收和处理失败也执行同样的判断。允许知识回答时说明时效与未核验边界；仍要求实时来源时保留阻塞，不伪造检索结果。
- 保留 review 认可回复不授权新工作的约定。未引入中文关键词匹配，也未全局禁止搜索。语义选择仍依赖模型，以下是实际回归证据，不能据此保证所有模型与表述都零偏差。

验证通过：`go test ./...`、collaboration/runtimebinding/httpapi 定向测试、真实 PostgreSQL 的 `TestHumanFollowUpOnFailedTeamCreatesRootContinuation`，以及 `go build -o bin/aistiod ./cmd/aistiod`。日志见本目录。使用 `BUILDER_REBUILD=0 BUILDER_RESET_DB=0 agentscope-service/scripts/dev-up.sh` 重启，保留数据，服务健康检查通过。

## 真实集群回归

使用用户原场景的相同 team / worker / lead，核对 issue 导出、task、attempt、session events 中的实际工具调用、task.get.executionBrief 和物理唤醒消息。

| 场景 | 实际结果 |
| --- | --- |
| Team 主任务 `e5226c12-52dd-4c43-955a-de61e6fa188f` | 两个子 issue 均 done，Lead 汇总后主 issue 为 in_review |
| 新能源子任务 `a07fc4ab-9c7d-4c53-bebc-7d757c468936` 初次搜索缺 KEY，收到“你直接根据自己的认知回答就好了” | 恢复轮仅调用 task.get、task.complete，无 web_search；task completed、attempt succeeded，Lead 接受 |
| 同 Team 手机子任务 `79c50a4c-a4cb-4c8f-a578-6244baed1d73` | 初次直接提交带时效说明的知识报告，Lead 接受；此分支没有经历人类回复恢复，不作为恢复测试计数 |
| 手机任务 `d52f4ccc-4af1-4636-b144-2f91c5cb60d5` 要求今天实时新闻、可核验链接，并再次明确不接受知识替代 | 两轮均执行搜索、报告缺 KEY，issue blocked，task/attempt failed；没有伪造成功 |
| 上述手机任务随后收到“你直接根据自己的认知回答就好了” | 两条人类修订均保留，最新要求生效；恢复轮仅 task.get、task.complete，无 web_search；task completed、attempt succeeded，issue in_review |

证据：`audit.json` 为最终回归；`audit-strict-before-relax.json` 保存严格来源要求仍 blocked 的中间状态；`evidence/` 保存各 API 导出与 session 事件。历史失败 task/attempt 保留，不因后续恢复而覆写为成功。


## 原 issue 重试验证与边界

通过正式 `POST /api/v1/agent-tasks/6f1032a3-f7bd-44da-91ff-ecc5e2eb62eb/retry` 入口重试，沿用原触发评论，没有新增或伪造用户回复。

- 新 worker task `ba82a6a6-a35e-482a-bc36-2aeedca005c1` completed，attempt succeeded；实际工具仅 `task.get`、`task.complete`，没有搜索或请求 KEY，已交付手机报告。
- 原手机 issue `d83331ee-40a4-43c6-bc1c-daffa3257c1e` 最终 in_review，新运行 `c39ad0f1-d51c-4cfd-be90-ec268cb1220e` succeeded。
- **恢复链路的独立问题**：重试入口将这个子 issue 建成了新运行的 root，没有接回原父任务。原主 issue `12d535ba-5faa-48ed-b479-4cf5ba8f7a24` 仍 blocked；其旧运行 `264a94bc-ef8d-4199-ae21-99231fc3fdd5` 此前因 managed_turn_incomplete 失败。本次没有更改这个旧运行或宣称主 issue 已恢复。
- 新运行中的 Lead 完成后还产生了一轮 worker 结果回复，最终均收敛；它也属于重试运行关联的后续问题，不能将本次结果表述为整个旧任务恢复链路无缺陷。

最终 task/attempt/工具审计见 `original-retry-audit.json`，完整导出见 `evidence/original-*.json`。
