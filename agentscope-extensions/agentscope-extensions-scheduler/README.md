# AgentScope Scheduler Extension

## Overview

The AgentScope Scheduler Extension provides scheduled execution capabilities for Agents, allowing them to run at specified times or intervals. The scheduler module adopts an extensible architecture design that supports multiple scheduling implementations.

***Note: The current version only includes the XXL-Job based implementation**, which is suitable for distributed task scheduling.*

## Key Features

- ⏰ **Scheduled Automatic Execution**: Support for periodic automatic execution of Agents
- 🏗️ **Extensible Architecture**: Support for multiple scheduling implementations through the `AgentScheduler` interface
- 🌐 **Distributed Scheduling**: Distributed execution across multiple executor instances
- 🎯 **Centralized Management**: Unified schedule management through admin console
- 🔒 **State Isolation**: Dynamically creates fresh Agent instances for each execution
- 🔄 **Dynamic Configuration**: Loads latest configuration at execution time
- 📊 **Monitoring & Logging**: Built-in monitoring and execution logging capabilities

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                   Configuration Layer                     │
│  AgentConfig (with ModelConfig)  +  ScheduleConfig       │
│  (defines how Agent is created)     (defines scheduling)  │
└──────────────────────┬───────────────────────────────────┘
                       │ passed as parameters
                       ▼
┌──────────────────────────────────────────────────────────┐
│                   Core Interface Layer                    │
│                                                          │
│  ┌─────────────────────────┐                             │
│  │   AgentScheduler        │  schedule()    ┌────────┐   │
│  │  ┌───────────────────┐  │  ─────────────>│ Task 1 │   │
│  │  │ - schedule()      │  │                ├────────┤   │
│  │  │ - cancel()        │  │                │ Task 2 │   │
│  │  │ - getScheduledAgent()│                ├────────┤   │
│  │  │ - shutdown()      │  │  <─────────────│ Task N │   │
│  │  └───────────────────┘  │                └────────┘   │
│  └─────────────────────────┘           ScheduleAgentTask │
│         (interface)                    (task control & exec)│
└──────────────────┬───────────────────────────────────────┘
                   │ multiple implementations
       ┌───────────┼───────────┐
       ▼           ▼           ▼
┌──────────┐ ┌──────────────┐ ┌──────────┐
│ XXL-Job  │ │ Spring Task  │ │  Other   │
│    ✅    │ │      🔜       │ │    🔜    │
└──────────┘ └──────────────┘ └──────────┘

✅ Implemented  🔜 Planned
```

**Core Design**:
- **Configuration Layer**: Defines Agent metadata and scheduling strategy
- **Interface Layer**: `AgentScheduler` creates and manages multiple `ScheduleAgentTask` instances
- **Implementation Layer**: Implements `AgentScheduler` interface based on different frameworks

## Quick Start

### 1. Add Dependency

```xml
<dependency>
<groupId>io.agentscope</groupId>
<artifactId>agentscope-extensions-scheduler-xxl-job</artifactId>
<version>${agentscope.version}</version>
</dependency>
```

### 2. Basic Usage (XXL-Job Implementation)

**Step 1.** Deploy the XXL-Job scheduling server. You can complete the deployment through one of the following methods:

#### Option 1: 👉 [Deploy Open Source Service](https://www.xuxueli.com/xxl-job/)

#### Option 2: 👉 [Create Cloud Service](https://help.aliyun.com/zh/mse/getting-started/get-started-with-xxljob-in-10-minutes?spm=5176.mse-ops.console-base_help.dexternal.717a2675Rig1IO)

> **Note**: After deploying the server, obtain the corresponding server access address (e.g., `http://localhost:8080/xxl-job-admin`), which will be needed in subsequent configuration.


**Step 2.** Connect your business application to the XXL-Job server:

Initialize the XXL-Job Executor and create a scheduler instance to connect it to the XXL-Job admin server:

```java
// Initialize XXL-Job Executor
XxlJobExecutor executor = new XxlJobExecutor();
executor.setAdminAddresses("http://localhost:8080/xxl-job-admin");  // Server address obtained in Step 1
executor.setAppname("agentscope-executor");                          // Application name, must match server configuration
executor.setAccessToken("xxxxxxxx");                                 // Access token (optional, recommended for production)
executor.setPort(9999);                                              // Executor port
executor.start();

// Create AgentScope scheduler instance
AgentScheduler scheduler = new XxlJobAgentScheduler(executor);
```

> **Note**: After the Executor starts, it will automatically connect to the XXL-Job admin server. Ensure that the configured address and port are correct and accessible. For existing applications already connected to XXL-Job, you only need to create the corresponding `AgentScheduler`.

**Step 3.** Define the Agent that needs to run on a schedule:

Create an Agent configuration and register it with the scheduler:

```java
// Create model configuration
ModelConfig modelConfig = DashScopeModelConfig.builder()
    .apiKey(apiKey)
    .modelName("qwen-plus")
    .build();

// Create Agent configuration (choose one of the following two methods)

// 【Method 1】Using AgentConfig
// Suitable for scenarios where no toolkit binding is needed
AgentConfig agentConfig = AgentConfig.builder()
    .name("MyScheduledAgent")  // Agent name (will be used as JobHandler name)
    .modelConfig(modelConfig)
    .sysPrompt("You are a helpful assistant")  // Agent's function description and business execution flow definition
    .build();

// 【Method 2】Using RuntimeAgentConfig (transitional approach)
// Use only when toolkit binding is needed. RuntimeAgentConfig is a transitional product, mainly to allow binding of Tools toolkit at this stage
// The toolkit injection method will be adjusted in future versions
RuntimeAgentConfig agentConfig = RuntimeAgentConfig.builder()
    .name("MyScheduledAgent")  // Agent name (will be used as JobHandler name)
    .modelConfig(modelConfig)
    .sysPrompt("You are a helpful assistant")
    .toolkit(toolkit)  // Bind toolkit, such as: send notifications, generate reports, data cleanup, etc.
    .build();

// Register Agent to scheduler to enable scheduled execution
ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
```

> **Note**: After registration, you need to configure the Agent's scheduling strategy (CRON expression, execution frequency, etc.) in the XXL-Job admin console. The Agent name `MyScheduledAgent` will be displayed as the JobHandler name in the console.

**Step 4.** Configure the Agent for scheduled execution in the scheduling console and view its runtime logs:  

｜ Create corresponding Agent task and configure scheduled execution cycle  
![Agent Task Configuration](images/agent-task-config.png)
  
｜ View Agent execution logs, which will include event log feedback from each interaction with the model during Agent runtime  
![Agent Runtime Logs](images/agent-task-log.png) 

### General Requirements
- Java 17 or higher
- AgentScope Core 1.0.1+

### Scheduler-Specific Requirements

**For XXL-Job Implementation:**
- XXL-Job 2.4.0+ 
- When connecting to cloud-based MSE XXL-Job service, business application Agents need to be deployed in the same VPC

**For Future Implementations:**
- Add other open-source scheduled execution implementations
- To be determined

## License

Apache License 2.0
