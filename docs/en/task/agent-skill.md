# Agent Skills

## Overview

Agent Skills are modular skill packages that extend agent capabilities. Each Skill contains instructions, metadata, and optional resources (such as scripts, reference documentation, examples, etc.), which agents will automatically use for relevant tasks.

**Reference**: [Claude Agent Skills Official Documentation](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview)

## Core Features

### Progressive Disclosure Mechanism

Adopts **three-stage on-demand loading** to optimize context: Initially loads only metadata (~100 tokens/Skill) → AI loads complete instructions when needed (<5k tokens) → On-demand access to resource files. Tools are also progressively disclosed, activated only when the Skill is in use.

**Workflow:** User Query → AI Identifies Relevant Skill → Calls `load_skill_through_path` Tool to Load Content and Activate Bound Tools → On-Demand Resource Access → Task Completion

**Unified Loading Tool**: `load_skill_through_path(skillId, resourcePath)` provides a single entry point for loading skill resources
- `skillId` uses an enum field, ensuring selection only from registered Skills, guaranteeing accuracy
- `resourcePath` is the resource path relative to the Skill root directory (e.g., `references/api-doc.md`)
- Returns a list of all available resource paths when the path is incorrect, helping the LLM correct errors

### Adaptive Design

We have further abstracted skills so that their discovery and content loading are no longer dependent on the file system. Instead, the LLM discovers and loads skill content and resources through tools. At the same time, to maintain compatibility with the existing skill ecosystem and resources, skills are still organized according to file system structure for their content and resources.

**Organize your skill content and resources just like organizing a skill directory in a file system!**

