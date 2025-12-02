# 智能体技能

[智能体技能（Agent skill）](https://claude.com/blog/skills) 是 Anthropic 提出的一种提升智能体在特定任务上能力的方法。

AgentScope 通过 `Toolkit` 类提供了对智能体技能的内置支持，让开发者可以注册和管理智能体技能。

## 相关 API

`Toolkit` 类中的智能体技能 API 如下：

| API | 描述 |
|-----|------|
| `registerAgentSkill(String skillContent)` | 从包含 YAML 前置元数据的内容字符串注册智能体技能 |
| `registerAgentSkill(AgentSkill skill)` | 从 AgentSkill 对象注册智能体技能 |
| `removeAgentSkill(String skillName)` | 根据名称移除已注册的智能体技能 |
| `removeAgentSkills(Set<String> skillNames)` | 批量移除多个已注册的智能体技能 |
| `getAgentSkillPrompt()` | 获取所有已注册智能体技能的提示词，可以附加到智能体的系统提示词中 |

本文档将演示如何注册智能体技能并在 `ReActAgent` 类中使用它们。

## 创建 AgentSkill 对象

AgentScope 提供了两种创建 `AgentSkill` 对象的方式，每种方式适用于不同的场景。

### 构造函数 1：从 YAML 前置元数据内容创建（推荐）

```java
public AgentSkill(String skillContent)
```

此构造函数会自动从技能内容中解析 YAML 前置元数据，提取 `name` 和 `description`。

**示例：**

```java
import io.agentscope.core.tool.skill.AgentSkill;

String skillContent = """
---
name: data_analysis
description: 数据分析和可视化工具
---

# 数据分析技能

此技能提供了分析数据集的方法...
""";

// 创建 AgentSkill - name 和 description 会自动提取
AgentSkill skill = new AgentSkill(skillContent);
```

**使用场景：**
- ✅ 当你有包含 YAML 前置元数据的完整技能内容时
- ✅ 当遵循 Anthropic 技能格式标准时
- ✅ 最常见的使用场景

**抛出异常：**
- `IllegalArgumentException` 如果缺少 YAML 前置元数据
- `IllegalArgumentException` 如果缺少或为空 `name` 或 `description` 字段
- `IllegalArgumentException` 如果 YAML 语法无效

### 构造函数 2：显式参数

```java
public AgentSkill(String name, String description, String skillContent)
```

此构造函数允许你显式指定所有三个参数。

**示例：**

```java
import io.agentscope.core.tool.skill.AgentSkill;

String name = "custom_skill";
String description = "用于特殊任务的自定义技能";
String skillContent = """
# 自定义技能

这是技能的完整内容。
不需要 YAML 前置元数据。
""";

// 使用显式参数创建 AgentSkill
AgentSkill skill = new AgentSkill(name, description, skillContent);
```

**使用场景：**
- ✅ 当你想要以编程方式生成技能时
- ✅ 当内容没有 YAML 前置元数据时
- ✅ 当你需要更多控制元数据时

**抛出异常：**
- `IllegalArgumentException` 如果 `name` 为 null 或空
- `IllegalArgumentException` 如果 `description` 为 null 或空
- `IllegalArgumentException` 如果 `skillContent` 为 null 或空

### 对比和错误场景

| 特性 | 构造函数 1（YAML） | 构造函数 2（显式） |
|------|------------------|-------------------|
| **参数数量** | 1 个（skillContent） | 3 个（name, description, content） |
| **需要 YAML** | ✅ 是 | ❌ 否 |
| **自动解析元数据** | ✅ 是 | ❌ 否 |
| **缺少 YAML 时报错** | ✅ 是 | ❌ 否（不检查） |
| **使用场景** | 标准技能 | 编程生成 |

### 常见错误情况

#### 错误 1：缺少 YAML 前置元数据

```java
String invalidContent = """
# 我的技能
只有内容没有前置元数据
""";

// ❌ 抛出 IllegalArgumentException
AgentSkill skill = new AgentSkill(invalidContent);
// 错误信息: "The skill content must have a YAML Front Matter including `name` and `description` fields."
```

#### 错误 2：缺少必需字段

```java
String invalidContent = """
---
name: my_skill
# 缺少 description 字段！
---
# 内容
""";

// ❌ 抛出 IllegalArgumentException
AgentSkill skill = new AgentSkill(invalidContent);
// 错误信息: "The skill content must have a YAML Front Matter including `name` and `description` fields."
```

#### 错误 3：空参数（构造函数 2）

```java
// ❌ 抛出 IllegalArgumentException
AgentSkill skill = new AgentSkill("", "desc", "content");
// 错误信息: "The skill must include `name`, `description`, and `skillContent` fields."

// ❌ null 值也会抛出异常
AgentSkill skill = new AgentSkill(null, "desc", "content");
// 错误信息: "The skill must include `name`, `description`, and `skillContent` fields."
```

#### 错误 4：无效的 YAML 语法

```java
String invalidYaml = """
---
name: my_skill
description: "未闭合的引号
---
# 内容
""";

// ❌ 抛出 IllegalArgumentException
AgentSkill skill = new AgentSkill(invalidYaml);
// 错误信息: "Invalid YAML frontmatter syntax"
```

### 最佳实践建议

1. **标准技能使用构造函数 1**：遵循 Anthropic 格式，使用 YAML 前置元数据
2. **动态生成使用构造函数 2**：以编程方式构建技能时使用
3. **始终验证内容**：确保 YAML 前置元数据格式正确
4. **处理异常**：从外部源加载技能时，用 try-catch 包装技能创建

```java
try {
    String skillContent = loadFromFile("skill.md");
    AgentSkill skill = new AgentSkill(skillContent);
    toolkit.registerAgentSkill(skill);
} catch (IllegalArgumentException e) {
    logger.error("加载技能失败: {}", e.getMessage());
    // 适当处理错误
}
```

## 注册智能体技能

AgentScope 中的智能体技能使用包含 YAML 前置元数据的内容字符串来定义，遵循 [Anthropic blog](https://claude.com/blog/skills) 中指定的要求。

> **注意：** 技能内容必须包含 'name' 和 'description' 字段的 YAML 前置元数据。

这里我们创建一个示例技能内容：

```markdown
---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能

这是一个示例技能，用于演示如何在 AgentScope 中使用智能体技能。
```

然后，我们可以使用 `Toolkit` 类的 `registerAgentSkill` 方法注册技能：

```java
import io.agentscope.core.tool.Toolkit;

// 准备包含 YAML 前置元数据的技能内容
String skillContent = """
---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能

这是一个示例技能，用于演示如何在 AgentScope 中使用智能体技能。
""";

// 注册智能体技能
Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill(skillContent);
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
Check "---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能

这是一个示例技能，用于演示如何在 AgentScope 中使用智能体技能。" for how to use this skill
```

这个提示词会被自动附加到智能体的系统提示词（`sysPrompt`）末尾，从而让智能体知道有哪些技能可用，并包含完整的技能内容供智能体参考。

## 自定义提示词模板

当然，我们也可以在创建 `Toolkit` 实例时自定义提示词模板：

```java
import io.agentscope.core.tool.ToolkitConfig;

// 准备技能内容
String skillContent = """
---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能
...
""";

// 创建自定义配置
ToolkitConfig config = ToolkitConfig.builder()
    // 向智能体/大语言模型介绍如何使用技能的指令
    .agentSkillInstruction(
        "<system-info>为你提供了一组技能，每个技能都包含完整的使用说明。</system-info>\n")
    // 用于格式化每个技能提示词的模板
    // 使用 String.format 格式，参数顺序为：name, description, skillContent
    .agentSkillTemplate("- %s: %s\n内容: %s\n")
    .build();

// 使用自定义配置创建 Toolkit
Toolkit customToolkit = new Toolkit(config);
customToolkit.registerAgentSkill(skillContent);

String agentSkillPrompt = customToolkit.getAgentSkillPrompt();
System.out.println("自定义智能体技能提示词:");
System.out.println(agentSkillPrompt);
```

使用自定义模板生成的提示词如下：

```text
<system-info>为你提供了一组技能，每个技能都包含完整的使用说明。</system-info>
- sample_skill: 用于演示的示例智能体技能
内容: ---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能
...
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
                           skill.getName(),
                           skill.getDescription(),
                           skill.getSkillContent());
}
```

### 模板参数说明

`agentSkillTemplate` 模板使用 `String.format` 进行格式化，按顺序接收三个参数：

| 参数位置 | 参数名称 | 说明 | 示例 |
|---------|---------|------|------|
| 第一个 `%s` | name | 技能名称 | `sample_skill` |
| 第二个 `%s` | description | 技能描述 | `用于演示的示例智能体技能` |
| 第三个 `%s` | skillContent | 完整的技能内容字符串 | `---\nname: sample_skill\n...\n` |

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
Check "[技能内容...]" for how to use this skill

## skill_b
技能 B 的描述
Check "[技能内容...]" for how to use this skill

## skill_c
技能 C 的描述
Check "[技能内容...]" for how to use this skill
```

## 在 ReActAgent 中集成智能体技能

AgentScope 中的 `ReActAgent` 类会自动将智能体技能提示词附加到系统提示词中。

我们可以按如下方式创建一个带有已注册智能体技能的 ReAct 智能体：

> **注意：** 使用新的 API，技能内容直接嵌入到智能体的系统提示词中，因此智能体无需文件读取工具即可访问技能说明。

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

// 准备并注册智能体技能
String skillContent = """
---
name: sample_skill
description: 用于演示的示例智能体技能
---

# 示例技能

这是一个示例技能，用于演示如何在 AgentScope 中使用智能体技能。
""";

Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill(skillContent);

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
Check "[包含完整使用说明的技能内容]" for how to use this skill
```

可以看到，`ReActAgent` 会自动调用 `toolkit.getAgentSkillPrompt()` 并将其附加到原始系统提示词的末尾。这样，智能体在运行时就会知道有哪些技能可用，并能直接从嵌入的内容中访问它们的使用说明。

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

public class AgentSkillExample {
    public static void main(String[] args) {
        // 1. 准备技能内容
        String skillContent = """
            ---
            name: sample_skill
            description: 用于演示的示例智能体技能
            ---
            
            # 示例技能
            
            这是一个示例技能，用于演示如何在 AgentScope 中使用智能体技能。
            
            ## 使用方法
            在你的任务中直接引用此技能即可。
            """;

        // 2. 创建 Toolkit 并注册技能
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentSkill(skillContent);

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

## 技能内容结构要求

一个有效的智能体技能内容字符串必须满足以下要求：

1. **YAML 前置元数据**：内容必须以 `---` 分隔的 YAML 前置元数据开始
2. **必需字段**：YAML 前置元数据必须包含：
   - `name`: 技能名称（必需，非空字符串）
   - `description`: 技能描述（必需，非空字符串）
3. **技能说明**：YAML 前置元数据之后是技能的具体说明和使用指南

示例技能内容结构：

```markdown
---
name: my_skill
description: 这是我的技能描述
---

# 技能名称

## 功能介绍
- 功能 1
- 功能 2

## 使用方法
按照以下步骤使用此技能：
1. 步骤 1
2. 步骤 2
```

## 最佳实践

1. **清晰的技能描述**：在 YAML 前置元数据中提供清晰简洁的技能描述
2. **详细的使用说明**：在技能内容中提供详细的使用指南和示例
3. **完整的内容**：在技能内容中直接包含所有必要的说明，因为它将被嵌入到智能体的系统提示词中
4. **简洁而全面**：在提供足够详细信息和保持内容简洁之间取得平衡，以避免超出 token 限制
5. **技能管理**：
   - 使用 `removeAgentSkill()` 删除单个技能
   - 需要删除多个技能时，使用 `removeAgentSkills()` 进行批量删除，提高效率

## 注意事项

1. 智能体技能在运行时直接嵌入到智能体的系统提示词中，因此智能体可以立即访问所有技能说明
2. 注册多个包含大量内容的技能时要注意 token 限制
3. 技能内容一旦注册就无法修改；要更新技能，需要先移除旧版本然后注册新版本
4. 移除技能前，确保没有其他组件依赖该技能

