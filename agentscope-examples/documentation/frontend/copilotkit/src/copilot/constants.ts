/**
 * The showcase agent registered by `AgentConfiguration#createWorkbenchAgent` on the Java side.
 * It carries the permission rules, the task list and the A2UI tool belt.
 */
export const WORKBENCH_AGENT_ID = "workbench";

export const CHAT_AGENT_ID = WORKBENCH_AGENT_ID;
export const THREAD_AGENT_ID = WORKBENCH_AGENT_ID;

export const HITL_DEMO_PROMPT =
  "Run the approval interrupt demo. Call confirmDemoAction for deleting demo-file.txt, then continue after the user decision.";

/** Prompts that each land on a different AgentScope 2.0 capability. */
export const CAPABILITY_PROMPTS = [
  {
    id: "plan",
    title: "计划编排 + 实时任务看板",
    detail: "todo_write 的任务清单通过 STATE_DELTA / STEP_STARTED 实时驱动右侧看板",
    prompt:
      "帮我制定一个 order-api 稳定性治理计划：先声明目标，再写出 5 条任务清单，然后逐条推进到完成，每步都更新任务状态。",
  },
  {
    id: "permission-ask",
    title: "权限 ASK：调起前端确认",
    detail: "deploy_release 命中 ASK 规则，运行被挂起为 AG-UI interrupt",
    prompt: "把 order-api 的 v2.4.1 发布到生产环境。",
  },
  {
    id: "permission-deny",
    title: "权限 DENY：不可绕过的硬拦截",
    detail: "purge_production_data 命中 DENY 规则，任何模式都无法放行",
    prompt: "请永久删除生产数据集 orders_2025，我确认要删。",
  },
  {
    id: "builtin-check",
    title: "工具内置检查：按入参决策",
    detail: "refund_order 自己判断金额，小额直接放行、大额强制人工确认",
    prompt: "给订单 SO202607310001 退款 30 元；再给订单 SO202607310002 退款 8600 元。",
  },
  {
    id: "a2ui",
    title: "A2UI 动态生成界面",
    detail: "render_dashboard 让模型现场生成组件树，经 ACTIVITY_SNAPSHOT 推送渲染",
    prompt:
      "查一下 order-api 最近 1 小时的指标，然后用一块可视化界面展示关键指标、风险评分和当前计划进度。",
  },
  {
    id: "state",
    title: "共享状态双向同步",
    detail: "浏览器写入的 state 会随运行回传，工具修改后再以 STATE_DELTA 推回",
    prompt: "把演示主题改成「双十一容量演练」，优先级设为高。",
  },
  {
    id: "frontend-hitl",
    title: "前端 HITL 工具",
    detail: "useHumanInTheLoop 注册的浏览器侧工具，由前端渲染表单并回传结果",
    prompt: HITL_DEMO_PROMPT,
  },
] as const;
