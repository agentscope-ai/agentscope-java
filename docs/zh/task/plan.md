# Plan

PlanNotebook 为智能体提供计划管理能力，帮助智能体将复杂任务分解为结构化的子任务并逐步执行。

## 启用计划功能

### 方式一：使用默认配置（推荐）

```java
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .enablePlan()  // 启用计划功能
        .build();
```

### 方式二：自定义配置

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .maxSubtasks(10)  // 限制子任务数量
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .planNotebook(planNotebook)
        .build();
```

## 使用示例

```java
// 创建支持计划的智能体
ReActAgent agent = ReActAgent.builder()
        .name("PlanAgent")
        .sysPrompt("You are a systematic assistant that breaks down complex tasks into plans.")
        .model(model)
        .toolkit(toolkit)
        .enablePlan()
        .build();

// 给智能体分配复杂任务
Msg task = Msg.builder()
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder()
                .text("Build a simple calculator web app with HTML, CSS and JavaScript.")
                .build()))
        .build();

// 智能体会自动创建计划并逐步执行
Msg response = agent.call(task).block();
```

## 计划工具

启用计划功能后，智能体会自动获得以下工具：

| 工具名称 | 用途 |
|---------|------|
| `create_plan` | 创建新计划 |
| `revise_current_plan` | 修订当前计划 |
| `update_subtask_state` | 更新子任务状态（todo/in_progress/abandoned）|
| `finish_subtask` | 标记子任务为已完成 |
| `view_subtasks` | 查看子任务详情 |
| `finish_plan` | 完成或放弃整个计划 |
| `view_historical_plans` | 查看历史计划 |
| `recover_historical_plan` | 恢复历史计划 |

智能体会根据任务自动调用这些工具，无需手动干预。

## 工作流程

1. **创建计划**：智能体分析任务，调用 `create_plan` 创建包含多个子任务的计划
2. **执行子任务**：按顺序执行每个子任务
3. **更新状态**：完成子任务后调用 `finish_subtask` 更新状态
4. **完成计划**：所有子任务完成后调用 `finish_plan`

执行过程中，系统会自动在每次推理前注入计划提示，引导智能体按计划执行。

## 完整示例

参考 [PlanNotebookExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/PlanNotebookExample.java)

## 配置选项

### 限制子任务数量

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .maxSubtasks(10)  // 最多 10 个子任务
        .build();
```

### 自定义存储

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .storage(new InMemoryPlanStorage())  // 默认内存存储
        .build();
```

### 自定义提示生成

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .planToHint(new DefaultPlanToHint())  // 自定义计划转提示策略
        .build();
```
