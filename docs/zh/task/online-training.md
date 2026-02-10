# AgentScope Training 训练扩展

## 概述

### 背景

Agent 开发者通常基于开源模型，通过 **SFT（监督微调）**、**RFT（强化微调）** 等微调手段，在特定场景下平衡 Agent 成本、性能与效果。该插件帮助 Agent 开发者**便捷、持续地利用在线真实交互数据优化模型与 Agent**，打通从生产环境到训练系统的**全链路数据闭环**，通过在线训练，实现**"Agent 越用越聪明"**。

### 在线训练

在线训练（Online Training）是一种直接在生产环境或接近生产环境的实时系统中，利用真实用户交互数据持续优化智能体（Agent）行为的训练范式。与传统的离线训练（Offline Training）——即先收集历史日志、构建静态数据集、再在隔离环境中训练模型——不同，在线训练强调与真实工具链和用户行为的深度耦合，实现“边运行、边学习、边优化”的闭环。

#### 核心特点

**1. 复用线上真实工具链**

Agent 在训练中可直接调用线上已部署的真实工具（如 API、数据库、业务系统等），无需为训练专门搭建模拟环境或编写 mock 工具。

**优势**：避免因 mock 工具与线上实际行为不一致导致的"训练-部署偏差"（Reality Gap）；大幅降低集成成本，提升训练数据的真实性与有效性。

**2. 支持增量学习，快速冷启动**

不依赖完整的历史数据集，Agent 可从少量甚至单次真实交互开始学习，适合新上线 Agent 或长尾场景，显著降低启动门槛。


#### 约束

**默认仅安全支持只读工具**

因训练过程可能涉及多次尝试或重放（replay），若直接调用写操作工具（如"下单""扣款""发消息"），可能导致重复执行，引发业务风险。因此，写操作需通过沙箱机制、幂等设计或人工审核等方式额外保障安全性。用户需要自行保证 Agent 使用的工具的安全性。

**多轮交互场景需显式建模**

当前主流训练框架原生支持用户与 Agent 的单轮交互（用户提问 → Agent 响应）。该轮交互中，Agent 可以和 LLM 有多次交互。

对于多轮对话或复杂任务流（如订机票 → 选座位 → 支付），需开发者额外设计状态管理、用户行为模拟或轨迹采样策略。


## 架构


该方案使用Trinity-RFT作为训练后端进行训练。Trinity-RFT 是一个通用、灵活、用户友好的大语言模型（LLM）强化微调（RFT）框架。

Github地址：https://github.com/agentscope-ai/Trinity-RFT

版本要求：v0.4.0及以上

在线训练模式将 Agent 运行 (Agent Runner)、推理服务 (Explorer) 、训练服务 (Trainer) 三个部分解耦开来:
![Online-Trining架构图](../../imgs/training.svg)
- Agent Runner 负责运行用户的 Agent 应用，处理用户请求，并通过 restful API 与 Explorer 进行交互。该部分由用户自行实现、部署和管理，Trinity-RFT 不对该部分做任何约束。
- Explorer 作为推理服务，处理来自 Agent Runner 的请求，记录可训练数据（Experience），并将数据存储在数据库中。 Explorer 提供以下 Restful 接口供 Agent Runner 调用：
  - chat: 兼容标准的 openai chat completions 接口，处理用户的对话请求。
  - feedback: 接收用户对 Agent 回答的反馈信息。
  - commit: 告知 Explorer 向 Trainer 提交数据。
- Trainer 作为训练服务，从数据库中获取新的训练数据，对模型进行训练，并将更新后的模型检查点存储在共享文件系统中，供 Explorer 使用。

Agent Runner、Explorer 和 Trainer 可以部署在不同的服务器上。其中 Agent Runner 由用户自行管理，只需要保证网络与 Explorer 互通，无需 GPU 资源。
而 Explorer 和 Trainer 需要通过 Trinity-RFT 部署在 GPU 服务器上，且需要保证两者可以访问同一个共享文件系统，以便 Trainer 保存的模型检查点可以被 Explorer 读取。

