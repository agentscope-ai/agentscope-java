# Durable Spring Boot 示例

[English](README.en.md)

`agentscope-durable-examples` 按 Brain 与 Hands 的部署方式组织示例。`integrated` 和 `queued`
都是 Maven 分类聚合模块；每个叶子场景拥有独立的依赖、Spring Boot 配置、可执行 JAR 和测试。

```text
examples/
├── integrated/                 # 不通过队列拆分 Brain 与 Hands
│   ├── embedded/               # 最小进程内集成
│   ├── http-server/            # HTTP API 与 SSE 宿主
│   └── sandbox-hands/          # 同 JVM Brain + Sandbox Hands，跨重启恢复
└── queued/                     # 队列解耦，Brain 与 Hands 独立进程
    ├── queued-hands/           # RocketMQ + echo Hands
    └── sandbox-hands/          # RocketMQ + Sandbox Hands
```

分类依据是 **Brain/Hands 是否通过队列解耦**，而不是是否使用 HTTP 或是否演示重启。
`integrated/sandbox-hands` 的 `start` 和 `resume` 虽然是两个先后运行的 JVM，每个 JVM 内的
Brain 与 Hands 仍是一体装配。`http-server` 是 API-only 宿主，不启动 Brain，也没有队列 Worker。

## 一体式：integrated

| 场景 | 演示内容 | artifactId | 配置前缀 |
| --- | --- | --- | --- |
| [`embedded`](integrated/embedded/README.md) | 进程内 Brain；同一请求跨重启只调用模型一次 | `agentscope-durable-example-embedded` | `durable.embedded` |
| [`http-server`](integrated/http-server/README.md) | Spring Boot HTTP API、取消、历史查询与 SSE | `agentscope-durable-example-http-server` | `durable.http-server` |
| [`sandbox-hands`](integrated/sandbox-hands/README.md) | 同 JVM Brain → Sandbox Hands → Brain，恢复原执行并安全清理 | `agentscope-durable-example-sandbox-hands` | `durable.sandbox-hands` |

```sh
bash agentscope-durable/durable-examples/integrated/embedded/run.sh
bash agentscope-durable/durable-examples/integrated/http-server/run.sh
bash agentscope-durable/durable-examples/integrated/sandbox-hands/run.sh
```

## 队列解耦：queued

| 场景 | 演示内容 | artifactId | 配置前缀 |
| --- | --- | --- | --- |
| [`queued-hands`](queued/queued-hands/README.md) | RocketMQ 5 分离 Brain 与 echo Worker | `agentscope-durable-example-queued-hands` | `durable.queued-hands` |
| [`sandbox-hands`](queued/sandbox-hands/README.md) | RocketMQ 5 分离 Brain 与 Sandbox Worker；Worker 持久化资源绑定与执行账本 | `agentscope-durable-example-queued-sandbox-hands` | `durable.queued-sandbox-hands` |

每个队列示例的 `brain` 和 `worker` 使用同一 JAR 的不同角色，但部署为独立进程，使用各自的
数据目录，只通过消息交换执行请求和结果。Sandbox 示例默认使用独立的 MQ namespace，避免
与 echo 示例消费同一组 Topic。具体 Broker 准备与双进程启动步骤见各场景说明。

没有 Broker 时可以只验证打包：

```sh
bash agentscope-durable/durable-examples/queued/queued-hands/run.sh --compile-only
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh --compile-only
```

## 构建与运行

从仓库根目录执行，以 `embedded` 为例：

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-embedded -am package

java -jar agentscope-durable/durable-examples/integrated/embedded/target/agentscope-durable-example-embedded.jar \
  --durable.embedded.data-directory=./durable-embedded-data
```

每个分类目录的 `pom.xml` 聚合其子模块。批量构建时，在 `-pl` 中用逗号分隔所需场景的
artifactId，并保留 `-am` 构建依赖。Spring Boot 命令行参数、环境变量和外部
`application.yml` 均可覆盖默认配置。

## 离线验证

以下命令运行所有示例测试，避免要求上游模块的完整集成验收环境：

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-embedded,:agentscope-durable-example-http-server,:agentscope-durable-example-sandbox-hands,:agentscope-durable-example-queued-hands,:agentscope-durable-example-queued-sandbox-hands \
  -am -Djacoco.skip=true \
  -Dtest=EmbeddedExampleTest,HttpServerApplicationTest,SandboxHandsExampleTest,QueuedHandsExampleTest,QueuedSandboxHandsExampleTest,QueuedSandboxHandsRoundTripTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP 测试启动真实随机端口 Tomcat，检查跨客户端提交、查询、取消及 SSE。队列 Sandbox 测试
使用本地命令与内存传输验证 Durable 队列协议、结果回灌和幂等性；不替代真实 RocketMQ SDK、
Broker 或多机故障验收。所有示例均无需模型 API Key。

这些模块只展示宿主集成，不加入框架 BOM，也不应成为业务应用的运行时依赖。
