---
title: "Workflows：设计可重复执行的流程"
---

[English](/v2/en/service/workflows)

**DESIGN → Workflows** 用于有明确步骤、依赖和人工关口的工作。定义保存可编辑草稿；发布生成不可变 revision；每次执行固定一个 revision，因此后续编辑不会改变已有运行的拓扑。

## 从一个 Agent 步骤开始

1. 新建 Workflow，填写名称并选择 Initial Workflow Agent。
2. 在设计器中设置节点 key 和目标 Agent。节点 key 在同一流程中必须唯一。
3. 保存草稿，执行校验，修复未选目标、未知节点引用或环路等错误。
4. 发布版本，进入执行入口选择该版本和新建或已有 Issue，填写 JSON input 后启动。
5. 检查执行图、节点输出和 Issue 结果；确定单步可用后再添加更多节点。

## 可用节点

| 节点 | 用途 |
| --- | --- |
| agent | 交给一个 Agent 执行 |
| team | 交给 Team 协作 |
| condition | 根据 CEL 条件选择路径 |
| join | 汇合分支，按配置决定何时满足依赖 |
| approval | 等待指定人员审批 |
| timer | 等待指定时长 |
| signal | 等待具名外部信号 |
| subrun | 调用指定的已发布 Workflow revision |

例如“生成草稿 → 人工审批 → 发布准备”可以使用 agent、approval、agent 三个节点，并建立相应边。审批通过只放行下一步；对外发布能力仍需要目标 Agent 的工具和权限。

## 数据映射与校验

Input mapping 把输入名称映射为 CEL 表达式，例如 `run.input.request`。表达式可引用运行输入、变量、Issue、trigger 和前序节点的状态、输出、产物。使用设计器检查依赖关系，先查看真实节点输出，再编写下游字段映射。

CEL 是受限表达式，不能当作任意脚本执行器。流程必须是无环图；子流程引用固定版本。JSON 编辑和图形编辑操作的是同一份定义，发布前都需要服务端校验。

## 控制执行

Pause 阻止新节点调度，已经运行的步骤仍可返回结果；Resume 恢复调度。Cancel 请求取消节点、任务和子运行，但不会把 Issue 自动验收或删除。

终态后使用 Rerun 会创建新的 Run 并保留来源关联。选择 `fail_fast`、`continue` 或 `partial_success` 时，要明确部分成功是否能满足业务目标。流程不提供自动补偿已发生外部副作用的保证。

## 提供给应用使用

发布后在 Endpoint 配置中选择具体 revision。新 revision 发布不等于调用方已切换；更新 Endpoint release 后再检查实际调用版本。

下一步：[Endpoint 接入](/v2/zh/service/endpoints) · [执行状态参考](/v2/zh/service/sessions)。
