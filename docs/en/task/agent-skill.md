# Agent Skills

## Overview

**Agent Skills** are modular capabilities designed to extend agent functionality, providing domain-specific expertise. Each Skill encapsulates specialized knowledge, instructions, and resources, enabling agents to handle complex tasks in specific domains.

### Progressive Disclosure Mechanism

Skills implement a **three-stage progressive disclosure** mechanism to optimize context window usage:

**Three Stages:**

1. **Metadata Stage** - Agent initialization loads `name` and `description` (~100 tokens/Skill)
2. **Instructions Stage** - When AI determines the Skill is needed, loads complete SKILL.md content (<5k tokens)
3. **Resources Stage** - AI on-demand access to references/, scripts/, and other resource files (calculated based on actual usage)

**Important**: Skills also implement progressive disclosure for Tools. Only when a skill is actively used will its associated Tools be activated and passed to the LLM.

This mechanism ensures that only relevant content occupies the context window at any given time.

### Progressive Disclosure Workflow

**Complete Flow:**

1. **Agent Initialization**
   - Scan and register all Skills
   - Extract name and description
   - Inject into System Prompt
   - Dynamically register Skill loading tools

2. **User Query**
   - User: "Help me analyze this data"

3. **AI Decision Making**
   - Identifies need for data_analysis skill
   - Calls `loadSkillContent("data_analysis_custom")`
   - System returns complete SKILL.md content
   - Activates skill-bound tools

4. **On-Demand Resource Loading**
   - AI determines which resources are needed based on SKILL.md
   - Calls `loadSkillResource(..., "references/formulas.md")`
   - Calls related Tools: loadData, calculateStats, generateChart

5. **Task Completion** - AI returns results

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

### 2. Register a Skill

```java
SkillBox skillBox = new SkillBox(new Toolkit());

// Basic registration
skillBox.registerAgentSkill(skill);

// Skill metadata is automatically injected into System Prompt
String systemPrompt = skillBox.getSkillPrompt();
```

### 3. Register Skill Discovery Tools

```java
skillBox.registerSkillLoadTools(toolkit);
```

This method registers three tools that enable the LLM to discover and load skill content and resources:

- `skill_md_load_tool`: Load complete SKILL.md content
- `skill_resources_load_tool`: Load specified resource files
- `get_all_resources_path_tool`: Get all resource file paths

### 4. Use Skills

After registration, the AI will see the Skill metadata in the System Prompt and automatically use it when needed.

**Progressive disclosure flow:** User Query → AI Identifies Relevant Skill → AI Calls Tool to Load Complete Content → AI Executes Task Based on Instructions

**In other words, you don't need to do anything. The system will automatically discover and register skills, inject their metadata into the System Prompt, and use them automatically when needed.**

## Advanced Features

### Feature 3: Skill Version Management

**Why is this feature needed?**

Skills evolve with changing requirements:

- Bug fixes
- Adding new features
- Optimizing instructions
- Updating resource documentation

Version management allows preserving historical versions and quickly rolling back when new versions have issues.

**What does this feature solve?**

1. **Safe Updates**: Preserve old versions, rollback if new version has issues
2. **Version Tracking**: Record the evolution history of Skills
3. **A/B Testing**: Maintain multiple versions simultaneously for comparison testing
4. **Duplicate Registration Protection**: Same Skill object won't create duplicate versions

**Important Mechanism**: The system uses object references to determine if it's the same Skill. If the same Skill object is registered multiple times (even with different Tools), it won't create new versions. Only when a new Skill object is registered will a new version be created.

**Example Code**:

