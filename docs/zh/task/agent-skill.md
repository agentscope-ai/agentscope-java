# 智能体技能包 (Agent Skill)

## 概述

Agent Skill 是扩展智能体能力的模块化技能包。每个 Skill 包含指令、元数据和可选资源(如脚本、参考文档、示例等),智能体在相关任务时会自动使用这些资源。

**参考资料**: [Claude Agent Skills 官方文档](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview)

## 核心特性

### 渐进式披露机制

Agent Skill 采用**三阶段渐进式披露**机制,优化上下文窗口使用:

**三个阶段:**

1. **元数据阶段** - 智能体初始化时加载 `name` 和 `description` (~100 tokens/Skill)
2. **指令阶段** - AI 判断需要时加载 SKILL.md 完整内容 (<5k tokens)
3. **资源阶段** - AI 按需访问 references/、scripts/ 等资源文件 (按实际使用计算)

**重要**: Skill 同样实现了 Tool 的渐进式披露,只有当技能被使用时,Skill 协同注册的 Tool 才会生效并传递给 LLM。

这种机制确保任何时刻只有相关内容占据上下文窗口。

### 渐进式披露工作流程

**完整流程:**

1. **Agent 初始化**
   - 扫描并注册所有 Skills
   - 提取 name 和 description
   - 注入到 System Prompt
   - 动态注册 Skill 加载工具

2. **用户提问**
   - 用户: "帮我分析这份数据"

3. **AI 自主决策**
   - 识别需要 data_analysis skill
   - 调用 `loadSkillContent("data_analysis_custom")`
   - 系统返回完整 SKILL.md 内容
   - 激活 skill 绑定的 tool

4. **按需加载资源**
   - AI 根据 SKILL.md 指令决定需要哪些资源
   - 调用 `loadSkillResource(..., "references/formulas.md")`
   - 调用相关 Tools: loadData, calculateStats, generateChart

5. **完成任务** - AI 返回结果

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

### Skill 结构

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

### SKILL.md 格式规范

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
// 准备 SKILL.md 内容
String skillMd = """
---
name: data_analysis
description: Use this skill when analyzing data, calculating statistics, or generating reports
---
# 技能名称
Content...
""";

// 准备资源文件(可选)
Map<String, String> resources = Map.of(
    "references/formulas.md", "# 常用公式\n...",
    "examples/sample.csv", "name,value\nA,100\nB,200"
);

// 创建 Skill
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

### 2. 注册 Skill

```java
Toolkit toolkit = new Toolkit();
SkillBox skillBox = new SkillBox(toolkit);

// 基础注册
registerAgentSkill(skill);
```

### 3. 注册 Skill 发现工具

```java
skillBox.registerSkillLoadTools();
```

这个方法会注册三个工具,用于让 LLM 发现和加载 Skill 的内容和资源:

- `skill_md_load_tool`: 加载 SKILL.md 完整内容
- `skill_resources_load_tool`: 加载指定资源文件
- `get_all_resources_path_tool`: 获取所有资源文件路径

### 4. 注册skill hook到agent

```java
ReActAgent agent =
        ReActAgent.builder()
                .name("DataAnalyst")
                .sysPrompt(buildSystemPrompt())
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(apiKey)
                                .modelName("qwen-max")
                                .stream(true)
                                .enableThinking(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(toolkit)
                .hooks(List.of(skillBox.getSkillHook()))
                .memory(new InMemoryMemory())
                .build();
```

### 5. 使用 Skill

注册后,AI 会在 System Prompt 中看到 Skill 的元数据,并在需要时自动使用。

**渐进式披露流程:** 用户提问 → AI 识别相关 Skill → AI 调用工具加载完整内容 → AI 根据指令执行任务

**也就是说,你不需要做任何额外操作,系统会自动发现和注册 Skill,并将其元数据注入到 System Prompt 中,在需要时自动使用。**

## 高级功能

### 功能 1: Tool 的渐进式披露

**为什么需要这个功能?**

在实际应用中,在 Skill 中指示 LLM 去调用 Tool 来完成任务是常见的场景。在过去,我们需要将这些 Tool 全部都预先注册好,但这样会带来的问题是:我们要启用的 Skill 越多,我们要提前注册的 Tool 就越多,这样就污染了 Tool 相关的上下文。

所以,期望 Skill 会使用到的 Tool 同样是渐进式披露的就变成了一个有意义的需求。于是我们提供在注册 Tool 的时候将其和 Skill 绑定的功能,这些被绑定的 Tool,只有在 Skill 确定了会被 LLM 使用的时候才会传递给 LLM。

**示例代码**:

