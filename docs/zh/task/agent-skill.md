# 智能体技能包 (Agent Skill)

## 概述

Agent Skill 是扩展智能体能力的模块化技能包。每个 Skill 包含指令、元数据和可选资源(如脚本、参考文档、示例等),智能体在相关任务时会自动使用这些资源。

**参考资料**: [Claude Agent Skills 官方文档](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview)

## 核心特性

### 渐进式披露机制

采用**三阶段按需加载**优化上下文: 初始化时仅加载元数据(~100 tokens/Skill) → AI 判断需要时加载完整指令(<5k tokens) → 按需访问资源文件。Tool 同样渐进式披露,仅在 Skill 激活时生效。

**工作流程:** 用户提问 → AI 识别相关 Skill → 调用 `load_skill_through_path` 工具加载内容并激活绑定的 Tool → 按需访问资源 → 完成任务

**统一加载工具**: `load_skill_through_path(skillId, resourcePath)` 提供单一入口加载技能资源
- `skillId` 使用枚举字段, 确保只能从已注册的 Skill 中选择, 保证准确性
- `resourcePath` 是相对于 Skill 根目录的资源路径(如 `references/api-doc.md`)
- 路径错误时会返回所有可用的资源路径列表,帮助 LLM 纠正

### 适应性设计

我们将 Skill 进行了进一步的抽象,使其的发现和内容加载不再依赖于文件系统,而是 LLM 通过 Tool 来发现和加载 Skill 的内容和资源。同时为了兼容已有的 Skill 生态与资源,Skill 的组织形式依旧按照文件系统的结构来组织它的内容和资源。

**像在文件系统里组织 Skill 目录一样组织 Skill 的内容和资源吧!**

