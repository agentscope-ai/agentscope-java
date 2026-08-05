# DashScope 模型

`agentscope-extensions-model-dashscope` 接入阿里云 DashScope Qwen Model，包括多模态和推理能力的 Qwen 模型。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-dashscope</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `DASHSCOPE_API_KEY` 后，可以使用 `dashscope:<model>`，也可以使用 Qwen 短名：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("dashscope:qwen-plus") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("qwen-plus") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要 DashScope 专属配置时使用 builder，例如 endpoint type、thinking、search、encryption：

```java
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;

DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-plus")
    .stream(true)
    .build();
```

## Spring Boot

Spring Boot 应用可以使用 DashScope starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-dashscope-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](../../docs/building-blocks/model.md)。
