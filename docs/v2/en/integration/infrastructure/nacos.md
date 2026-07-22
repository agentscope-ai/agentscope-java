# Nacos

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
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistry;
import io.agentscope.core.nacos.a2a.registry.NacosA2aRegistryProperties;

Properties props = new Properties();
props.setProperty("serverAddr", "127.0.0.1:8848");
NacosA2aRegistry registry = new NacosA2aRegistry(props);

NacosA2aRegistryProperties props2 = new NacosA2aRegistryProperties();
// props2.setNamespace(...) / setGroup(...) / etc.
registry.registerAgent(agentCard, props2);
```

After registration, the AgentCard and the service endpoint are written into the Nacos AI Service for consumers to discover.

### Client side: resolve a remote AgentCard via Nacos

```java
import io.agentscope.core.nacos.a2a.discovery.NacosAgentCardResolver;

NacosAgentCardResolver resolver = new NacosAgentCardResolver(props, "translator-agent");
A2aAgent remote = A2aAgent.builder()
    .name("translator")
    .agentCardResolver(resolver)
    .build();
```

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
```

The listener maintains a local cache; when prompts change in Nacos, updates are pushed in. The next `getPrompt(...)` call returns the new version with no restart.

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

## Pairs well with

- [A2A](../protocol/a2a.md): inject a Nacos-backed `AgentRegistry` into `AgentScopeA2aServer.builder().agentRegistry(...)` to publish AgentCards cluster-wide on startup.
- [Skill repositories](../skill/): coexist with Git/MySQL `AgentSkillRepository` to assemble a Toolkit from multiple sources.