```java
Toolkit toolkit = new Toolkit();
SkillBox skillBox = new SkillBox(toolkit);

// 创建 Skill
AgentSkill dataSkill = AgentSkill.builder()
    .name("data_analysis")
    .description("Comprehensive data analysis capabilities")
    .skillContent("# Data Analysis\n...")
    .build();

// 创建多个相关的 Tool
AgentTool loadDataTool = new AgentTool(...);
AgentTool calculateTool = new AgentTool(...);
AgentTool visualizeTool = new AgentTool(...);

// 方式 1: 多次注册相同 Skill 对象 + 不同 Tool
skillBox.registration()
    .skill(dataSkill)
    .tool(loadDataTool)
    .apply();

skillBox.registration()
    .skill(dataSkill)  // 相同的 skill 对象引用,并不会注册新的版本
    .tool(calculateTool)
    .apply();

skillBox.registration()
    .skill(dataSkill)  // 相同的 skill 对象引用,并不会注册新的版本
    .tool(visualizeTool)
    .apply();

// 方式 2: 注册到 Skill 分组
skillBox.createSkillGroup("analytics", "数据分析工具集");

skillBox.registration()
    .skill(dataSkill)
    .tool(loadDataTool)
    .skillGroup("analytics")
    .apply();

// 停用整个分组,所有相关 Tools 都不可用
skillBox.updateSkillGroups(List.of("analytics"), false);
```

**重复注册保护机制**:

```java
// 系统会检查 Skill 对象引用
AgentSkill skill = new AgentSkill("my_skill", "desc", "content", null);

// 第一次注册: 创建版本
skillBox.registration().skill(skill).tool(tool1).apply();

// 第二次注册相同对象: 不创建新版本,只添加 tool2
skillBox.registration().skill(skill).tool(tool2).apply();

// 第三次注册相同对象: 不创建新版本,只添加 tool3
skillBox.registration().skill(skill).tool(tool3).apply();

// 但如果是新的 Skill 对象(即使内容相同),会创建新版本
AgentSkill newSkill = new AgentSkill("my_skill", "desc", "content", null);
skillBox.registration().skill(newSkill).tool(tool4).apply();  // 创建新版本
```

### 功能 2: Skill 分组管理

**为什么需要这个功能?**

在复杂应用中,可能有数十个 Skills。不同的任务场景需要不同的 Skills 组合:

- 数据分析场景: 需要统计、可视化、报告生成等 Skills
- 文档处理场景: 需要 PDF、Word、Excel 等 Skills
- 代码开发场景: 需要代码生成、测试、部署等 Skills

分组功能允许按场景组织 Skills,并批量控制其可用性。

**这个功能解决了什么?**

1. **场景切换**: 快速切换不同任务场景的 Skills
2. **性能优化**: 只激活当前需要的 Skills,减少 System Prompt 大小
3. **组织管理**: 逻辑清晰地组织大量 Skills
4. **批量操作**: 一次操作控制多个相关 Skills

**示例代码**:

```java
SkillBox skillBox = new SkillBox(new Toolkit());

// 场景 1: 创建数据分析分组
skillBox.createSkillGroup("data_analysis", "数据分析相关技能", true);

AgentSkill statsSkill = new AgentSkill("statistics", "Statistical analysis", "...", null);
AgentSkill vizSkill = new AgentSkill("visualization", "Data visualization", "...", null);
AgentSkill reportSkill = new AgentSkill("reporting", "Report generation", "...", null);

skillBox.registration().skill(statsSkill).skillGroup("data_analysis").apply();
skillBox.registration().skill(vizSkill).skillGroup("data_analysis").apply();
skillBox.registration().skill(reportSkill).skillGroup("data_analysis").apply();

// 场景 2: 创建文档处理分组
skillBox.createSkillGroup("document_processing", "文档处理相关技能", false);

AgentSkill pdfSkill = new AgentSkill("pdf_handler", "PDF processing", "...", null);
AgentSkill wordSkill = new AgentSkill("word_handler", "Word processing", "...", null);

skillBox.registration().skill(pdfSkill).skillGroup("document_processing").apply();
skillBox.registration().skill(wordSkill).skillGroup("document_processing").apply();

// 场景 3: 根据任务切换激活的分组
// 执行数据分析任务
skillBox.updateSkillGroups(List.of("data_analysis"), true);
skillBox.updateSkillGroups(List.of("document_processing"), false);

System.out.println("当前激活: " + skillBox.getActiveSkillGroups());
// 输出: [data_analysis]

// 切换到文档处理任务
skillBox.updateSkillGroups(List.of("data_analysis"), false);
skillBox.updateSkillGroups(List.of("document_processing"), true);

System.out.println("当前激活: " + skillBox.getActiveSkillGroups());
// 输出: [document_processing]

// 查看激活分组的详细信息
String notes = skillBox.getActivatedSkillGroupsNotes();
System.out.println(notes);
// 输出:
// Activated skill groups:
// - document_processing: 文档处理相关技能
```

