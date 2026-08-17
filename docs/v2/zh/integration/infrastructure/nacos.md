# Nacos

`agentscope-extensions-nacos` 把 [Nacos](https://nacos.io/) 用作 AgentScope 的统一控制面：注册发现 A2A Agent、动态加载 Prompt、托管 Skill。包含三个子模块，按需组合使用。

| 子模块 | 解决的问题 |
| --- | --- |
| `agentscope-extensions-nacos-a2a` | A2A AgentCard 与服务实例的注册/发现 |
| `agentscope-extensions-nacos-prompt` | 把 Prompt 模板放到 Nacos，热更新到运行中的 Agent |
| `agentscope-extensions-nacos-skill` | 从 Nacos AI 模块加载 Skill 包（ZIP） |

## A2A 注册发现

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 服务端：把 AgentCard 注册到 Nacos

```java
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");
NacosA2aRegistry registry = new NacosA2aRegistry(props);

NacosA2aRegistryProperties props2 = new NacosA2aRegistryProperties();
// props2.setNamespace(...) / setGroup(...) 等
registry.registerAgent(agentCard, props2);
```

注册后，AgentCard 与服务端点会写入 Nacos 的 AI Service，供消费者发现。

### 客户端：通过 Nacos 拿到远端 AgentCard

```java
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;

NacosAgentCardResolver resolver = new NacosAgentCardResolver(props, "translator-agent");
A2aAgent remote = A2aAgent.builder()
    .name("translator")
    .agentCardResolver(resolver)
    .build();
```

## Prompt 配置中心

### 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-prompt</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 用法

```java
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.prompt.NacosPromptListener;

NacosPromptListener prompts = new NacosPromptListener(aiService);

String tpl = prompts.getPrompt("system-prompt", Map.of(
    "userName", "Alice"
));
```

监听器内部维护本地缓存，Nacos 上 prompt 更新时会自动推送进来，下一次 `getPrompt(...)` 立即拿到新版本，无需重启。

## Skill 仓库

`agentscope-extensions-nacos-skill` 提供一个 `AgentSkillRepository` 实现，把 Nacos AI 模块管理的技能 ZIP 包下载下来解析。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-skill</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
import io.agentscope.core.nacos.skill.NacosSkillRepository;

Properties props = new Properties();
props.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "1.2.0");
// 或 SKILL_LABEL_PATH = "stable"

NacosSkillRepository repo = new NacosSkillRepository(aiService, "default-namespace", props);
AgentSkill skill = repo.getSkill("calculator");
```

版本/标签的解析顺序是：构造时传入的 `Properties` → JVM `-D` 系统属性 → 环境变量。同时设置版本和标签时，**版本优先**，标签不会用于下载。

## 与其他扩展配合

- 结合 [A2A](../protocol/a2a.md)：`AgentScopeA2aServer.builder().agentRegistry(...)` 可以注入一个把 AgentCard 推到 Nacos 的注册器，启动后自动暴露给整个集群。
- 结合 [Skill 仓库](../skill/)：可以与 Git/MySQL 的 `AgentSkillRepository` 并存，把同一个 Toolkit 用多个数据源拼起来。