```java
SkillBox skillBox = new SkillBox(new Toolkit());

// 1. Create and register initial version
AgentSkill v1 = AgentSkill.builder()
    .name("data_processor")
    .description("Process data files")
    .skillContent("# Version 1.0\nBasic data processing functionality")
    .build();

toolkit.registerAgentSkill(v1);

// 2. Repeatedly register same object + different Tools (won't create new version)
AgentTool tool1 = new AgentTool(...);
AgentTool tool2 = new AgentTool(...);

toolkit.registration().skill(v1).tool(tool1).done();  // Won't create new version
toolkit.registration().skill(v1).tool(tool2).done();  // Won't create new version

// 3. Create new skill object based on original and register as new version
AgentSkill v2 = v1.toBuilder()
    .skillContent("# Version 2.0\nEnhanced features: Support more formats")
    .addResource("references/new-formats.md", "# New Format Support\n...")
    .build();

toolkit.registerAgentSkill(v2);

// 4. Register v2 object again + new Tool (won't create new version)
AgentTool tool3 = new AgentTool(...);
toolkit.registration().skill(v2).tool(tool3).done();  // Won't create new version

// View version list
versions = toolkit.listSkillVersions("data_processor_custom");
System.out.println(versions); // Output: [skill-v1-versionId, skill-v2-versionId]

// 5. If v2 has issues, rollback to v1
toolkit.rollbackSkillVersion("data_processor_custom", skill-v1-versionId); // Latest version reverts to v1

// 6. Get specific version
AgentSkill retrieved = toolkit.getSkillVersion("data_processor_custom", skill-v2-versionId);

// 7. Get latest version
String latest = toolkit.getLatestSkillVersionId("data_processor_custom");
System.out.println("Latest version: " + latest);

// 8. Clean up old versions (keep only latest version)
skillBox.clearSkillOldVersions("data_processor_custom");
```

### Feature 1: Progressive Disclosure of Tools

**Why is this feature needed?**

In real applications, it's common for skills to instruct the LLM to call tools to complete tasks. In the past, we needed to pre-register all these tools, but this caused a problem: the more skills we enable, the more tools we need to register in advance, polluting the tool-related context.

Therefore, having skill-associated tools also progressively disclosed becomes a meaningful requirement. We provide the ability to bind tools with skills during registration. These bound tools are only passed to the LLM when the skill is confirmed to be used by the LLM.

**Example Code**:

```java
SkillBox skillBox = new SkillBox(new Toolkit());

// Create Skill
AgentSkill dataSkill = AgentSkill.builder()
    .name("data_analysis")
    .description("Comprehensive data analysis capabilities")
    .skillContent("# Data Analysis\n...")
    .build();

// Create multiple related Tools
AgentTool loadDataTool = new AgentTool(...);
AgentTool calculateTool = new AgentTool(...);
AgentTool visualizeTool = new AgentTool(...);

skillBox.registration()
    .skill(dataSkill)
    .tool(loadDataTool)
    .apply();
skillBox.registration()
    .skill(dataSkill)  // Same skill object reference, won't register new version
    .tool(calculateTool)
    .apply();

skillBox.registration()
    .skill(dataSkill)  // Same skill object reference, won't register new version
    .tool(visualizeTool)
    .apply();
```

**Duplicate Registration Protection Mechanism**:

```java
// System checks Skill object reference
AgentSkill skill = new AgentSkill("my_skill", "desc", "content", null);

// First registration: Creates version
skillBox.registration().skill(skill).tool(tool1).apply();

// Second registration of same object: Doesn't create new version, only adds tool2
skillBox.registration().skill(skill).tool(tool2).apply();

// Third registration of same object: Doesn't create new version, only adds tool3
skillBox.registration().skill(skill).tool(tool3).apply();

// But if it's a new Skill object (even with same content), creates new version
AgentSkill newSkill = new AgentSkill("my_skill", "desc", "content", null);
skillBox.registration().skill(newSkill).tool(tool4).apply();  // Creates new version
```

### Feature 4: Skill Persistence Storage

**Why is this feature needed?**

Skills need to remain available after application restart, or be shared across different environments. Persistence storage supports:

- File system storage
- Database storage (not yet implemented)
- Git repository (not yet implemented)

**What does this feature solve?**

1. **Persistence**: Skills won't be lost due to application restart
2. **Sharing**: Team members can share Skills
3. **Version Control**: Combined with Git, can track Skill change history
4. **Extensibility**: Supports custom storage backends

**Example Code**:

```java
// 1. Use file system storage
Path skillsDir = Path.of("./my-skills");
AgentSkillRepository repository = new FileSystemSkillRepository(skillsDir);

// 2. Save Skill
AgentSkill skill = AgentSkill.builder()
    .name("my_skill")
    .description("My custom skill")
    .skillContent("# Content")
    .addResource("ref.md", "Reference doc")
    .build();

repository.save(List.of(skill), false);  // force=false: Don't overwrite existing

// 3. Load Skill from storage
AgentSkill loaded = repository.getSkill("my_skill");

// 4. List all Skills
List<String> allSkillNames = repository.getAllSkillNames();
List<AgentSkill> allSkills = repository.getAllSkills();

// 5. Delete Skill
repository.delete("my_skill");

// 6. Check if Skill exists
boolean exists = repository.skillExists("my_skill");

// 7. Get Repository information
AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
System.out.println("Repository: " + info.getRepositoryType());
System.out.println("Location: " + info.getLocation());
```