Taking the [Skill Structure](#skill-structure) as an example, this directory-structured skill is represented in our system as:

```java
AgentSkill skill = new AgentSkill.builder()
    .name("data_analysis")
    .description("Use this skill when analyzing data, calculating statistics, or generating reports")
    .skillContent("# Data Analysis\n...")
    .addResource("references/api-doc.md", "# API Reference\n...")
    .addResource("references/best-practices.md", "# Best Practices\n...")
    .addResource("examples/example1.java", "public class Example1 {\n...\n}")
    .addResource("scripts/process.py", "def process(data): ...\n")
    .build();
```

### Skill Structure

```text
skill-name/
├── SKILL.md          # Required: Entry file with YAML frontmatter and instructions
├── references/       # Optional: Detailed reference documentation
│   ├── api-doc.md
│   └── best-practices.md
├── examples/         # Optional: Working examples
│   └── example1.java
└── scripts/          # Optional: Executable scripts
    └── process.py
```

### SKILL.md Format Specification

```yaml
---
name: skill-name                    # Required: Skill name (lowercase letters, numbers, underscores)
description: This skill should be used when...  # Required: Trigger description, explaining when to use
---

# Skill Name

## Feature Overview
[Detailed description of the skill's functionality]

## Usage Instructions
[Usage steps and best practices]

## Available Resources
- references/api-doc.md: API reference documentation
- scripts/process.py: Data processing script
```

**Required Fields:**

- `name` - Skill name (lowercase letters, numbers, underscores)
- `description` - Skill functionality and usage scenarios, helps AI determine when to use

## Quick Start

### 1. Create a Skill

#### Method 1: Using Builder

```java
AgentSkill skill = AgentSkill.builder()
    .name("data_analysis")
    .description("Use when analyzing data...")
    .skillContent("# Data Analysis\n...")
    .addResource("references/formulas.md", "# Common Formulas\n...")
    .source("custom")
    .build();
```

#### Method 2: Create from Markdown

```java
// Prepare SKILL.md content
String skillMd = """
---
name: data_analysis
description: Use this skill when analyzing data, calculating statistics, or generating reports
---
# Skill Name
Content...
""";

// Prepare resource files (optional)
Map<String, String> resources = Map.of(
    "references/formulas.md", "# Common Formulas\n...",
    "examples/sample.csv", "name,value\nA,100\nB,200"
);

// Create Skill
AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
```

#### Method 3: Direct Construction

```java
AgentSkill skill = new AgentSkill(
    "data_analysis",                    // name
    "Use when analyzing data...",       // description
    "# Data Analysis\n...",             // skillContent
    resources                            // resources (can be null)
);
```

### 2. Integrate with ReActAgent

#### Using SkillBox

```java
Toolkit toolkit = new Toolkit();

SkillBox skillBox = new SkillBox(toolkit);
skillBox.registerSkill(skill1);

ReActAgent agent = ReActAgent.builder()
        .name("DataAnalyst")
        .model(model)
        .toolkit(toolkit)
        .skillBox(skillBox)  // Automatically registers skill tools and hook
        .memory(new InMemoryMemory())
        .build();
```

### 3. Use Skills

## Simplified Integration

```java
SkillBox skillBox = new SkillBox();

skillBox.registerSkill(dataSkill);

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .skillBox(skillBox)
    .build();
```

## Advanced Features

### Feature 1: Progressive Disclosure of Tools

Bind Tools to Skills for on-demand activation. Avoids context pollution from pre-registering all Tools, only passing relevant Tools to LLM when the Skill is actively used.

**Lifecycle of Progressively Disclosed Tools**: Tool lifecycle remains consistent with Skill lifecycle. Once a Skill is activated, Tools remain available throughout the entire session, avoiding the call failures caused by Tool deactivation after each conversation round in the old mechanism.

**Example Code**:

```java
Toolkit toolkit = new Toolkit();
SkillBox skillBox = new SkillBox(toolkit);

AgentSkill dataSkill = AgentSkill.builder()
    .name("data_analysis")
    .description("Comprehensive data analysis capabilities")
    .skillContent("# Data Analysis\n...")
    .build();

AgentTool loadDataTool = new AgentTool(...);

skillBox.registration()
    .skill(dataSkill)
    .tool(loadDataTool)
    .apply();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .skillBox(skillBox)
    .build();
```

### Feature 2: Code Execution Capabilities

Provides an isolated code execution folder for Skills, supporting Shell commands, file read/write operations, etc. Uses Builder pattern for flexible configuration of required tools.

**Basic Usage**:

```java
SkillBox skillBox = new SkillBox(toolkit);

// Enable all code execution tools (Shell, read file, write file)
skillBox.codeExecution()
    .withShell()
    .withRead()
    .withWrite()
    .enable();
```

**Custom Configuration**:

```java
// Customize working directory and Shell command whitelist
ShellCommandTool customShell = new ShellCommandTool(
    null,  // baseDir will be automatically set to workDir
    Set.of("python3", "node", "npm"),
    command -> askUserApproval(command)  // Optional command approval callback
);

skillBox.codeExecution()
    .workDir("/path/to/workdir")  // Specify working directory
    .withShell(customShell)       // Use custom Shell tool
    .withRead()                   // Enable file reading
    .withWrite()                  // Enable file writing
    .enable();

// Or enable only file operations, without Shell
skillBox.codeExecution()
    .withRead()
    .withWrite()
    .enable();
```

**Core Features**:
- **Unified Working Directory**: All tools share the same `workDir`, ensuring file isolation
- **Selective Enabling**: Flexibly combine Shell, read file, and write file tools as needed
- **Flexible Configuration**: Supports custom ShellCommandTool to meet customization requirements
- **Automatic Management**: Automatically creates temporary directory when `workDir` is not specified, with automatic cleanup on program exit

### Feature 3: Skill Persistence Storage

**Why is this feature needed?**

Skills need to remain available after application restart, or be shared across different environments. Persistence storage supports:

#### File System Storage

```java
AgentSkillRepository repo = new FileSystemSkillRepository(Path.of("./skills"));
repo.save(List.of(skill), false);
AgentSkill loaded = repo.getSkill("data_analysis");
```

#### MySQL Database Storage (not yet implemented)

#### Git Repository (not yet implemented)

#### JAR Resource Adapter (Read-Only)

Used to load pre-packaged Skills from JAR resources. Automatically compatible with standard JARs and Spring Boot Fat JARs.

```java
try (JarSkillRepositoryAdapter adapter = new JarSkillRepositoryAdapter("skills")) {
    AgentSkill skill = adapter.getSkill("data-analysis");
    List<AgentSkill> allSkills = adapter.getAllSkills();
} catch //...
```

Resource structure: Place multiple skill subdirectories under `src/main/resources/skills/`, each containing a `SKILL.md`.

### Performance Optimization Recommendations

1. **Control SKILL.md Size**: Keep under 5k tokens, recommended 1.5-2k tokens
2. **Organize Resources Properly**: Place detailed documentation in references/ rather than SKILL.md
3. **Regularly Clean Versions**: Use `clearSkillOldVersions()` to clean up old versions no longer needed
4. **Avoid Duplicate Registration**: Leverage duplicate registration protection mechanism; same Skill object with multiple Tools won't create duplicate versions

## Related Documentation

- [Claude Agent Skills Official Documentation](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview) - Complete concept and architecture introduction
- [Tool Usage Guide](./tool.md) - Tool system usage methods
- [Agent Configuration](./agent.md) - Agent configuration and usage