### 功能 3: Skill 版本管理

**为什么需要这个功能?**

Skill 会随着需求变化而更新:

- 修复 Bug
- 添加新功能
- 优化指令
- 更新资源文档

版本管理允许保留历史版本,在新版本出现问题时可以快速回滚。

**这个功能解决了什么?**

1. **安全更新**: 保留旧版本,新版本有问题可以回滚
2. **版本追踪**: 记录 Skill 的演进历史
3. **A/B 测试**: 可以同时保留多个版本进行对比测试
4. **重复注册保护**: 相同的 Skill 对象不会创建重复版本

**重要机制**: 系统通过对象引用判断是否为同一个 Skill。如果多次注册相同的 Skill 对象(即使配套不同的 Tool),不会创建新版本。只有当注册新的 Skill 对象时,才会创建新版本。

**示例代码**:

```java
SkillBox skillBox = new SkillBox(new Toolkit());

// 1. 创建并注册初始版本
AgentSkill v1 = AgentSkill.builder()
    .name("data_processor")
    .description("Process data files")
    .skillContent("# Version 1.0\n基础数据处理功能")
    .build();

skillBox.registerAgentSkill(v1);

// 2. 重复注册相同对象 + 不同 Tool (不会创建新版本)
AgentTool tool1 = new AgentTool(...);
AgentTool tool2 = new AgentTool(...);

skillBox.registration().skill(v1).tool(tool1).apply();  // 不创建新版本
skillBox.registration().skill(v1).tool(tool2).apply();  // 不创建新版本

// 3. 在原先的skill基础上创建新的skill对象并注册为新版本
AgentSkill v2 = v1.toBuilder()
    .skillContent("# Version 2.0\n增强功能: 支持更多格式")
    .addResource("references/new-formats.md", "# 新格式支持\n...")
    .build();

skillBox.registerAgentSkill(v2);

// 4. 再次注册 v2 对象 + 新 Tool (不会创建新版本)
AgentTool tool3 = new AgentTool(...);
skillBox.registration().skill(v2).tool(tool3).apply();  // 不创建新版本

// 查看版本列表
versions = skillBox.listSkillVersions("data_processor_custom");
System.out.println(versions); // 输出: [skill-v1-versionId, skill-v2-versionId]

// 5. 如果 v2 有问题,回滚到 v1
skillBox.rollbackSkillVersion("data_processor_custom", skill-v1-versionId); // 最新的版本变回了v1

// 6. 获取特定版本
AgentSkill retrieved = skillBox.getSkillVersion("data_processor_custom", skill-v2-versionId);

// 7. 获取最新版本
String latest = skillBox.getLatestSkillVersionId("data_processor_custom");
System.out.println("最新版本: " + latest);

// 8. 清理旧版本(仅保留最新版本)
skillBox.clearSkillOldVersions("data_processor_custom");
```

### 功能 4: Skill 持久化存储

**为什么需要这个功能?**

Skills 需要在应用重启后保持可用,或者在不同环境间共享。持久化存储支持:

- 文件系统存储
- 数据库存储 (暂未实现)
- Git 仓库 (暂未实现)

**这个功能解决了什么?**

1. **持久化**: Skills 不会因应用重启而丢失
2. **共享**: 团队成员可以共享 Skills
3. **版本控制**: 结合 Git 可以追踪 Skills 的变更历史
4. **扩展性**: 支持自定义存储后端

**示例代码**:

```java
// 1. 使用文件系统存储
Path skillsDir = Path.of("./my-skills");
AgentSkillRepository repository = new FileSystemSkillRepository(skillsDir);

// 2. 保存 Skill
AgentSkill skill = AgentSkill.builder()
    .name("my_skill")
    .description("My custom skill")
    .skillContent("# Content")
    .addResource("ref.md", "Reference doc")
    .build();

repository.save(List.of(skill), false);  // force=false: 不覆盖已存在的

// 3. 从存储加载 Skill
AgentSkill loaded = repository.getSkill("my_skill");

// 4. 列出所有 Skills
List<String> allSkillNames = repository.getAllSkillNames();
List<AgentSkill> allSkills = repository.getAllSkills();

// 5. 删除 Skill
repository.delete("my_skill");

// 6. 检查 Skill 是否存在
boolean exists = repository.skillExists("my_skill");

// 7. 获取 Repository 信息
AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
System.out.println("Repository: " + info.getRepositoryType());
System.out.println("Location: " + info.getLocation());
```

**自定义存储实现**:

