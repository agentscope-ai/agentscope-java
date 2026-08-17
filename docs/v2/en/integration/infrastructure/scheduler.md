# Scheduler

`agentscope-extensions-scheduler` runs Agents periodically — e.g. "every day at 8 AM run the daily-report Agent" or "every 5 seconds run a health-check Agent". The module abstracts a unified `AgentScheduler` interface and ships two implementations:

| Sub-module | Implementation | Deployment |
| --- | --- | --- |
| `agentscope-extensions-scheduler-quartz` | [Quartz](https://www.quartz-scheduler.org/) | Standalone or clustered (shared Quartz DB) |
| `agentscope-extensions-scheduler-xxl-job` | [XXL-Job](https://www.xuxueli.com/xxl-job/) | Distributed scheduling, requires admin server |

The SPI lives in `agentscope-extensions-scheduler-common` so you can plug in other schedulers.

## Shared concepts

- `AgentConfig` (or `RuntimeAgentConfig`): how to construct the Agent — name, model config, system prompt, toolkit.
- `ScheduleConfig`: scheduling policy — CRON, fixed rate, max parallelism.
- `AgentScheduler`: the core interface — `schedule(...)` / `pause(...)` / `resume(...)` / `cancel(...)` / `shutdown()`.
- `ScheduleAgentTask`: handle returned from a registration; represents one scheduled task.

Each trigger creates a fresh Agent instance for execution to avoid leaked state.

## Quartz mode

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-scheduler-quartz</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Usage

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
    .sysPrompt("You are a report assistant; please generate a sales summary every day.")
    .build();

ScheduleConfig schedule = ScheduleConfig.builder()
    .scheduleMode(ScheduleMode.FIXED_RATE)
    .fixedRate(5_000L)   // every 5s
    // or .scheduleMode(ScheduleMode.CRON).cron("0 0 8 * * ?")
    .build();

scheduler.schedule(agent, schedule);
```

Runtime control:

```java
scheduler.pause("DailyReportAgent");
scheduler.resume("DailyReportAgent");
scheduler.cancel("DailyReportAgent");
scheduler.shutdown();
```

## XXL-Job mode

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-scheduler-xxl-job</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Usage

```java
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.agentscope.extensions.scheduler.xxljob.XxlJobAgentScheduler;

// 1) Boot the XXL-Job executor
XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
executor.setAdminAddresses("http://localhost:8080/xxl-job-admin");
executor.setAppname("agentscope-demo");
executor.setAccessToken("xxxxxxxx");
executor.setPort(9999);
executor.start();

// 2) Wrap it as AgentScheduler
AgentScheduler scheduler = new XxlJobAgentScheduler(executor);

// 3) Register an Agent as a JobHandler
ScheduleAgentTask task = scheduler.schedule(agentConfig, ScheduleConfig.builder().build());
```

After registration, **configure the schedule (CRON, parallelism, routing) in the XXL-Job admin console**. The Agent name `DailyReportAgent` shows up there as the JobHandler.

## Binding tools

Use `RuntimeAgentConfig` (a transitional API that may evolve) when you need to bind a Toolkit:

```java
RuntimeAgentConfig agent = RuntimeAgentConfig.builder()
    .name("OpsAgent")
    .modelConfig(modelConfig)
    .sysPrompt("Run health checks and send alerts.")
    .toolkit(toolkit)
    .build();
```

## Choosing one

- **Local or small cluster, no external scheduler service** → Quartz
- **Need a console, cross-node routing, task logs** → XXL-Job
- **Bring your own scheduler framework** → implement the `AgentScheduler` SPI
