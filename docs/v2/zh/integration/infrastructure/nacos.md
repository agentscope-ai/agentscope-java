---
title: Nacos
en_link: /v2/en/integration/infrastructure/nacos
---

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
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;
import java.util.List;
import java.util.Properties;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");
props.setProperty("username", System.getenv("NACOS_USERNAME"));
props.setProperty("password", System.getenv("NACOS_PASSWORD"));
AiService aiService = AiFactory.createAiService(props);
NacosA2aRegistry registry = new NacosA2aRegistry(aiService);

AgentCard agentCard = AgentCard.builder()
    .name("translator")
    .description("Translate text")
    .version("1.2.3")
    .supportedInterfaces(List.of(new AgentInterface(
        "JSONRPC", "http://127.0.0.1:8081/agent", null, "1.0")))
    .capabilities(AgentCapabilities.builder().build())
    .defaultInputModes(List.of("text"))
    .defaultOutputModes(List.of("text"))
    .skills(List.of())
    .build();

NacosA2aRegistryProperties props2 = NacosA2aRegistryProperties.builder()
    .setAsLatest(true)
    .build();
registry.registerAgent(agentCard, props2);
```

上例使用 A2A 1.0 的 `AgentCard`。`version("1.2.3")` 是 Agent 的业务版本；`AgentInterface` 的最后一个参数 `"1.0"` 是接口协议版本，按 A2A 规范使用主版本和次版本。注册更高的业务版本不代表支持更高的协议版本。

接口元数据随新版本发布生效；已经发布的版本不会被同版本注册覆盖，如需修正旧记录，请发布新的业务版本。

上例设置了 `setAsLatest(true)`。未启用发布审核时，注册会自动发布该版本，发布完成后消费者即可发现。

<Note>
如果设置 `setAsLatest(false)`，后续新版本可能保留为草稿，需要在 Nacos 控制台完成发布；首次创建的版本仍会自动提交。如果启用了发布审核，即使设置为 `true`，也需要在控制台完成相应的审核和发布步骤。
</Note>

<Note>
端点更新兼容性：在 Nacos 3.3 中，同一客户端重新注册同一 Agent 版本时，会替换该版本的完整端点集合。更新地址时，请向 `registry.registerAgent(...)` 提交所有需要保留的端点配置。不要在注册新地址后再注销旧地址，否则会撤销该版本的整组注册；其他客户端的注册不受影响。
</Note>

<Note>
A2A 1.0 字段兼容性：使用多租户时，将 `AgentInterface` 的第三个参数设为 tenant；注册会保留每个接口的 tenant 和协议版本。配置运行时地址时，按传输类型匹配 Card 接口；存在多个候选时优先匹配 URL，仍有不同 tenant 或协议版本的歧义则拒绝注册。A2A 技能级 `securityRequirements` 尚不能完整保存，直接导入含鉴权要求的标准 A2A 1.0 JSON 也尚不兼容。上例通过 Java 对象注册；注册成功并不表示所有 A2A 1.0 字段均受支持。
</Note>

### 客户端：通过 Nacos 拿到远端 AgentCard

```java
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;

NacosAgentCardResolver resolver = new NacosAgentCardResolver(aiService);
A2aAgent remote = A2aAgent.builder()
    .name("translator")
    .agentCardResolver(resolver)
    .build();
```

默认根据 Nacos 服务端能力自动选择发现方式。仍可用 JVM 参数 `-Dagentscope.nacos.a2a.discovery.mode=a2a` 或 `rad` 显式指定；`auto` 表示自动选择。参数在创建 Resolver 时读取，实际选择在首次查询时完成，之后不会自动切换。

<Note>
升级提示：支持 RAD 的服务端现在默认使用原生发现。Agent 或所选版本、标签被删除后，`getAgentCard(...)` 返回 `null`，重建后自动恢复；显式选择 `a2a` 可保留旧模式下缓存最后一个 AgentCard 的行为。首次 RAD 查询在订阅完成后最多等待 10 秒接收初始状态，超时会报错，后续查询继续使用同一订阅。能力尚未确定或查询失败时会报错，不会自动降级；可在连接就绪后重试。发现结果无法转换为 A2A Card 时也会报告转换错误。
</Note>

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

// 生产环境可以选择固定版本，或通过标签切换版本
String prodTpl = prompts.getPrompt(
    "system-prompt", null, "prod", Map.of("userName", "Alice"),
    "You are a helpful assistant for {{userName}}."
);
```

上例按 `prod` 标签读取模板；需要固定版本时，将第二个参数设为版本号（如 `"1.0.0"`），第三个参数设为 `null`。调用参数会覆盖模板中的变量默认值。当模板不存在、为空，或读取失败时，使用最后一个参数提供的默认模板。

在 Nacos 更新模板或切换标签后，变更会自动同步，无需重启应用。应在需要生成提示词时调用 `getPrompt(...)`；已经返回的字符串不会自动变化。

<Note>
升级提示：Prompt 的 `version` 和 `label` 现在会校验互斥。如果现有调用同时传入两者，请改为只指定一个，否则会抛出 `IllegalArgumentException`。
</Note>

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

在 Nacos 发布 Skill 新版本后，需要重新调用 `getSkill(...)` 并将返回的 Skill 加载到 Agent；已经加载的 Skill 不会自动替换。若指定了固定版本，还需先将版本配置改为目标版本并重新创建仓库。

## 与其他扩展配合

- 结合 [A2A](/v2/zh/integration/protocol/a2a)：`AgentScopeA2aServer.builder().agentRegistry(...)` 可以注入一个把 AgentCard 推到 Nacos 的注册器，启动后自动暴露给整个集群。
- 结合 [Skill 仓库](/v2/zh/integration/skill/index)：可以与 Git/MySQL 的 `AgentSkillRepository` 并存，把同一个 Toolkit 用多个数据源拼起来。
