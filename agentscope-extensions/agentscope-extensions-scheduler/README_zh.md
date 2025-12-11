# AgentScope 调度器扩展 [Incubating]

## 概述

AgentScope 调度器扩展为 Agent 提供定时调度执行功能，允许它们在指定的时间或时间间隔运行。调度器模块采用可扩展的架构设计，支持多种调度实现。

*注：**当前版本暂且仅包含基于 XXL-Job 的实现**，适用于分布式任务调度。*

## 核心特性

- ⏰ **定时自动执行**：支持 Agent 周期性自动执行
- 🏗️ **可扩展架构**：通过 `AgentScheduler` 接口支持多种调度实现
- 🌐 **分布式调度**：跨多个执行器实例的分布式执行
- 🎯 **集中管理**：通过管理控制台统一管理 Agent 定时调度
- 🔒 **状态隔离**：每次执行动态创建全新的 Agent 实例
- 📊 **运行日志**：支持 Agent 执行日志采集功能

## 架构说明

```
┌──────────────────────────────────────────────────────────┐
│                     配置层                                │
│  AgentConfig (含 ModelConfig)  +  ScheduleConfig         │
│  (定义 Agent 如何创建)              (定义调度策略)           │
└──────────────────────┬───────────────────────────────────┘
                       │ 作为参数传入
                       ▼
┌──────────────────────────────────────────────────────────┐
│                     核心接口层                             │
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
│         (接口)                           (任务控制与执行)   │
└──────────────────┬───────────────────────────────────────┘
                   │ 多种实现
       ┌───────────┼───────────┐
       ▼           ▼           ▼
┌──────────┐ ┌──────────────┐ ┌──────────┐
│ XXL-Job  │ │ Spring Task  │ │  Other   │
│    ✅    │ │      🔜       │ │    🔜    │
└──────────┘ └──────────────┘ └──────────┘

✅ 已实现  🔜 规划中
```

**核心设计**：
- **配置层**：定义 Agent 元数据信息和调度策略
- **接口层**：`AgentScheduler` 创建并管理多个 `ScheduleAgentTask`
- **实现层**：基于不同框架实现 `AgentScheduler` 接口

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-scheduler-xxl-job</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 2. 基本使用（XXL-Job 实现）

**步骤 1.** 需要先部署 XXL-Job 调度服务端。

#### 部署方式参考：👉 [部署开源服务](https://www.xuxueli.com/xxl-job/)

> **注意**：服务端部署后，获取对应的服务段接入地址（如 `http://localhost:8080/xxl-job-admin`），在后续配置中需要使用。

**步骤 2.** 业务应用接入 XXL-Job 服务端：

初始化 XXL-Job Executor 并创建调度器实例，使其连接到 XXL-Job 管理服务端：

```java
// 初始化 XXL-Job Executor
XxlJobExecutor executor = new XxlJobExecutor();
executor.setAdminAddresses("http://localhost:8080/xxl-job-admin");  // 步骤 1 中获取的服务端地址
executor.setAppname("agentscope-demo");                          // 应用名称，需与服务端配置一致
executor.setAccessToken("xxxxxxxx");                                 // 访问令牌（可选，建议生产环境配置）
executor.setPort(9999);                                              // Executor 端口
executor.start();

// 创建 AgentScope 调度器实例
AgentScheduler scheduler = new XxlJobAgentScheduler(executor);
```

> **注意**：Executor 启动后会自动连接到 XXL-Job 管理服务端，确保配置的地址和端口正确可访问。存量已接入过的应用只需创建对应`AgentScheduler`即可

**步骤 3.** 定义需要定时运行的 Agent：

创建 Agent 配置并注册到调度器中：

```java
// 创建模型配置
ModelConfig modelConfig = DashScopeModelConfig.builder()
    .apiKey(apiKey)
    .modelName("qwen-plus")
    .build();

// 创建 Agent 配置（以下两种方式二选一）

// 【方式一】使用 AgentConfig
// 适用于不需要绑定工具集的场景
AgentConfig agentConfig = AgentConfig.builder()
    .name("MyScheduledAgent")  // Agent 名称（将作为 JobHandler 名称）
    .modelConfig(modelConfig)
    .sysPrompt("You are a helpful assistant")  // Agent 的功能描述以及业务执行流程定义
    .build();

// 【方式二】使用 RuntimeAgentConfig（阶段性方案）
// 仅当需要绑定工具集时使用。RuntimeAgentConfig 是阶段性产物，主要为了现阶段允许绑定 Tools 工具集
// 后续版本会调整 toolkit 注入的定义方式
RuntimeAgentConfig agentConfig = RuntimeAgentConfig.builder()
    .name("MyScheduledAgent")  // Agent 名称（将作为 JobHandler 名称）
    .modelConfig(modelConfig)
    .sysPrompt("You are a helpful assistant")
    .toolkit(toolkit)  // 绑定工具集，如：发送通知、报告文件生成、数据清理等
    .build();

// 注册 Agent 到调度器，让其能够被定时调度执行
ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
```

> **注意**：注册后，需要在 XXL-Job 管理控制台中配置该 Agent 的调度策略（CRON 表达式、执行频率等），Agent 名称 `MyScheduledAgent` 将作为 JobHandler 名称在控制台中显示。

**步骤 4.** 在调度控制台对Agent配置定时运行，可查看其运行过程信息日志：  

｜ 创建对应Agent任务，配置对应定时执行周期  
![Agent任务配置](images/agent-task-config_zh.png)
  
｜ 查看Agent执行日志，其中会包含Agent每一次运行时与模型交互产生的事件日志反馈  
![Agent运行日志](images/agent-task-log_zh.png) 

### 特定调度器实现要求

**对于 XXL-Job 实现：**
- XXL-Job 2.4.0+ 
- 接入云上MSE XXL-Job服务，业务应用Agent需部署与同VPC

**对于未来实现：**
- 增加其他开源定时运行方案实现
- 待定