### 核心功能

本方案通过 AgentScope Java 原生支持**端到端在线训练**，旨在打通从生产环境到模型优化的全链路闭环，实现以下目标：

- **利用线上真实交互数据**：Agent 开发者可直接基于生产环境中 Agent 的真实请求调用与工具状态，使用线上的数据进行训练
- **极简使用体验**：Agent 开发者仅需提供关键训练配置（如：RL 中的奖励函数），即可自动完成执行、数据收集、训练全流程
- **统一训练接口，覆盖主流优化方法**：原生支持监督微调（**SFT**）、知识蒸馏，以及适用于特定任务的强化学习算法（如**PPO**），无需切换框架或依赖其他生态
 
---

## 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-training</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```
### 定义请求筛选逻辑

请求筛选逻辑用于筛选出需要用于训练的请求。

#### 内置策略：

**SamplingRateStrategy** - 随机采样。所有线上请求按照百分比进行筛选。
```java
TrainingSelectionStrategy strategy = SamplingRateStrategy.of(0.1);  // 10%
```

**ExplicitMarkingStrategy** - 用户显式标记重要请求
```java
TrainingSelectionStrategy strategy = ExplicitMarkingStrategy.create();

// 在你的应用代码中显示标记请求用于训练
TrainingContext.mark("high-quality", "user-feedback");
agent.call(msg).block();  // 这个请求会被用于训练
```
#### 自定义策略
您可以参考SamplingRateStrategy或者ExplicitMarkingStrategy实现TrainingSelectionStrategy接口，并在shouldSelect方法中根据您的业务需求自定义您的请求筛选逻辑。


### 定义奖励函数
您可以实现RewardCalculator接口，并在calculate方法中根据您的业务需求自定义您的奖励计算逻辑。一般而言，奖励为0-1之间的小数。
### 启动训练后端

#### 安装 Trinity

在安装之前，请确保您的系统满足以下要求，推荐使用源码安装：

- **Python**：版本 3.10 至 3.12（含）
- **CUDA**：版本 >= 12.8
- **GPU**：至少 2 块 GPU

