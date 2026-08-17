# 在线训练（Training）

`agentscope-extensions-training` 在 AgentScope 之上接入 Trinity 训练后端：把生产流量按策略采样、收集 trace、计算奖励，再周期性提交训练，形成闭环。

## 何时使用

- 已有 Trinity（或兼容服务）作为模型训练后端。
- 想把线上流量当训练数据，做强化学习或在线微调。
- 不想改业务调用代码，希望训练流水线对 Agent 调用方"透明"。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-training</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.training.runner.TrainingRunner;
import io.agentscope.core.training.strategy.SamplingRateStrategy;

TrainingRunner runner = TrainingRunner.builder()
    .trinityEndpoint("http://localhost:8080")
    .modelName("/path/to/model")
    .selectionStrategy(SamplingRateStrategy.of(0.1))   // 10% 流量进训练
    .rewardCalculator(agent -> 0.0)                    // 自定义奖励
    .commitIntervalSeconds(300)                        // 每 5 分钟 commit 一次
    .build();

runner.start();          // 拦截 Agent，开始采样

// 业务侧照常使用 Agent，无须感知 runner
agent.call(msg).block();

runner.stop();           // 停止训练流水线
```

## 选样策略

- `SamplingRateStrategy.of(0.1)`：按比例随机采样。
- `ExplicitMarkingStrategy.create()`：完全由调用方显式标记哪些请求要进训练。
- 也可以实现 `TrainingSelectionStrategy` 自定义。

## 奖励计算

`rewardCalculator` 是一个 `Function<AgentBase, Double>`，每次采样产生 trajectory 后调用。可以是：

- Lambda：基于回答长度、工具调用次数等启发式打分。
- 自定义类：实现 `RewardCalculator` 接口，封装更复杂的指标。

```java
TrainingRunner runner = TrainingRunner.builder()
    .trinityEndpoint(endpoint)
    .modelName(model)
    .selectionStrategy(SamplingRateStrategy.of(0.1))
    .rewardCalculator(new MyMetricRewardCalculator())
    .build();
```

## 工作机制

1. `runner.start()` 之后，Agent 的请求会经 `TrainingRouter` 路由：
   - 命中采样 → 调用替换为 Trinity 后端，trace 数据被收集；
   - 未命中 → 走原有模型，无副作用。
2. 命中样本会调 reward calculator 算分，再通过 `TrinityClient.feedback(...)` 反馈。
3. `commitIntervalSeconds` 周期到达时调用 `commit(...)`，触发训练任务。

`runner.stop()` 时会优雅关闭定时器与连接池。

## 关键配置

| 字段 | 说明 |
| --- | --- |
| `trinityEndpoint` | Trinity 服务地址 |
| `modelName` | 训练目标模型路径或别名 |
| `selectionStrategy` | 采样策略 |
| `rewardCalculator` | 奖励计算函数 |
| `commitIntervalSeconds` | commit 周期，默认 300 |

## 与 Studio 配合

可以同时挂载 `StudioMessageHook`，在 Studio 上实时看到哪些会话被采样进训练；reward 也可以写入 Studio 用于可视化分析。