**Custom Storage Implementation**:

```java

// Implement custom storage backend (e.g., database)

public class DatabaseSkillRepository implements AgentSkillRepository {

    private final DataSource dataSource;

    public DatabaseSkillRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public AgentSkill getSkill(String name) {
        // Load from database
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT * FROM skills WHERE name = ?";
            // ... Execute query and build AgentSkill
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        // Save to database
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO skills (name, description, content, resources, source) VALUES (?, ?, ?, ?, ?)";
            // ... Execute insert
        }
    }

    // Implement other required methods...
}

        // Use custom storage
        AgentSkillRepository dbRepo = new DatabaseSkillRepository(dataSource);
dbRepo.

        save(List.of(skill), false);
```

## Complete Example

For a complete data analysis Skill system example, please refer to `AgentSkillExample.java` in the `agentscope-examples/quickstart` module.

## Important Notes

### Security Considerations

**Important**: Only use Skills from trusted sources. Malicious Skills may contain harmful instructions or scripts.

Key security recommendations:

- ✅ Review all Skill content, including SKILL.md, scripts/, and resources/
- ✅ Check if scripts perform unexpected operations (network calls, file access, etc.)
- ✅ Use sandbox environments to test Skills from unknown sources
- ❌ Avoid using Skills that dynamically fetch content from external URLs

**Path Traversal Protection**:

The `FileSystemSkillRepository` includes built-in security measures to prevent path traversal attacks:

- ✅ Automatic validation of all skill names to prevent directory traversal (e.g., `../`, `../../`)
- ✅ Blocks absolute path access (e.g., `/etc/passwd`, `C:\Windows\System32`)
- ✅ Path normalization to eliminate `.` and `..` segments
- ✅ Ensures all operations stay within the configured base directory

```java
// Safe: Valid skill name
repository.getSkill("my_skill");  // ✅ Allowed

// Blocked: Path traversal attempts
repository.getSkill("../outside");  // ❌ Throws IllegalArgumentException
repository.getSkill("/etc/passwd");  // ❌ Throws IllegalArgumentException
repository.getSkill("valid/../outside");  // ❌ Throws IllegalArgumentException
```

This protection applies to all repository operations: `getSkill()`, `save()`, `delete()`, and `skillExists()`.

For detailed security guidelines, please refer to [Claude Agent Skills Security Considerations](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview#安全考虑).

### Performance Optimization Recommendations

1. **Control SKILL.md Size**: Keep under 5k tokens, recommended 1.5-2k tokens
2. **Organize Resources Properly**: Place detailed documentation in references/ rather than SKILL.md
3. **Regularly Clean Versions**: Use `clearSkillOldVersions()` to clean up old versions no longer needed
4. **Avoid Duplicate Registration**: Leverage duplicate registration protection mechanism; same Skill object with multiple Tools won't create duplicate versions

### FAQ

**Q: What is the format of a Skill's `skillId`?**

A: The `skillId` format is `{name}_{source}`, for example `data_analysis_custom`. Note the use of underscore `_` rather than hyphen.

**Q: How do I make the AI automatically use a Skill?**

A: After registering a Skill, its metadata is automatically injected into the System Prompt. The AI will determine when to use the Skill based on the `description` field. Ensure the `description` clearly describes the usage scenario. You also need to call `skillBox.registerSkillLoadTools(toolkit)` to register the loading tools.

**Q: Why doesn't registering the same Skill object multiple times create new versions?**

A: This is the designed duplicate registration protection mechanism. It allows a Skill to be associated with multiple Tools without creating a new version each time a Tool is registered. The system uses object references to determine if it's the same Skill. Only when a new Skill object is registered will a new version be created.

**Q: Is there a size limit for Skill resource files?**

A: Recommended single resource file < 10k tokens. Due to on-demand loading, there's no hard limit on total resource size, but avoid excessively large individual files.

**Q: What is the relationship between Tools and Skills?**

A: Tools are specific executable functions, while Skills are collections of domain knowledge and instructions. A Skill typically requires multiple Tools to implement its complete functionality. Through linked registration, they can be managed as a unified functional unit.

## Related Documentation

- [Claude Agent Skills Official Documentation](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview) - Complete concept and architecture introduction
- [Tool Usage Guide](./tool.md) - Tool system usage methods
- [Agent Configuration](./agent.md) - Agent configuration and usage