```bash
git clone https://github.com/agentscope-ai/Trinity-RFT
cd Trinity-RFT
pip install -e ".[dev]"
pip install flash-attn==2.8.1
```
#### 配置训练配置
##### 编写explorer服务配置
```yaml
mode: serve  # set to 'serve' for online inference service
project: test  # set your project name
name: test  # set your experiment name
checkpoint_root_dir: CHECKPOINT_ROOT_DIR  # set the root directory for checkpoints, must be an absolute path, and should be on a shared filesystem
model:
  model_path:  /path/to/your/model # set the path to your base model
  max_model_len:  8192
  max_response_tokens: 2048
  temperature: 0.7
algorithm:
  algorithm_type: "ppo"  # current version only supports ppo for online training (group is not supported yet)
cluster:
    node_num: 1
    gpu_per_node: 4  # suppose you have 4 GPUs on the node
explorer:
  rollout_model:
    engine_num: 2
    tensor_parallel_size: 2  # make sure tensor_parallel_size * engine_num <= node_num * gpu_per_node
    enable_openai_api: true
    enable_history: true
    enable_auto_tool_choice: true
    tool_call_parser: hermes
    # reasoning_parser: deepseek_r1  # if using Qwen3 series models, uncomment this line
    dtype: bfloat16
    seed: 42
  service_status_check_interval: 10  # check new checkpoints and update data every 10 seconds
  proxy_port: 8010  # set the port for Explorer service
# trainer:
#   save_interval: 1  # save checkpoint every step
#   ulysses_sequence_parallel_size: 2  # set according to your model and hardware
buffer:
  train_batch_size: 16 
  trainer_input:
    experience_buffer:
      name: exp_buffer  # table name in the database
      storage_type: sql
      # path: your_db_url  # if not provided, use a sqlite database in checkpoint_root_dir/project/name/buffer
synchronizer:
  sync_method: checkpoint
  sync_interval: 1
monitor:
  monitor_type: tensorboard
```
##### 编写Trainner服务配置
```yaml
mode: train  # set to 'train' for training service
project: test  # set your project name, must be the same as in Explorer
name: test  # set your experiment name, must be the same as in Explorer
checkpoint_root_dir: CHECKPOINT_ROOT_DIR  # set the root directory for checkpoints, must be the same as in Explorer
model:
  model_path: /path/to/your/model # set the path to your base model, must be the same as in Explorer
  max_model_len:  8192  # must be the same as in Explorer
  max_response_tokens: 2048  # must be the same as in Explorer
  temperature: 0.7  # must be the same as in Explorer
algorithm:
  algorithm_type: "ppo"  # current version only supports ppo for online training (group is not supported yet)
cluster:
    node_num: 1
    gpu_per_node: 4  # suppose you have 4 GPUs on the node
buffer:
  train_batch_size: 32        # trainer consumes 16 samples per step
  trainer_input:
    experience_buffer:
      name: exp_buffer  # table name in the database, must be the same as in Explorer
      storage_type: sql
      # path: your_db_url  # if not provided, use a sqlite database in checkpoint_root_dir/project/name/buffer
trainer:
  save_interval: 16  # save checkpoint every step
  ulysses_sequence_parallel_size: 1  # set according to your model and hardware
  save_hf_checkpoint: always
  max_checkpoints_to_keep: 5
  trainer_config:
    trainer:
        balance_batch: false
        max_actor_ckpt_to_keep: 5
        max_critic_ckpt_to_keep: 5
synchronizer:
  sync_method: checkpoint
  sync_interval: 1
    
monitor:
  monitor_type: tensorboard
```

#### 启动训练后端环境
启动 Explorer 和 Trainer 服务前需要启动 ray 集群
```bash
ray start --head
```
分别启动Explorer与Trainner服务。

```bash
trinity run --config explorer.yaml
trinity run --config trainer.yaml
```
启动Explorer 服务后，会将服务地址打印在日志中，一般端口为8010
### 配置在线训练与启动Agent

#### 配置选项

```java
TrainingRunner trainingRunner = TrainingRunner.builder()
        .trinityEndpoint(TRINITY_ENDPOINT) //Trinity Explorer服务地址
        .modelName(TRAINING_MODEL_NAME)//对应Trinity配置中model_path
        .selectionStrategy(new CustomStrategy())
        .rewardCalculator(new CustomReward())
        .commitIntervalSeconds(60*5)
        .repeatTime(1)
        .build();
trainingRunner.start();
```

#### 完整示例

```java
import io.agentscope.core.training.runner.TrainingRunner;
import io.agentscope.core.training.strategy.SamplingRateStrategy;

// 1. 启动训练 runner（无需 Task ID/Run ID！）
TrainingRunner runner = TrainingRunner.builder()
        .trinityEndpoint("http://trinity-backend:8010")
        .modelName("/path/to/qwen-model")  
        .selectionStrategy(SamplingRateStrategy.of(0.1))  // 10% 采样
        .rewardCalculator(agent -> 0.0)  // 自定义奖励计算逻辑
        .commitIntervalSeconds(300)  // 每 5 分钟 commit 一次
        .build();

runner.start();

// 2. 正常使用你的 Agent - 完全无感知训练！
ReActAgent agent = ReActAgent.builder()
        .name("ProductionAgent")
        .model(gpt4Model)  // 生产模型 (GPT-4)
        .tools(tools)
        .build();

// 用户请求正常处理（使用 GPT-4），自动采样10%请求用于训练
Msg response = agent.call(Msg.userMsg("搜索 Python 教程")).block();

// 3. 训练完成后停止
runner.stop();
```

---
