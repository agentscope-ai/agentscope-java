# Work Hub Issue 详情页对齐分析

## 结论

旧页面的问题不只是视觉密度，而是信息架构没有围绕 Issue 协作主链路组织：标题、讨论、子任务、执行、附件与属性被拆成多个等权卡片，用户无法快速回答“现在是什么状态、谁在处理、发生了什么、下一步做什么”。

目标页面采用与 Multica 相近的两栏工作台：左侧承载 Issue 内容与按时间排序的 Activity，右侧承载可直接操作的 Properties、验收条件、外部来源、执行记录、附件和审计信息。能力命名仍保持 AgentScope 的领域模型，不复制竞品中不存在于当前服务端的概念。

## 本次落地

| 方向 | 旧页面 | 优化后 |
|---|---|---|
| 信息架构 | 统计卡片、Run、Discussion、Assignment 等纵向堆叠 | 主内容/Activity + Properties 双栏，窄屏属性抽屉 |
| Issue 内容 | 标题、描述只读 | 标题与 Markdown 描述就地编辑 |
| 状态与属性 | 顶部动作按钮分散；负责人需单独卡片提交 | 状态、负责人、优先级、截止时间在侧栏就地修改 |
| Discussion | 评论平铺，回复关系不可见 | 顶层讨论卡片 + 线程回复 + resolve/reopen |
| Activity | 只展示评论 | 合并真实 Issue Activity 与评论，状态流转可追溯 |
| 子任务 | 侧栏小卡片 | 主内容区的 Sub-issues 清单、完成进度与快速创建 |
| Agent 协作 | mention 控件长期占据表单 | 按需展开 mention，保留路由 outcome 可见性 |
| 验收 | `acceptanceCriteria` 不可见 | checklist 可展示、新增、勾选；显示结果/附件/审批门槛 |
| 执行 | Run 与 AgentTask 分散 | Execution log 汇总 Run 与 AgentTask，并链接 Operations |
| 附件 | 只能上传，上传后不可见 | Issue artifacts 查询接口与附件清单，评论器内快速上传 |
| 订阅与导出 | 导出有入口，订阅能力未接 UI | 顶部订阅人数、订阅/取消订阅、JSON 导出 |
| 响应式 | 两列卡片自然换行，属性定位不稳定 | 桌面固定双栏，窄屏默认聚焦正文并使用属性抽屉 |

## 建议的后续能力

以下能力不能只靠前端补齐，需要先扩展领域模型和 API：

1. **Reaction**：新增 comment reaction 聚合与当前用户状态，支持幂等增删和 Activity 审计。
2. **Pin / highlight**：给 Comment 或 Activity 增加 pin 关系，用于沉淀结论，而不是仅做本地 UI 状态。
3. **PR 自动关联**：扩展 external links，将 branch、commit、pull request 与 Issue identifier 自动关联；当前 Source 只展示已有 `sourceType/sourceRef`。
4. **执行成本**：形成 Issue 级 usage projection，统一聚合 Run、AgentTask、Attempt 的 token、费用与耗时；避免前端猜测不同 provider 的原始 usage JSON。
5. **Project、Label 与自定义属性**：先定义可检索、可授权、可审计的属性 schema，再开放 Add property；不建议把任意 JSON 直接暴露给用户。
6. **通知体验**：将 subscriber 与 Inbox 打通为可配置的通知级别（全部、mentions、状态变更），并显示未读状态。

优先级建议：Reaction/评论编辑与删除 → PR 自动关联 → Issue usage projection → Project/Label/自定义属性。前两项最直接提升协作闭环，usage projection 则是 AgentScope 相比通用 Issue 产品最应该强化的差异化能力。
