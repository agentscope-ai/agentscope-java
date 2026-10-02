# Online Training

`agentscope-extensions-training` plugs a Trinity-style training backend into AgentScope: it samples production traffic, collects traces, computes rewards, and periodically commits training jobs — closing the loop.

## When to use

- You run Trinity (or a compatible service) as the training store.
- You want to use live traffic for reinforcement learning or online fine-tuning.
- You want the training pipeline to be transparent to the Agent's callers.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-training</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.training.runner.TrainingRunner;
import io.agentscope.core.training.strategy.SamplingRateStrategy;

TrainingRunner runner = TrainingRunner.builder()
    .trinityEndpoint("http://localhost:8080")
    .modelName("/path/to/model")
    .selectionStrategy(SamplingRateStrategy.of(0.1))   // 10% sampling
    .rewardCalculator(agent -> 0.0)                    // custom reward
    .commitIntervalSeconds(300)                        // commit every 5 minutes
    .build();

runner.start();          // intercept Agent calls and start sampling

// Business code keeps using the Agent unmodified
agent.call(msg).block();

runner.stop();           // stop the training pipeline
```

## Selection strategies

- `SamplingRateStrategy.of(0.1)`: random sampling at the given rate.
- `ExplicitMarkingStrategy.create()`: only marked requests are sampled.
- Or implement `TrainingSelectionStrategy` for custom behavior.

## Reward calculation

`rewardCalculator` is a `Function<AgentBase, Double>`, invoked once per sampled trajectory:

- A lambda — heuristics like answer length, tool-call count, etc.
- A custom class implementing `RewardCalculator` for richer metrics.

```java
TrainingRunner runner = TrainingRunner.builder()
    .trinityEndpoint(endpoint)
    .modelName(model)
    .selectionStrategy(SamplingRateStrategy.of(0.1))
    .rewardCalculator(new MyMetricRewardCalculator())
    .build();
```

## How it works

1. After `runner.start()`, requests go through `TrainingRouter`:
   - sampled → routed to the Trinity store, traces collected;
   - not sampled → original model is used, no side effects.
2. Sampled trajectories invoke the reward calculator and feedback through `TrinityClient.feedback(...)`.
3. Every `commitIntervalSeconds`, `commit(...)` triggers a training job.

`runner.stop()` shuts down timers and connection pools cleanly.

## Key configuration

| Field | Notes |
| --- | --- |
| `trinityEndpoint` | Trinity service URL |
| `modelName` | Target model path or alias |
| `selectionStrategy` | Sampling strategy |
| `rewardCalculator` | Reward function |
| `commitIntervalSeconds` | Commit interval, default 300 |

## Pairs well with Studio

Attach `StudioMessageHook` simultaneously and you can see in Studio which sessions get sampled and how rewards were computed.
