# 智能体技能包 (Agent Skill)

## 概述

Agent Skill 是扩展智能体能力的模块化技能包。每个 Skill 包含指令、元数据和可选资源(如脚本、参考文档、示例等),智能体在相关任务时会自动使用这些资源。

**参考资料**: [Claude Agent Skills 官方文档](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview)

## 核心特性

### 渐进式披露机制

采用**三阶段按需加载**优化上下文: 初始化时仅加载元数据(~100 tokens/Skill) → AI 判断需要时加载完整指令(<5k tokens) → 按需访问资源文件。Tool 同样渐进式披露,仅在 Skill 激活时生效。

**工作流程:** 用户提问 → AI 识别相关 Skill → 调用工具加载内容并激活绑定的 Tool → 按需访问资源 → 完成任务

### 适应性设计

我们将 Skill 进行了进一步的抽象,使其的发现和内容加载不再依赖于文件系统,而是 LLM 通过 Tool 来发现和加载 Skill 的内容和资源。同时为了兼容已有的 Skill 生态与资源,Skill 的组织形式依旧按照文件系统的结构来组织它的内容和资源。

**像在文件系统里组织 Skill 目录一样组织 Skill 的内容和资源吧!**

以 [Skill 结构](#skill-结构) 为例,这种目录结构的 Skill 在我们的系统中的表现形式就是:

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

## Skill 结构

```text
skill-name/
├── SKILL.md          # 必需: 入口文件,包含 YAML frontmatter 和指令
├── references/       # 可选: 详细参考文档
│   ├── api-doc.md
│   └── best-practices.md
├── examples/         # 可选: 工作示例
│   └── example1.java
└── scripts/          # 可选: 可执行脚本
    └── process.py
```

## SKILL.md 格式规范

```yaml
---
name: skill-name                    # 必需: 技能名称(小写字母、数字、下划线)
description: This skill should be used when...  # 必需: 触发描述,说明何时使用
---

# 技能名称

## 功能概述
[详细说明该技能的功能]

## 使用方法
[使用步骤和最佳实践]

## 可用资源
- references/api-doc.md: API 参考文档
- scripts/process.py: 数据处理脚本
```

**必需字段:**

- `name` - 技能的名字（小写字母、数字、下划线）
- `description` - 技能功能和使用场景描述，帮助 AI 判断何时使用

## 快速开始

### 1. 创建 Skill

#### 方式一: 使用 Builder

```java
AgentSkill skill = AgentSkill.builder()
    .name("data_analysis")
    .description("Use when analyzing data...")
    .skillContent("# Data Analysis\n...")
    .addResource("references/formulas.md", "# 常用公式\n...")
    .source("custom")
    .build();
```

#### 方式二: 从 Markdown 创建

```java
String skillMd = """
---
name: data_analysis
description: Use this skill when analyzing data, calculating statistics, or generating reports
---
# 技能名称
Content...
""";

Map<String, String> resources = Map.of(
    "references/formulas.md", "# 常用公式\n...",
    "examples/sample.csv", "name,value\nA,100\nB,200"
);

AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
```

#### 方式三: 直接构造

```java
AgentSkill skill = new AgentSkill(
    "data_analysis",                    // name
    "Use when analyzing data...",       // description
    "# Data Analysis\n...",             // skillContent
    resources                            // resources (可为 null)
);
```

### 2. 集成到 ReActAgent

#### 使用 SkillBox

```java
Toolkit toolkit = new Toolkit();

SkillBox skillBox = new SkillBox(toolkit);
skillBox.registerAgentSkill(skill1);

ReActAgent agent = ReActAgent.builder()
        .name("DataAnalyst")
        .model(model)
        .toolkit(toolkit)
        .skillBox(skillBox)  // 自动注册 skill 工具和 hook
        .memory(new InMemoryMemory())
        .build();
```

### 3. 使用 Skill

## 简化的集成方式

```java
SkillBox skillBox = new SkillBox();

skillBox.registerAgentSkill(dataSkill);

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .skillBox(skillBox)
    .build();
```

## 高级功能

### 功能 1: Tool 的渐进式披露

将 Tool 与 Skill 绑定,实现按需激活。避免预先注册所有 Tool 导致的上下文污染,仅在 Skill 被 LLM 使用时才传递相关 Tool。

**示例代码**:

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

### 功能 2: Skill 持久化存储

**为什么需要这个功能?**

Skills 需要在应用重启后保持可用,或者在不同环境间共享。持久化存储支持:

- 文件系统存储
- 数据库存储 (暂未实现)
- Git 仓库 (暂未实现)

**示例代码**:

```java
AgentSkillRepository repo = new FileSystemSkillRepository(Path.of("./skills"));
repo.save(List.of(skill), false);
AgentSkill loaded = repo.getSkill("data_analysis");
```

这种保护适用于所有仓库操作: `getSkill()`、`save()`、`delete()` 和 `skillExists()`。

详细安全指南请参阅 [Claude Agent Skills 安全考虑](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview#安全考虑)。

### 性能优化建议

1. **控制 SKILL.md 大小**: 保持在 5k tokens 以内,建议 1.5-2k tokens
2. **合理组织资源**: 将详细文档放在 `references/` 中,而非 SKILL.md
3. **定期清理版本**: 使用 `clearSkillOldVersions()` 清理不再需要的旧版本
4. **避免重复注册**: 利用重复注册保护机制,相同 Skill 对象配多个 Tool 时不会创建重复版本

## 相关文档

- [Claude Agent Skills 官方文档](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview) - 完整的概念和架构介绍
- [Tool 使用指南](./tool.md) - 工具系统的使用方法
- [Agent 配置](./agent.md) - 智能体配置和使用
