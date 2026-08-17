# Scheduler（定时调度）

`agentscope-extensions-scheduler` 让 Agent 可以按调度器配置周期性执行——比如"每天 8 点跑一次日报 Agent"、"每 5 秒做一次健康巡检"。模块抽出统一的 `AgentScheduler` 接口，提供两个实现：

| 子模块 | 实现 | 部署形态 |
| --- | --- | --- |
| `agentscope-extensions-scheduler-quartz` | [Quartz](https://www.quartz-scheduler.org/) | 单机或集群（共享 Quartz 数据库） |
| `agentscope-extensions-scheduler-xxl-job` | [XXL-Job](https://www.xuxueli.com/xxl-job/) | 分布式调度，依赖 admin server |

底层 SPI 在 `agentscope-extensions-scheduler-common`，可以自己实现接入其他调度框架。

## 共享概念

- `AgentConfig`（或 `RuntimeAgentConfig`）：定义 Agent 怎么"造"出来——名字、模型配置、system prompt、工具集等。
- `ScheduleConfig`：定义调度策略——CRON、固定速率、最大并发等。
- `AgentScheduler`：核心接口，`schedule(...)` / `pause(...)` / `resume(...)` / `cancel(...)` / `shutdown()`。
- `ScheduleAgentTask`：一次注册返回的"被调度的 Agent 任务"句柄。

每次任务触发时，调度器会"现造"一个新的 Agent 实例并执行，避免状态串台。

## Quartz 模式（单机/集群）

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-scheduler-quartz</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 用法

```java
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler;

AgentScheduler scheduler = QuartzAgentScheduler.builder()
    .autoStart(true)
    .build();

AgentConfig agent = AgentConfig.builder()
    .name("DailyReportAgent")
    .modelConfig(DashScopeModelConfig.builder()
        .apiKey(apiKey).modelName("qwen-plus").build())
    .sysPrompt("你是日报助手，请每天生成销售汇总")
    .build();

ScheduleConfig schedule = ScheduleConfig.builder()
    .scheduleMode(ScheduleMode.FIXED_RATE)
    .fixedRate(5_000L)   // 每 5 秒
    // 或 .scheduleMode(ScheduleMode.CRON).cron("0 0 8 * * ?")
    .build();

scheduler.schedule(agent, schedule);
```

支持运行时管控：

```java
scheduler.pause("DailyReportAgent");
scheduler.resume("DailyReportAgent");
scheduler.cancel("DailyReportAgent");
scheduler.shutdown();
```

## XXL-Job 模式（分布式）

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-scheduler-xxl-job</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 用法

```java
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.agentscope.extensions.scheduler.xxljob.XxlJobAgentScheduler;

// 1) 启动 XXL-Job Executor
XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
executor.setAdminAddresses("http://localhost:8080/xxl-job-admin");
executor.setAppname("agentscope-demo");
executor.setAccessToken("xxxxxxxx");
executor.setPort(9999);
executor.start();

// 2) 把它包成 AgentScheduler
AgentScheduler scheduler = new XxlJobAgentScheduler(executor);

// 3) 注册一个 Agent 作为 JobHandler
ScheduleAgentTask task = scheduler.schedule(agentConfig, ScheduleConfig.builder().build());
```

之后，**调度策略（CRON、并发、路由）在 XXL-Job 控制台配置**，Agent 名 `DailyReportAgent` 会作为 JobHandler 显示。

## 工具绑定

绑定 Toolkit 时使用 `RuntimeAgentConfig`（过渡 API，未来可能调整）：

```java
RuntimeAgentConfig agent = RuntimeAgentConfig.builder()
    .name("OpsAgent")
    .modelConfig(modelConfig)
    .sysPrompt("巡检并发送告警")
    .toolkit(toolkit)
    .build();
```

## 选型建议

- **本地或小集群、不想引入外部调度服务** → Quartz
- **需要可视化控制台、跨节点路由、任务日志** → XXL-Job
- **想接其他调度框架** → 自己实现 `AgentScheduler` 接口
