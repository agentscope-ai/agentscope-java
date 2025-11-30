# Agent Skill

[Agent skill](https://claude.com/blog/skills) is an approach proposed by Anthropic to improve agent capabilities on specific tasks.

AgentScope provides built-in support for agent skills through the `Toolkit` class, allowing developers to register and manage agent skills.

## Related APIs

The agent skill APIs in the `Toolkit` class are as follows:

| API | Description |
|-----|-------------|
| `registerAgentSkill(String skillDir)` | Register agent skills from a given directory |
| `removeAgentSkill(String skillName)` | Remove a registered agent skill by name |
| `removeAgentSkills(Set<String> skillNames)` | Remove multiple registered agent skills in batch |
| `getAgentSkillPrompt()` | Get the prompt for all registered agent skills, which can be attached to the system prompt for the agent |

This document demonstrates how to register agent skills and use them in the `ReActAgent` class.

## Registering Agent Skills

First, we need to prepare an agent skill directory, which follows the requirements specified in the [Anthropic blog](https://claude.com/blog/skills).

> **Note:** The skill directory must contain a `SKILL.md` file containing YAML frontmatter and instructions.

Here, we create an example skill directory `sample_skill` with the following content:

```markdown
---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill
...
```

Then, we can register the skill using the `registerAgentSkill` method of the `Toolkit` class:

```java
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Create example skill directory
Path skillDir = Paths.get("sample_skill");
Files.createDirectories(skillDir);

// Create SKILL.md file
String skillContent = """
---
name: sample_skill
description: A sample agent skill for demonstration
---

# Sample Skill
...
""";
Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

// Register agent skill
Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill("sample_skill");
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
Check "sample_skill/SKILL.md" for how to use this skill
```

This prompt will be automatically attached to the end of the agent's system prompt (`sysPrompt`), allowing the agent to know which skills are available and guiding it to read the corresponding `SKILL.md` files to learn how to use them.

## Customizing Prompt Templates

Of course, we can customize the prompt template when creating the `Toolkit` instance:

```java
import io.agentscope.core.tool.ToolkitConfig;

// Create custom configuration
ToolkitConfig config = ToolkitConfig.builder()
    // The instruction that introduces how to use skills to the agent/LLM
    .agentSkillInstruction(
        "<system-info>You're provided a collection of skills, each in a directory and described by a SKILL.md file.</system-info>\n")
    // The template for formatting each skill's prompt
    // Uses String.format format, parameter order: name, description, skillDir
    .agentSkillTemplate("- %s(in directory '%s'): %s\n")
    .build();

// Create Toolkit with custom configuration
Toolkit customToolkit = new Toolkit(config);
customToolkit.registerAgentSkill("sample_skill");

String agentSkillPrompt = customToolkit.getAgentSkillPrompt();
System.out.println("Customized Agent Skill Prompt:");
System.out.println(agentSkillPrompt);
```

The prompt generated using the custom template is as follows:

```text
<system-info>You're provided a collection of skills, each in a directory and described by a SKILL.md file.</system-info>
- sample_skill(in directory 'sample_skill'): A sample agent skill for demonstration
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
                           skill.name(),
                           skill.description(),
                           skill.skillDir());
}
```

### Template Parameter Description

The `agentSkillTemplate` template uses `String.format` for formatting and receives three parameters in order:

| Parameter Position | Parameter Name | Description | Example |
|-------------------|----------------|-------------|---------|
| First `%s` | name | Skill name | `sample_skill` |
| Second `%s` | description | Skill description | `A sample agent skill for demonstration` |
| Third `%s` | skillDir | Skill directory path | `sample_skill` |

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
Check "skill_a/SKILL.md" for how to use this skill

## skill_b
Description of skill B
Check "skill_b/SKILL.md" for how to use this skill

## skill_c
Description of skill C
Check "skill_c/SKILL.md" for how to use this skill
```

## Integrating Agent Skills with ReActAgent

The `ReActAgent` class in AgentScope will automatically attach the agent skill prompt to the system prompt.

We can create a ReAct agent with registered agent skills as follows:

> **Important:** When using agent skills, the agent must be equipped with text file reading or shell command tools to access the skill instructions in `SKILL.md` files.

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

// Create and register agent skill
Toolkit toolkit = new Toolkit();
toolkit.registerAgentSkill("sample_skill");

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
Check "sample_skill/SKILL.md" for how to use this skill
```

As you can see, `ReActAgent` automatically calls `toolkit.getAgentSkillPrompt()` and appends it to the end of the original system prompt. This way, the agent will know which skills are available at runtime and can read the corresponding `SKILL.md` files as needed to learn how to use them.

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AgentSkillExample {
    public static void main(String[] args) throws IOException {
        // 1. Create example skill directory
        Path skillDir = Paths.get("sample_skill");
        Files.createDirectories(skillDir);

        String skillContent = """
            ---
            name: sample_skill
            description: A sample agent skill for demonstration
            ---
            
            # Sample Skill
            
            This is a sample skill to demonstrate how to use agent skills in AgentScope.
            """;
        Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

        // 2. Create Toolkit and register skill
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentSkill("sample_skill");

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

## Skill Directory Structure Requirements

A valid agent skill directory must meet the following requirements:

1. **Directory Structure**: The skill must be a directory
2. **SKILL.md File**: The directory must contain a `SKILL.md` file
3. **YAML Frontmatter**: The `SKILL.md` file must start with YAML frontmatter containing the following fields:
   - `name`: Skill name (required)
   - `description`: Skill description (required)
4. **Skill Content**: After the YAML frontmatter comes the detailed instructions and usage guidelines for the skill

Example `SKILL.md` structure:

```markdown
---
name: my_skill
description: This is my skill description
---

# Skill Name

## Features
...

## Usage
...
```

## Best Practices

1. **Clear Skill Descriptions**: Provide clear and concise skill descriptions in the YAML frontmatter
2. **Detailed Instructions**: Provide detailed usage guidelines and examples in the `SKILL.md` file
3. **Equip File Reading Tools**: Ensure the agent is equipped with file reading tools to access `SKILL.md` files
4. **Skill Organization**: Organize related scripts, resource files, etc. within the same skill directory
5. **Skill Management**:
   - Use `removeAgentSkill()` to remove a single skill
   - When removing multiple skills, use `removeAgentSkills()` for batch removal to improve efficiency

## Notes

1. Agent skills are loaded dynamically at runtime, and the agent needs to proactively read the `SKILL.md` file to learn how to use the skills
2. Ensure the agent has file reading permissions and appropriate tools (such as `read_file` tool)
3. Skill directory paths can be relative or absolute paths
4. Before removing a skill, ensure no other components are using it

