---
title: Nacos
zh_link: /v2/zh/integration/infrastructure/nacos
---

`agentscope-extensions-nacos` uses [Nacos](https://nacos.io/) as AgentScope's unified control plane: register and discover A2A Agents, hot-load prompts, and host skills. It contains three sub-modules — pick the ones you need.

| Sub-module | Problem it solves |
| --- | --- |
| `agentscope-extensions-nacos-a2a` | A2A AgentCard / instance registry and discovery |
| `agentscope-extensions-nacos-prompt` | Manage prompt templates in Nacos with hot updates |
| `agentscope-extensions-nacos-skill` | Load skill packages (ZIP) from the Nacos AI module |

## A2A registry & discovery

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-a2a</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Server side: register the AgentCard with Nacos

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

The example uses an A2A 1.0 `AgentCard`. `version("1.2.3")` identifies the Agent's business version; the final `AgentInterface` argument, `"1.0"`, identifies the interface protocol using the major and minor version required by A2A. Registering a higher business version does not establish support for a higher protocol version.

Interface metadata takes effect when publishing a new version. Registering an already published version does not overwrite it; publish a new business version to correct an existing record.

The example sets `setAsLatest(true)`. When publication review is disabled, registration automatically publishes the version, and consumers can discover it once publication completes.

<Note>
With `setAsLatest(false)`, subsequent new versions may remain drafts and need to be published in the Nacos console; the first version is still submitted automatically. If publication review is enabled, complete the required review and publication steps in the console even when this option is `true`.
</Note>

<Note>
Endpoint update compatibility: with Nacos 3.3, registering the same Agent version again replaces that client's complete endpoint set for the version. Pass every endpoint you want to retain to `registry.registerAgent(...)`. Do not deregister the old address after registering its replacement: that would remove the version's entire registration. Registrations from other clients are unaffected.
</Note>

<Note>
A2A 1.0 field compatibility: for multi-tenancy, set the third `AgentInterface` argument to the tenant. Registration preserves each interface's tenant and protocol version. Runtime addresses match Card interfaces by transport, preferring a matching URL when there are multiple candidates. Registration rejects remaining ambiguity in tenant or protocol version. A2A skill-level `securityRequirements` cannot yet be fully preserved, and importing standard A2A 1.0 JSON with security requirements is not yet compatible. The example registers a Java object; successful registration does not mean that every A2A 1.0 field is supported.
</Note>

### Client side: resolve a remote AgentCard via Nacos

```java
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;

NacosAgentCardResolver resolver = new NacosAgentCardResolver(aiService);
A2aAgent remote = A2aAgent.builder()
    .name("translator")
    .agentCardResolver(resolver)
    .build();
```

Discovery automatically selects the API supported by the Nacos server. You can still set `-Dagentscope.nacos.a2a.discovery.mode=a2a` or `rad` explicitly; `auto` selects automatically. The option is read at construction, and the API is selected on the first lookup and retained for the resolver's lifetime.

<Note>
Upgrade note: RAD-capable servers now use native discovery by default. If the agent or selected version or label is deleted, `getAgentCard(...)` returns `null` and recovers automatically after recreation. Explicit `a2a` mode preserves the previous behavior of retaining the last AgentCard. The first RAD lookup waits up to 10 seconds after subscription returns for initial state; a timeout is reported, and later lookups reuse the same subscription. Unknown capability or query failures are reported without automatic fallback; retry after the connection is ready. Discovery results that cannot be represented as A2A Cards also produce conversion errors.
</Note>

## Prompt config center

### Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-nacos-prompt</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Usage

```java
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.prompt.NacosPromptListener;

NacosPromptListener prompts = new NacosPromptListener(aiService);

String tpl = prompts.getPrompt("system-prompt", Map.of(
    "userName", "Alice"
));

// Pin a version in production, or use a label to switch versions
String prodTpl = prompts.getPrompt(
    "system-prompt", null, "prod", Map.of("userName", "Alice"),
    "You are a helpful assistant for {{userName}}."
);
```

This example selects the `prod` label. To pin a version, set the second argument to a version such as `"1.0.0"` and the third to `null`. Call arguments override the template's variable defaults. The last argument provides a fallback template when the prompt is missing, empty, or cannot be retrieved.

Template updates and label changes in Nacos synchronize automatically without restarting the application. Call `getPrompt(...)` whenever you need to generate a prompt; strings already returned do not change automatically.

<Note>
Upgrade note: Prompt `version` and `label` are now validated as mutually exclusive. If an existing call supplies both, change it to specify only one; otherwise it throws `IllegalArgumentException`.
</Note>

## Skill repository

`agentscope-extensions-nacos-skill` provides an `AgentSkillRepository` implementation that downloads and parses skill ZIP packages managed by the Nacos AI module.

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
// or SKILL_LABEL_PATH = "stable"

NacosSkillRepository repo = new NacosSkillRepository(aiService, "default-namespace", props);
AgentSkill skill = repo.getSkill("calculator");
```

Version/label resolution order: `Properties` provided to the constructor → JVM `-D` system properties → environment variables. When both version and label resolve, **version wins** and the label is not used for download.

After publishing a new Skill version in Nacos, call `getSkill(...)` again and load the returned Skill into the Agent. Skills already loaded are not replaced automatically. If you pinned a version, first update the version setting and recreate the repository.

## Pairs well with

- [A2A](/v2/en/integration/protocol/a2a): inject a Nacos-backed `AgentRegistry` into `AgentScopeA2aServer.builder().agentRegistry(...)` to publish AgentCards cluster-wide on startup.
- [Skill repositories](/v2/en/integration/skill/index): coexist with Git/MySQL `AgentSkillRepository` to assemble a Toolkit from multiple sources.