```java
// 实现自定义存储后端(例如: 数据库)

public class DatabaseSkillRepository implements AgentSkillRepository {

    private final DataSource dataSource;

    public DatabaseSkillRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public AgentSkill getSkill(String name) {
        // 从数据库加载
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT * FROM skills WHERE name = ?";
            // ... 执行查询并构建 AgentSkill
        }
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        // 保存到数据库
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO skills (name, description, content, resources, source) VALUES (?, ?, ?, ?, ?)";
            // ... 执行插入
        }
    }

    // 实现其他必需方法...
}

        // 使用自定义存储
        AgentSkillRepository dbRepo = new DatabaseSkillRepository(dataSource);
dbRepo.

        save(List.of(skill), false);
```

## 完整示例

完整的数据分析 Skill 系统示例请参考:`agentscope-examples/quickstart` 模块中的 `AgentSkillExample.java`。

## 注意事项

### 安全考虑

**重要**: 仅使用来自可信来源的 Skills。恶意 Skill 可能包含有害指令或脚本。

关键安全建议:

- ✅ 审查所有 Skill 内容,包括 SKILL.md、scripts/ 和 resources/
- ✅ 检查脚本是否执行意外操作(网络调用、文件访问等)
- ✅ 使用沙箱环境测试未知来源的 Skills
- ❌ 避免使用从外部 URL 动态获取内容的 Skills

**路径遍历保护**:

`FileSystemSkillRepository` 内置了安全机制来防止路径遍历攻击:

- ✅ 自动验证所有技能名称,防止目录遍历(如 `../`、`../../`)
- ✅ 阻止绝对路径访问(如 `/etc/passwd`、`C:\Windows\System32`)
- ✅ 路径规范化,消除 `.` 和 `..` 段
- ✅ 确保所有操作都在配置的基础目录内

```java
// 安全: 有效的技能名称
repository.getSkill("my_skill");  // ✅ 允许

// 被阻止: 路径遍历尝试
repository.getSkill("../outside");  // ❌ 抛出 IllegalArgumentException
repository.getSkill("/etc/passwd");  // ❌ 抛出 IllegalArgumentException
repository.getSkill("valid/../outside");  // ❌ 抛出 IllegalArgumentException
```

这种保护适用于所有仓库操作: `getSkill()`、`save()`、`delete()` 和 `skillExists()`。

详细安全指南请参阅 [Claude Agent Skills 安全考虑](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview#安全考虑)。

### 性能优化建议

1. **控制 SKILL.md 大小**: 保持在 5k tokens 以内,建议 1.5-2k tokens
2. **合理组织资源**: 将详细文档放在 `references/` 中,而非 SKILL.md
3. **使用分组管理**: 仅激活当前场景需要的 Skill 分组
4. **定期清理版本**: 使用 `clearSkillOldVersions()` 清理不再需要的旧版本
5. **避免重复注册**: 利用重复注册保护机制,相同 Skill 对象配多个 Tool 时不会创建重复版本

### 常见问题

**Q: Skill 的 `skillId` 格式是什么?**

A: `skillId` 格式为 `{name}_{source}`,例如 `data_analysis_custom`。注意使用下划线 `_` 而非连字符。

**Q: 如何让 AI 自动使用 Skill?**

A: 注册 Skill 后,其元数据会自动注入 System Prompt。AI 会根据 `description` 字段判断何时使用该 Skill。确保 `description` 清晰描述使用场景。同时需要调用 `skillBox.registerSkillLoadTools(toolkit)` 注册加载工具。

**Q: 为什么多次注册相同 Skill 对象不会创建新版本?**

A: 这是设计的重复注册保护机制。允许一个 Skill 关联多个 Tools,而不会因为每次注册 Tool 就创建一个新版本。系统通过对象引用判断是否为同一个 Skill。只有注册新的 Skill 对象时才会创建新版本。

**Q: 如何在不同任务间切换 Skills?**

A: 使用分组功能 `skillBox.updateSkillGroups()` 激活或停用不同的 Skill 组

**Q: Skill 的资源文件有大小限制吗?**

A: 建议单个资源文件 < 10k tokens。由于采用按需加载,总资源大小没有硬性限制,但应避免过大的单个文件。

**Q: Tool 和 Skill 是什么关系?**

A: Tool 是具体的可执行功能,Skill 是领域知识和指令的集合。一个 Skill 通常需要多个 Tool 来实现其完整功能。通过联动注册,可以将它们作为一个功能单元统一管理。

## 相关文档

- [Claude Agent Skills 官方文档](https://platform.claude.com/docs/zh-CN/agents-and-tools/agent-skills/overview) - 完整的概念和架构介绍
- [Tool 使用指南](./tool.md) - 工具系统的使用方法
- [Agent 配置](./agent.md) - 智能体配置和使用
