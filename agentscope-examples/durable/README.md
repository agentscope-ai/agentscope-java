# Durable Spring Boot 示例

[English](README.en.md)

`io.agentscope:durable` 示例聚合模块按 Brain 与 Hands 的部署方式组织示例。`integrated` 和
`brain-hands` 是 Maven 分类聚合模块；每个叶子场景有独立的依赖、配置和测试。

这些模块归属 `agentscope-examples` Maven 聚合模块。Durable 的 `durable-standalone`
profile 也显式纳入本目录，使启动脚本及 `make -C agentscope-durable check` 继续连同库依赖
一起构建示例；库模块不依赖示例模块。

```text
agentscope-examples/durable/
├── integrated/                 # 不通过队列拆分 Brain 与 Hands
│   ├── quickstart/             # 最小进程内集成
│   ├── http-server/            # HTTP API 与 SSE 宿主
│   └── tool-in-sandbox/        # 同 JVM Brain + Sandbox 工具，跨重启恢复
└── brain-hands/                # 队列解耦，Brain 与 Hands 独立进程
    ├── echo-tool/              # RocketMQ + echo 工具
    └── tool-in-sandbox/        # RocketMQ + Sandbox 工具
```

分类依据是 Brain/Hands 是否通过队列解耦，而不是是否使用 HTTP 或是否演示重启。
`integrated/tool-in-sandbox` 的 `start` 和 `resume` 是先后运行的两个 JVM，每个 JVM 内仍是一体装配。
`http-server` 是 API-only 宿主，不启动 Brain，也没有队列 Worker。

## 一体式：integrated

| 场景 | 演示内容 | artifactId | 配置前缀 |
| --- | --- | --- | --- |
| [`quickstart`](integrated/quickstart/README.md) | 进程内 Brain；同一请求跨重启只调用模型一次 | `agentscope-durable-example-quickstart` | `durable.quickstart` |
| [`http-server`](integrated/http-server/README.md) | Spring Boot HTTP API、取消、历史查询与 SSE | `agentscope-durable-example-http-server` | `durable.http-server` |
| [`tool-in-sandbox`](integrated/tool-in-sandbox/README.md) | 同 JVM Brain → Sandbox 工具 → Brain，恢复原执行并安全清理 | `agentscope-durable-example-tool-in-sandbox` | `durable.tool-in-sandbox` |

```sh
bash agentscope-examples/durable/integrated/quickstart/run.sh
bash agentscope-examples/durable/integrated/http-server/run.sh
bash agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
```

## 队列解耦：brain-hands

| 场景 | 演示内容 | artifactId | 配置前缀 |
| --- | --- | --- | --- |
| [`echo-tool`](brain-hands/echo-tool/README.md) | RocketMQ 5 分离 Brain 与 echo Hands | `agentscope-durable-example-echo-tool` | `durable.echo-tool` |
| [`tool-in-sandbox`](brain-hands/tool-in-sandbox/README.md) | RocketMQ 5 分离 Brain 与 Sandbox Hands；Hands 持久化资源绑定与执行账本 | `agentscope-durable-example-brain-hands-tool-in-sandbox` | `durable.brain-hands-tool-in-sandbox` |

每个场景保留一个 Maven 模块，但 Brain 与 Hands 有各自的 Java 启动类和示例文件，分别打包为
`<artifactId>-brain.jar` 和 `<artifactId>-hands.jar`。不再通过 `role` 参数切换职责，也不在一个
`run()` 中混合两边的资源装配。Brain 只配置 Agent、Runtime 和远程执行器；Hands 只配置 Worker
和本地工具。两边使用不同数据目录，只通过队列交换执行请求和结果。

Sandbox 场景默认使用独立的 MQ namespace，避免与 echo 场景消费同一组 Topic。准备好 Broker 后，
在两个终端分别运行：

```sh
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh hands localhost:8081
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh brain localhost:8081
```

脚本参数为 `<brain|hands> <endpoint> [data-directory]`，可选第三个参数指定该进程的数据目录。
`run.sh` 选择对应 JAR，不向 Java 传递角色配置。也可以直接用 `java -jar` 启动各自的 JAR；
具体入口、Broker 准备和 Sandbox 操作见场景说明。没有 Broker 时只验证打包：

```sh
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh --compile-only
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh --compile-only
```

## 构建与运行

从仓库根目录执行，以 `quickstart` 为例：

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-quickstart -am package

java -jar agentscope-examples/durable/integrated/quickstart/target/agentscope-durable-example-quickstart.jar \
  --durable.quickstart.data-directory=./durable-quickstart-data
```

每个分类目录的 `pom.xml` 聚合其子模块。在 `-pl` 中用逗号分隔所需场景的 artifactId，并保留
`-am` 构建依赖。Spring Boot 命令行参数、环境变量和外部 `application.yml` 均可覆盖默认配置。

## 离线验证

以下命令仅选择示例包下的测试，不要求上游模块的完整集成验收环境：

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-quickstart,:agentscope-durable-example-http-server,:agentscope-durable-example-tool-in-sandbox,:agentscope-durable-example-echo-tool,:agentscope-durable-example-brain-hands-tool-in-sandbox \
  -am -Djacoco.skip=true \
  '-Dtest=%regex[io/agentscope/durable/examples/.*Test.class]' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

HTTP 测试启动真实随机端口 Tomcat，检查跨客户端提交、查询、取消及 SSE。队列测试使用本地工具和
内存传输验证结果回灌、恢复和幂等性，并检查两种 Spring 入口的装配隔离；不替代真实 RocketMQ SDK、
Broker 或多机故障验收。所有示例均无需模型 API Key。

这些模块只展示宿主集成，不加入框架 BOM，也不应成为业务应用的运行时依赖。