以 [Skill 结构](#skill-结构) 为例,这种目录结构的 Skill 在我们的系统中的表现形式就是:

```java
AgentSkill skill = AgentSkill.builder()
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
skillBox.registerSkill(skill1);

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

skillBox.registerSkill(dataSkill);

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .skillBox(skillBox)
    .build();
```

## 高级功能

### 功能 1: Tool 的渐进式披露

将 Tool 与 Skill 绑定,实现按需激活。避免预先注册所有 Tool 导致的上下文污染,仅在 Skill 被 LLM 使用时才传递相关 Tool。

**渐进式暴露的Tool的生命周期**: Tool 与 Skill 生命周期保持一致, Skill 激活后 Tool 在整个会话期间保持可用, 避免了旧机制中每轮对话后 Tool 失活导致的调用失败问题。

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

### 功能 2: 代码执行能力

为 Skill 提供隔离的代码执行环境,支持 Shell 命令、文件读写等操作。使用 Builder 模式按需组合工具和配置。

**基础用法**:

```java
SkillBox skillBox = new SkillBox(toolkit);

// 启用所有代码执行工具(Shell、读文件、写文件)
skillBox.codeExecution()
    .withShell()
    .withRead()
    .withWrite()
    .enable();
```

**配置说明**:

- **工具选择**: 按需组合 `withShell()`、`withRead()`、`withWrite()`,仅注册显式启用的工具
- **`workDir`**: 所有工具共享的工作目录。指定时自动创建;未指定时延迟创建临时目录 `agentscope-code-execution-*`,JVM 退出自动清理
- **`uploadDir`**: Skill 资源文件的上传位置,默认为 `workDir/skills`
- **文件过滤**: 控制允许上传的资源文件类型,默认接受 `scripts/`、`assets/` 目录及 `.py`、`.js`、`.sh` 扩展名。可通过 `includeFolders()`/`includeExtensions()` 调整,或用 `fileFilter()` 完全自定义(两种方式互斥)
- **自定义 Shell**: `withShell(customShellTool)` 支持传入自定义工具,其 `baseDir` 会被自动覆盖为 `workDir`,安全策略保持不变

**自定义配置**:

```java
// 指定目录 + 自定义 Shell + 文件过滤
ShellCommandTool customShell = new ShellCommandTool(
    null,  // baseDir 会被自动覆盖为 workDir
    Set.of("python3", "node", "npm"),
    command -> askUserApproval(command)
);

skillBox.codeExecution()
    .workDir("/data/agent-workspace")              // 工作目录
    .uploadDir("/data/agent-workspace/my-skills")  // 可选,默认 workDir/skills
    .includeFolders(Set.of("scripts/", "data/"))   // 可选,自定义上传文件夹
    .includeExtensions(Set.of(".py", ".json"))      // 可选,自定义上传扩展名
    .withShell(customShell)
    .withRead()
    .withWrite()
    .enable();

// 或使用完全自定义的文件过滤器(与 includeFolders/includeExtensions 互斥)
skillBox.codeExecution()
    .fileFilter(path -> path.endsWith(".py"))  // 或 SkillFileFilter.acceptAll()
    .withRead()
    .withWrite()
    .enable();
```

### 功能 3: Skill 持久化存储

**为什么需要这个功能?**

Skills 需要在应用重启后保持可用,或者在不同环境间共享。持久化存储支持:

#### 文件系统存储

```java
AgentSkillRepository repo = new FileSystemSkillRepository(Path.of("./skills"));
repo.save(List.of(skill), false);
AgentSkill loaded = repo.getSkill("data_analysis");
```

#### MySQL数据库存储

```java
// 使用简单构造函数（使用默认数据库/表名）
DataSource dataSource = createDataSource();
MysqlSkillRepository repo = new MysqlSkillRepository(dataSource, true, true);

// 使用Builder进行自定义配置
MysqlSkillRepository repo = MysqlSkillRepository.builder(dataSource)
        .databaseName("my_database")
        .skillsTableName("my_skills")
        .resourcesTableName("my_resources")
        .createIfNotExist(true)
        .writeable(true)
        .build();

repo.save(List.of(skill), false);
AgentSkill loaded = repo.getSkill("data_analysis");
```

#### Git仓库 (只读)

用于从 Git 仓库加载 Skills (只读)。支持 HTTPS 和 SSH。

**更新机制**
- 默认每次读取都会做轻量化的远端引用检查，仅当远端 HEAD 变化时才会 pull。
- 可以通过构造函数关闭自动同步，改为手动调用 `sync()` 刷新。

```java
AgentSkillRepository repo = new GitSkillRepository(
    "https://github.com/your-org/your-skills-repo.git");
AgentSkill skill = repo.getSkill("data-analysis");
List<AgentSkill> allSkills = repo.getAllSkills();

GitSkillRepository manualRepo = new GitSkillRepository(
    "https://github.com/your-org/your-skills-repo.git", false);
manualRepo.sync();
```

如果仓库中存在 `skills/` 子目录，会优先从该目录加载，否则使用仓库根目录。

#### Classpath 仓库 (只读)

用于从 classpath 资源中加载预打包的 Skills (只读)。自动兼容标准 JAR 和 Spring Boot Fat JAR。

```java
try (ClasspathSkillRepository repository = new ClasspathSkillRepository("skills")) {
    AgentSkill skill = repository.getSkill("data-analysis");
    List<AgentSkill> allSkills = repository.getAllSkills();
} catch //...
```

资源目录结构: `src/main/resources/skills/` 下放置多个 Skill 子目录,每个子目录包含 `SKILL.md`

> 注意: `JarSkillRepositoryAdapter` 已废弃,请使用 `ClasspathSkillRepository`。

#### Nacos 仓库 (只读)

通过已构建的 `AiService`（或 Nacos 连接配置）从 Nacos 拉取或订阅 Skill，Agent 运行时从 Nacos 实时获取，支持变更订阅与自动感知，适合需要与 Nacos 保持同步的在线场景。

```java
// 使用已构建的 AiService 创建 Nacos 技能仓库
try (NacosSkillRepository repository = new NacosSkillRepository(aiService, "namespace")) {
    AgentSkill skill = repository.getSkill("data-analysis");
    boolean exists = repository.skillExists("data-analysis");
} catch //...
```

> 注意: 需引入 `agentscope-extensions-nacos-skill` 依赖

### 功能 4: 自定义 Skill 提示词

SkillBox 在注入给 Agent 的系统提示词中,会为每个已注册的 Skill 生成描述信息,供 LLM 判断何时加载哪个 Skill。通过构造函数可自定义提示词的两个组成部分:

- **`instruction`**: 提示词头部,说明 Skill 的使用方式(如何加载、路径约定等)。默认包含 `load_skill_through_path` 的调用说明
- **`template`**: 每个 Skill 条目的格式模板,包含三个 `%s` 占位符,依次对应 `name`、`description`、`skillId`

开启代码执行后,还可通过 `.codeExecutionInstruction()` 自定义追加在 `</available_skills>` 之后的代码执行说明段落:

- **`codeExecutionInstruction`**: 代码执行说明模板,所有 `%s` 占位符都会被替换为 `uploadDir` 的绝对路径。传 `null` 或空字符串时使用内置默认值

三者传 `null` 或空字符串时均使用内置默认值。

**示例代码**:

```java
// 自定义 instruction 和 template
String customInstruction = """
    ## 可用技能
    当任务匹配某个技能时,使用 load_skill_through_path 加载它。
    """;

String customTemplate = """
    - **%s**: %s (id: %s)
    """;

SkillBox skillBox = new SkillBox(toolkit, customInstruction, customTemplate);

// 自定义代码执行说明(开启代码执行后生效)
skillBox.codeExecution()
    .workDir("/data/workspace")
    .codeExecutionInstruction("""
        ## 脚本执行
        技能脚本根目录: %s
        执行时请使用绝对路径。
        """)
    .withShell()
    .enable();
```

### 性能优化建议

1. **控制 SKILL.md 大小**: 保持在 5k tokens 以内,建议 1.5-2k tokens
2. **合理组织资源**: 将详细文档放在 `references/` 中,而非 SKILL.md
3. **定期清理版本**: 使用 `clearSkillOldVersions()` 清理不再需要的旧版本
4. **避免重复注册**: 利用重复注册保护机制,相同 Skill 对象配多个 Tool 时不会创建重复版本

## 相关文档

- [Claude Agent Skills 官方文档](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview) - 完整的概念和架构介绍
- [Tool 使用指南](./tool.md) - 工具系统的使用方法
- [Agent 配置](./agent.md) - 智能体配置和使用
