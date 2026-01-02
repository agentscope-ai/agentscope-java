# Plan

PlanNotebook provides planning capabilities for agents, helping them break down complex tasks into structured subtasks and execute them step by step.

## Enable Planning

### Option 1: Use Default Configuration (Recommended)

```java
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .enablePlan()  // Enable planning
        .build();
```

### Option 2: Custom Configuration

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .maxSubtasks(10)  // Limit number of subtasks
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .planNotebook(planNotebook)
        .build();
```

## Usage Example

```java
// Create an agent with planning support
ReActAgent agent = ReActAgent.builder()
        .name("PlanAgent")
        .sysPrompt("You are a systematic assistant that breaks down complex tasks into plans.")
        .model(model)
        .toolkit(toolkit)
        .enablePlan()
        .build();

// Assign a complex task to the agent
Msg task = Msg.builder()
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder()
                .text("Build a simple calculator web app with HTML, CSS and JavaScript.")
                .build()))
        .build();

// Agent will automatically create a plan and execute it step by step
Msg response = agent.call(task).block();
```

## Planning Tools

When planning is enabled, the agent automatically gets access to these tools:

| Tool Name | Purpose |
|-----------|---------|
| `create_plan` | Create a new plan |
| `revise_current_plan` | Revise the current plan |
| `update_subtask_state` | Update subtask state (todo/in_progress/abandoned) |
| `finish_subtask` | Mark subtask as done |
| `view_subtasks` | View subtask details |
| `finish_plan` | Complete or abandon the entire plan |
| `view_historical_plans` | View historical plans |
| `recover_historical_plan` | Recover a historical plan |

The agent will call these tools automatically based on the task - no manual intervention needed.

## Workflow

1. **Create Plan**: Agent analyzes the task and calls `create_plan` to create a plan with multiple subtasks
2. **Execute Subtasks**: Execute each subtask in sequence
3. **Update Status**: Call `finish_subtask` to update status after completing each subtask
4. **Complete Plan**: Call `finish_plan` after all subtasks are done

During execution, the system automatically injects plan hints before each reasoning step to guide the agent.

## Complete Example

See `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/PlanNotebookExample.java`

## Configuration Options

### Limit Subtask Count

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .maxSubtasks(10)  // Maximum 10 subtasks
        .build();
```

### Custom Storage

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .storage(new InMemoryPlanStorage())  // Default in-memory storage
        .build();
```

### Custom Hint Generation

```java
PlanNotebook planNotebook = PlanNotebook.builder()
        .planToHint(new DefaultPlanToHint())  // Custom plan-to-hint strategy
        .build();
```
