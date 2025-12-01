# Agent Skill

[Agent skill](https://claude.com/blog/skills) is an approach proposed by Anthropic to improve agent capabilities on specific tasks.

AgentScope provides built-in support for agent skills through the `Toolkit` class, allowing developers to register and manage agent skills.

## Related APIs

The agent skill APIs in the `Toolkit` class are as follows:

| API | Description |
|-----|-------------|
| `registerAgentSkill(String skillContent)` | Register an agent skill from content string with YAML frontmatter |
| `registerAgentSkill(AgentSkill skill)` | Register an agent skill from AgentSkill object |
| `removeAgentSkill(String skillName)` | Remove a registered agent skill by name |
| `removeAgentSkills(Set<String> skillNames)` | Remove multiple registered agent skills in batch |
| `getAgentSkillPrompt()` | Get the prompt for all registered agent skills, which can be attached to the system prompt for the agent |

This document demonstrates how to register agent skills and use them in the `ReActAgent` class.

## Creating AgentSkill Objects

AgentScope provides two ways to create `AgentSkill` objects, each suited for different scenarios.

### Constructor 1: From YAML Frontmatter Content (Recommended)

```java
public AgentSkill(String skillContent)
```

This constructor automatically parses the YAML frontmatter from the skill content to extract `name` and `description`.

**Example:**

```java
String skillContent = """
---
name: data_analysis
description: Tools for data analysis and visualization
---

# Data Analysis Skill

This skill provides methods for analyzing datasets...
""";

// Create AgentSkill - name and description are auto-extracted
Toolkit.AgentSkill skill = new Toolkit.AgentSkill(skillContent);
```

**When to use:**
- ✅ When you have complete skill content with YAML frontmatter
- ✅ When following the Anthropic skill format standard
- ✅ Most common use case

**Throws:**
- `IllegalArgumentException` if YAML frontmatter is missing
- `IllegalArgumentException` if `name` or `description` fields are missing or empty
- `IllegalArgumentException` if YAML syntax is invalid

### Constructor 2: Explicit Parameters

```java
public AgentSkill(String name, String description, String skillContent)
```

This constructor allows you to explicitly specify all three parameters.

**Example:**

```java
String name = "custom_skill";
String description = "A custom skill for special tasks";
String skillContent = """
# Custom Skill

This is the full content of the skill.
It doesn't require YAML frontmatter.
""";

// Create AgentSkill with explicit parameters
Toolkit.AgentSkill skill = new Toolkit.AgentSkill(name, description, skillContent);
```

**When to use:**
- ✅ When you want to programmatically generate skills
- ✅ When content doesn't have YAML frontmatter
- ✅ When you need more control over the metadata

**Throws:**
- `IllegalArgumentException` if `name` is null or empty
- `IllegalArgumentException` if `description` is null or empty
- `IllegalArgumentException` if `skillContent` is null or empty

### Comparison and Error Scenarios

| Feature | Constructor 1 (YAML) | Constructor 2 (Explicit) |
|---------|---------------------|--------------------------|
| **Parameters** | 1 (skillContent) | 3 (name, description, content) |
| **YAML Required** | ✅ Yes | ❌ No |
| **Auto-parse metadata** | ✅ Yes | ❌ No |
| **Error on missing YAML** | ✅ Yes | ❌ No (doesn't check) |
| **Use case** | Standard skills | Programmatic generation |

### Common Error Cases

#### Error 1: Missing YAML Frontmatter

```java
String invalidContent = """
# My Skill
Just content without frontmatter
""";

// ❌ Throws IllegalArgumentException
Toolkit.AgentSkill skill = new Toolkit.AgentSkill(invalidContent);
// Error: "The skill content must have a YAML Front Matter including `name` and `description` fields."
```

#### Error 2: Missing Required Fields

```java
String invalidContent = """
---
name: my_skill
# Missing description field!
---
# Content
""";

// ❌ Throws IllegalArgumentException
Toolkit.AgentSkill skill = new Toolkit.AgentSkill(invalidContent);
// Error: "The skill content must have a YAML Front Matter including `name` and `description` fields."
```

#### Error 3: Empty Parameters (Constructor 2)

```java
// ❌ Throws IllegalArgumentException
Toolkit.AgentSkill skill = new Toolkit.AgentSkill("", "desc", "content");
// Error: "The skill must include `name`, `description`, and `skillContent` fields."

// ❌ Also throws for null values
Toolkit.AgentSkill skill = new Toolkit.AgentSkill(null, "desc", "content");
// Error: "The skill must include `name`, `description`, and `skillContent` fields."
```

#### Error 4: Invalid YAML Syntax

```java
String invalidYaml = """
---
name: my_skill
description: "Unclosed quote
---
# Content
""";

// ❌ Throws IllegalArgumentException
Toolkit.AgentSkill skill = new Toolkit.AgentSkill(invalidYaml);
// Error: "Invalid YAML frontmatter syntax"
```

### Best Practice Recommendations

1. **Use Constructor 1 for standard skills**: Follow the Anthropic format with YAML frontmatter
2. **Use Constructor 2 for dynamic generation**: When building skills programmatically
3. **Always validate content**: Ensure YAML frontmatter is correctly formatted
4. **Handle exceptions**: Wrap skill creation in try-catch when loading from external sources

```java
try {
    String skillContent = loadFromFile("skill.md");
    Toolkit.AgentSkill skill = new Toolkit.AgentSkill(skillContent);
    toolkit.registerAgentSkill(skill);
} catch (IllegalArgumentException e) {
    logger.error("Failed to load skill: {}", e.getMessage());
    // Handle error appropriately
}
```

## Registering Agent Skills

Agent skills in AgentScope are defined using content strings with YAML frontmatter, following the requirements specified in the [Anthropic blog](https://claude.com/blog/skills).

> **Note:** The skill content must include YAML frontmatter with 'name' and 'description' fields.

Here, we create an example skill content:

```markdown
---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill

This is a sample skill to demonstrate how to use agent skills in AgentScope.
```

Then, we can register the skill using the `registerAgentSkill` method of the `Toolkit` class:

```java
import io.agentscope.core.tool.Toolkit;

// Prepare skill content with YAML frontmatter
String skillContent = """
---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill

This is a sample skill to demonstrate how to use agent skills in AgentScope.
""";

// Register agent skill
Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill(skillContent);
```

After that, we can get the prompt for all registered agent skills using the `getAgentSkillPrompt()` method:

```java
String agentSkillPrompt = toolkit.getAgentSkillPrompt();
System.out.println("Agent Skill Prompt:");
System.out.println(agentSkillPrompt);
```

The generated prompt content is as follows:

```text
# Agent Skills
The agent skills are a collection of folds of instructions, scripts, and resources that you can load dynamically to improve performance on specialized tasks. Each agent skill has a `SKILL.md` file in its folder that describes how to use the skill. If you want to use a skill, you MUST read its `SKILL.md` file carefully.

## sample_skill
A sample agent skill for demonstration
Check "---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill

This is a sample skill to demonstrate how to use agent skills in AgentScope." for how to use this skill
```

This prompt will be automatically attached to the end of the agent's system prompt (`sysPrompt`), allowing the agent to know which skills are available and includes the full skill content for the agent to reference.

## Customizing Prompt Templates

Of course, we can customize the prompt template when creating the `Toolkit` instance:

```java
import io.agentscope.core.tool.ToolkitConfig;

// Prepare skill content
String skillContent = """
---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill
...
""";

// Create custom configuration
ToolkitConfig config = ToolkitConfig.builder()
    // The instruction that introduces how to use skills to the agent/LLM
    .agentSkillInstruction(
        "<system-info>You're provided a collection of skills with embedded instructions.</system-info>\n")
    // The template for formatting each skill's prompt
    // Uses String.format format, parameter order: name, description, skillContent
    .agentSkillTemplate("- %s: %s\nContent: %s\n")
    .build();

// Create Toolkit with custom configuration
Toolkit customToolkit = new Toolkit(config);
customToolkit.registerAgentSkill(skillContent);

String agentSkillPrompt = customToolkit.getAgentSkillPrompt();
System.out.println("Customized Agent Skill Prompt:");
System.out.println(agentSkillPrompt);
```

The prompt generated using the custom template is as follows:

```text
<system-info>You're provided a collection of skills with embedded instructions.</system-info>
- sample_skill: A sample agent skill for demonstration
Content: ---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill
...
```

As you can see, custom templates allow you to fully control the format and content of skill prompts to suit different use cases and model preferences.

## Prompt Generation Mechanism

When you register agent skills, the `Toolkit` automatically generates skill prompts based on the configured templates. The prompt generation follows these rules:

### Default Prompt Format

By default, the prompt consists of two parts:

1. **Instruction Section**: Explains what agent skills are and how to use them
2. **Skills List Section**: Lists all registered skills and their information

The prompt generation process is as follows:

```java
// Pseudo-code showing generation logic
String prompt = "\n" + agentSkillInstruction;
for (AgentSkill skill : registeredSkills) {
    prompt += String.format(agentSkillTemplate,
                           skill.getName(),
                           skill.getDescription(),
                           skill.getSkillContent());
}
```

### Template Parameter Description

The `agentSkillTemplate` template uses `String.format` for formatting and receives three parameters in order:

| Parameter Position | Parameter Name | Description | Example |
|-------------------|----------------|-------------|---------|
| First `%s` | name | Skill name | `sample_skill` |
| Second `%s` | description | Skill description | `A sample agent skill for demonstration` |
| Third `%s` | skillContent | Full skill content string | `---\nname: sample_skill\n...\n` |

### Multiple Skills

If multiple skills are registered, they will be added to the prompt in registration order:

```java
toolkit.registerAgentSkill("skill_a");
toolkit.registerAgentSkill("skill_b");
toolkit.registerAgentSkill("skill_c");

String prompt = toolkit.getAgentSkillPrompt();
// Output contains all three skills
```

Example of generated prompt:

```text
# Agent Skills
The agent skills are a collection of folds of instructions, scripts, and resources that you can load dynamically to improve performance on specialized tasks. Each agent skill has a `SKILL.md` file in its folder that describes how to use the skill. If you want to use a skill, you MUST read its `SKILL.md` file carefully.

## skill_a
Description of skill A
Check "[skill content...]" for how to use this skill

## skill_b
Description of skill B
Check "[skill content...]" for how to use this skill

## skill_c
Description of skill C
Check "[skill content...]" for how to use this skill
```

## Integrating Agent Skills with ReActAgent

The `ReActAgent` class in AgentScope will automatically attach the agent skill prompt to the system prompt.

We can create a ReAct agent with registered agent skills as follows:

> **Note:** With the new API, skill content is directly embedded in the agent's system prompt, so the agent can access skill instructions without needing file reading tools.

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.dashscope.DashScopeChatModel;

// Create model
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen-max")
    .build();

// Prepare and register agent skill
String skillContent = """
---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill

This is a sample skill to demonstrate how to use agent skills in AgentScope.
""";

Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill(skillContent);

// Create ReActAgent
ReActAgent agent = ReActAgent.builder()
    .name("Friday")
    .sysPrompt("You are a helpful assistant named Friday.")
    .model(model)
    .memory(new InMemoryMemory())
    .toolkit(toolkit)
    .maxIters(10)
    .build();

System.out.println("Agent's System Prompt with Agent Skills:");
System.out.println(agent.getSysPrompt());
```

The complete system prompt output is as follows:

```text
You are a helpful assistant named Friday.
# Agent Skills
The agent skills are a collection of folds of instructions, scripts, and resources that you can load dynamically to improve performance on specialized tasks. Each agent skill has a `SKILL.md` file in its folder that describes how to use the skill. If you want to use a skill, you MUST read its `SKILL.md` file carefully.

## sample_skill
A sample agent skill for demonstration
Check "[full skill content including instructions]" for how to use this skill
```

As you can see, `ReActAgent` automatically calls `toolkit.getAgentSkillPrompt()` and appends it to the end of the original system prompt. This way, the agent will know which skills are available at runtime and can directly access their instructions from the embedded content.

## Complete Example

Below is a complete example of using agent skills:

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
        // 1. Prepare skill content
        String skillContent = """
            ---
            name: sample_skill
            description: A sample agent skill for demonstration
            ---
            
            # Sample Skill
            
            This is a sample skill to demonstrate how to use agent skills in AgentScope.
            
            ## Usage
            To use this skill, simply reference it in your task.
            """;

        // 2. Create Toolkit and register skill
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentSkill(skillContent);

        // 3. Create model
        DashScopeChatModel model = DashScopeChatModel.builder()
            .apiKey(System.getenv("DASHSCOPE_API_KEY"))
            .modelName("qwen-max")
            .build();

        // 4. Create ReActAgent
        ReActAgent agent = ReActAgent.builder()
            .name("Friday")
            .sysPrompt("You are a helpful assistant named Friday.")
            .model(model)
            .memory(new InMemoryMemory())
            .toolkit(toolkit)
            .maxIters(10)
            .build();

        // 5. Use the agent
        Msg userMsg = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .content(TextBlock.builder()
                .text("Hello, what skills can you use?")
                .build())
            .build();

        Msg response = agent.call(userMsg).block();
        System.out.println("Agent response: " + response);
    }
}
```

## Skill Content Structure Requirements

A valid agent skill content string must meet the following requirements:

1. **YAML Frontmatter**: The content must start with YAML frontmatter delimited by `---`
2. **Required Fields**: The YAML frontmatter must contain:
   - `name`: Skill name (required, non-empty string)
   - `description`: Skill description (required, non-empty string)
3. **Skill Instructions**: After the YAML frontmatter comes the detailed instructions and usage guidelines for the skill

Example skill content structure:

```markdown
---
name: my_skill
description: This is my skill description
---

# Skill Name

## Features
- Feature 1
- Feature 2

## Usage
Follow these steps to use this skill:
1. Step 1
2. Step 2
```

## Best Practices

1. **Clear Skill Descriptions**: Provide clear and concise skill descriptions in the YAML frontmatter
2. **Detailed Instructions**: Provide detailed usage guidelines and examples in the skill content
3. **Complete Content**: Include all necessary instructions directly in the skill content, as it will be embedded in the agent's system prompt
4. **Concise Yet Comprehensive**: Balance between providing sufficient detail and keeping the content concise to avoid exceeding token limits
5. **Skill Management**:
   - Use `removeAgentSkill()` to remove a single skill
   - When removing multiple skills, use `removeAgentSkills()` for batch removal to improve efficiency

## Notes

1. Agent skills are embedded directly in the agent's system prompt at runtime, so the agent has immediate access to all skill instructions
2. Be mindful of token limits when registering multiple skills with lengthy content
3. Skill content is immutable once registered; to update a skill, remove the old one and register a new version
4. Before removing a skill, ensure no other components are relying on it

