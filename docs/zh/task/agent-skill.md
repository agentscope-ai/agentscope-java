# 智能体技能

[智能体技能（Agent skill）](https://claude.com/blog/skills) 是 Anthropic 提出的一种提升智能体在特定任务上能力的方法。

AgentScope 通过 `Toolkit` 类提供了对智能体技能的内置支持，让开发者可以注册和管理智能体技能。

## 相关 API

`Toolkit` 类中的智能体技能 API 如下：

| API | 描述 |
|-----|------|
| `registerAgentSkill(String skillDir)` | 从指定目录注册智能体技能 |
| `removeAgentSkill(String skillName)` | 根据名称移除已注册的智能体技能 |
| `removeAgentSkills(Set<String> skillNames)` | 批量移除多个已注册的智能体技能 |
| `getAgentSkillPrompt()` | 获取所有已注册智能体技能的提示词，可以附加到智能体的系统提示词中 |

本文档将演示如何注册智能体技能并在 `ReActAgent` 类中使用它们。

## 注册智能体技能

首先，我们需要准备一个智能体技能目录，该目录需要遵循 [Anthropic blog](https://claude.com/blog/skills) 中指定的要求。

> **注意：** 技能目录必须包含一个 `SKILL.md` 文件，其中包含 YAML 前置元数据和指令说明。

这里我们创建一个示例技能目录 `sample_skill`，包含以下内容：

```markdown
---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能
...
```

然后，我们可以使用 `Toolkit` 类的 `registerAgentSkill` 方法注册技能：

```java
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// 创建示例技能目录
Path skillDir = Paths.get("sample_skill");
Files.createDirectories(skillDir);

// 创建 SKILL.md 文件
String skillContent = """
---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能
...
""";
Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

// 注册智能体技能
Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill("sample_skill");
```

之后，我们可以使用 `getAgentSkillPrompt()` 方法获取所有已注册智能体技能的提示词：

```java
String agentSkillPrompt = toolkit.getAgentSkillPrompt();
System.out.println("智能体技能提示词:");
System.out.println(agentSkillPrompt);
```

生成的提示词内容如下：

```text
# Agent Skills
The agent skills are a collection of folds of instructions, scripts, and resources that you can load dynamically to improve performance on specialized tasks. Each agent skill has a `SKILL.md` file in its folder that describes how to use the skill. If you want to use a skill, you MUST read its `SKILL.md` file carefully.

## sample_skill
用于演示的示例智能体技能
Check "sample_skill/SKILL.md" for how to use this skill
```

这个提示词会被自动附加到智能体的系统提示词（`sysPrompt`）末尾，从而让智能体知道有哪些技能可用，并引导智能体去读取对应的 `SKILL.md` 文件来了解如何使用这些技能。

## 自定义提示词模板

当然，我们也可以在创建 `Toolkit` 实例时自定义提示词模板：

```java
import io.agentscope.core.tool.ToolkitConfig;

// 创建自定义配置
ToolkitConfig config = ToolkitConfig.builder()
    // 向智能体/大语言模型介绍如何使用技能的指令
    .agentSkillInstruction(
        "<system-info>为你提供了一组技能，每个技能都在一个目录中，并由 SKILL.md 文件进行描述。</system-info>\n")
    // 用于格式化每个技能提示词的模板
    // 使用 String.format 格式，参数顺序为：name, description, skillDir
    .agentSkillTemplate("- %s(位于目录 '%s'): %s\n")
    .build();

// 使用自定义配置创建 Toolkit
Toolkit customToolkit = new Toolkit(config);
customToolkit.registerAgentSkill("sample_skill");

String agentSkillPrompt = customToolkit.getAgentSkillPrompt();
System.out.println("自定义智能体技能提示词:");
System.out.println(agentSkillPrompt);
```

使用自定义模板生成的提示词如下：

```text
<system-info>为你提供了一组技能，每个技能都在一个目录中，并由 SKILL.md 文件进行描述。</system-info>
- sample_skill(位于目录 'sample_skill'): 用于演示的示例智能体技能
```

可以看到，自定义模板允许你完全控制技能提示词的格式和内容，以适应不同的使用场景和模型偏好。

## 提示词生成机制

当你注册智能体技能后，`Toolkit` 会根据配置的模板自动生成技能提示词。提示词的生成遵循以下规则：

### 默认提示词格式

默认情况下，提示词由两部分组成：

1. **技能指令部分（Instruction）**：说明什么是智能体技能以及如何使用
2. **技能列表部分（Skills）**：列出所有已注册的技能及其信息

提示词的生成过程如下：

```java
// 伪代码展示生成逻辑
String prompt = "\n" + agentSkillInstruction;
for (AgentSkill skill : registeredSkills) {
    prompt += String.format(agentSkillTemplate,
                           skill.name(),
                           skill.description(),
                           skill.skillDir());
}
```

### 模板参数说明

`agentSkillTemplate` 模板使用 `String.format` 进行格式化，按顺序接收三个参数：

| 参数位置 | 参数名称 | 说明 | 示例 |
|---------|---------|------|------|
| 第一个 `%s` | name | 技能名称 | `sample_skill` |
| 第二个 `%s` | description | 技能描述 | `用于演示的示例智能体技能` |
| 第三个 `%s` | skillDir | 技能目录路径 | `sample_skill` |

### 多个技能的情况

如果注册了多个技能，它们会按照注册顺序依次添加到提示词中：

```java
toolkit.registerAgentSkill("skill_a");
toolkit.registerAgentSkill("skill_b");
toolkit.registerAgentSkill("skill_c");

String prompt = toolkit.getAgentSkillPrompt();
// 输出包含所有三个技能的提示词
```

生成的提示词示例：

```text
# Agent Skills
The agent skills are a collection of folds of instructions, scripts, and resources that you can load dynamically to improve performance on specialized tasks. Each agent skill has a `SKILL.md` file in its folder that describes how to use the skill. If you want to use a skill, you MUST read its `SKILL.md` file carefully.

## skill_a
技能 A 的描述
Check "skill_a/SKILL.md" for how to use this skill

## skill_b
技能 B 的描述
Check "skill_b/SKILL.md" for how to use this skill

## skill_c
技能 C 的描述
Check "skill_c/SKILL.md" for how to use this skill
```

## 在 ReActAgent 中集成智能体技能

AgentScope 中的 `ReActAgent` 类会自动将智能体技能提示词附加到系统提示词中。

我们可以按如下方式创建一个带有已注册智能体技能的 ReAct 智能体：

> **重要提示：** 使用智能体技能时，智能体必须配备文本文件读取或 shell 命令工具，以便访问 `SKILL.md` 文件中的技能指令。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.dashscope.DashScopeChatModel;

// 创建模型
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-max")
    .build();

// 创建并注册智能体技能
Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill("sample_skill");

// 创建 ReActAgent
ReActAgent agent = ReActAgent.builder()
    .name("Friday")
    .sysPrompt("你是一个名为 Friday 的智能助手。")
    .model(model)
    .memory(new InMemoryMemory())
    .toolkit(toolkit)
    .maxIters(10)
    .build();

System.out.println("带有智能体技能的系统提示词:");
System.out.println(agent.getSysPrompt());
```

输出的完整系统提示词如下：

```text
你是一个名为 Friday 的智能助手。
# Agent Skills
The agent skills are a collection of folds of instructions, scripts, and resources that you can load dynamically to improve performance on specialized tasks. Each agent skill has a `SKILL.md` file in its folder that describes how to use the skill. If you want to use a skill, you MUST read its `SKILL.md` file carefully.

## sample_skill
用于演示的示例智能体技能
Check "sample_skill/SKILL.md" for how to use this skill
```

可以看到，`ReActAgent` 会自动调用 `toolkit.getAgentSkillPrompt()` 并将其附加到原始系统提示词的末尾。这样，智能体在运行时就会知道有哪些技能可用，并能根据需要读取相应的 `SKILL.md` 文件来了解使用方法。

## 完整示例

下面是一个完整的使用智能体技能的示例：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.dashscope.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AgentSkillExample {
    public static void main(String[] args) throws IOException {
        // 1. 创建示例技能目录
        Path skillDir = Paths.get("sample_skill");
        Files.createDirectories(skillDir);

        String skillContent = """
            ---
            name: sample_skill
            description: 用于演示的示例智能体技能
            ---
            
            # 示例技能
            
            这是一个示例技能，用于演示如何在 AgentScope 中使用智能体技能。
            """;
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

        // 2. 创建 Toolkit 并注册技能
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentSkill("sample_skill");

        // 3. 创建模型
        DashScopeChatModel model = DashScopeChatModel.builder()
            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
            .modelName("qwen-max")
            .build();

        // 4. 创建 ReActAgent
        ReActAgent agent = ReActAgent.builder()
            .name("Friday")
            .sysPrompt("你是一个名为 Friday 的智能助手。")
            .model(model)
            .memory(new InMemoryMemory())
            .toolkit(toolkit)
            .maxIters(10)
            .build();

        // 5. 使用智能体
        Msg userMsg = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .content(TextBlock.builder()
                .text("你好，你可以使用哪些技能？")
                .build())
            .build();

        Msg response = agent.call(userMsg).block();
        System.out.println("智能体回复: " + response);
    }
}
```

## 技能目录结构要求

一个有效的智能体技能目录必须满足以下要求：

1. **目录结构**：技能必须是一个目录
2. **SKILL.md 文件**：目录中必须包含一个 `SKILL.md` 文件
3. **YAML 前置元数据**：`SKILL.md` 文件必须以 YAML 前置元数据开始，包含以下字段：
   - `name`: 技能名称（必需）
   - `description`: 技能描述（必需）
4. **技能内容**：YAML 前置元数据之后是技能的具体说明和使用指南

示例 `SKILL.md` 结构：

```markdown
---
name: my_skill
description: 这是我的技能描述
---

# 技能名称

## 功能介绍
...

## 使用方法
...
```

## 最佳实践

1. **清晰的技能描述**：在 YAML 前置元数据中提供清晰简洁的技能描述
2. **详细的使用说明**：在 `SKILL.md` 文件中提供详细的使用指南和示例
3. **配备文件读取工具**：确保智能体配备了文件读取工具，以便能够访问 `SKILL.md` 文件
4. **技能组织**：将相关的脚本、资源文件等组织在同一技能目录下
5. **技能管理**：
   - 使用 `removeAgentSkill()` 删除单个技能
   - 需要删除多个技能时，使用 `removeAgentSkills()` 进行批量删除，提高效率

## 注意事项

1. 智能体技能是在运行时动态加载的，智能体需要主动读取 `SKILL.md` 文件来了解如何使用技能
2. 确保智能体拥有文件读取权限和相应的工具（如 `read_file` 工具）
3. 技能目录路径可以是相对路径或绝对路径
4. 移除技能前，确保没有其他组件正在使用该技能

