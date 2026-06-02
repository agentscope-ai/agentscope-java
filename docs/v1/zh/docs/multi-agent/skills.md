# Skills（渐进式披露）

在 **Skills** 模式中，专项能力被打包成可调用的「技能」，用于增强智能体行为。技能主要是**提示驱动**的：智能体先看到技能元数据（如名称、描述），再通过工具（如 `read_skill`）**按需**加载完整技能内容，从而控制初始上下文大小，避免一次性加载所有技能文本。

AgentScope 通过 **SkillBox**、**SkillRepository**（如 `ClasspathSkillRepository`）和技能工具实现该机制。核心能力在 [智能体技能](../task/agent-skill.md) 中说明；本文侧重使用同一机制的 **多智能体示例**（SQL 助手）。

## 概述

核心特点：

- **渐进式披露**：模型在系统提示中只看到技能**描述**；当用户问题需要 schema 或示例时，智能体调用 **read_skill(skill_name)** 加载完整内容（如 `SKILL.md`）。
- **提示驱动**：每个技能由一段提示（及可选资源）定义；不启动单独子智能体，仅通过工具返回技能文本。
- **轻量**：技能比完整子智能体更简单，可按团队或领域独立开发与版本管理。

当希望一个智能体具备多种专项能力、且不需要在技能间施加严格约束、或希望避免把所有领域内容一次性放进上下文时，适合使用 Skills 模式。

## 架构

```
用户 → 智能体（仅见技能描述）→ read_skill(name) → 完整 SKILL.md 内容
                ↓
        智能体基于内容作答（如生成 SQL）
```

示例中：

- **ClasspathSkillRepository**：从 classpath 的 `skills/` 加载技能（每个技能为一个目录，内含 `SKILL.md`；frontmatter 含 `name`、`description`，正文为完整内容）。
- **SkillBox**：持有 **Toolkit** 并注册技能；提供技能系统提示与 **read_skill**（及可选的 **use_skill**）工具。
- **ReActAgent**：使用 **DashScopeChatModel**、toolkit、**skillBox** 和 **InMemoryMemory**。系统提示中包含「可用技能」及描述；需要时调用 **read_skill(skill_name)** 加载完整 `SKILL.md`。

## 实现

1. **技能格式**：在 `src/main/resources/skills/` 下，每个技能为一个目录，内含 `SKILL.md`。YAML frontmatter：`name`、`description`。正文：schema、业务逻辑、示例查询等。
2. **仓库**：`new ClasspathSkillRepository("skills")`，从 classpath 加载（如 `skills/sales_analytics/SKILL.md`、`skills/inventory_management/SKILL.md`）。
3. **SkillBox**：`SkillBox(toolkit)`，并对 `skillRepository.getAllSkills()` 中的每个技能执行 `skillBox.registration().skill(skill).apply()`。SkillBox 为智能体注入技能系统提示和技能工具。
4. **智能体**：用 **DashScopeChatModel**、**toolkit**、**skillBox** 和 **InMemoryMemory** 构建 **ReActAgent**。智能体只看到技能描述和 **read_skill** 工具，仅在相关时加载完整内容。

## 示例项目

**SQL 助手**示例包含两个技能：**sales_analytics**（客户、订单、收入）和 **inventory_management**（产品、仓库、库存）。用户用自然语言提问；智能体在需要时调用 **read_skill("sales_analytics")** 或 **read_skill("inventory_management")**，再生成 SQL。

- **路径**：`agentscope-examples/multiagent-patterns/skills/`
- **主要类**：`SkillsConfig`（ClasspathSkillRepository、SkillBox、ReActAgent），`SkillsRunner`（可选演示）。
- **运行**：设置 `skills.runner.enabled=true` 可在启动时运行单次查询演示（如「写出查询过去一个月内订单金额超过 1000 的所有客户的 SQL」）。

**构建与运行**（在仓库根目录）：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/skills -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/skills spring-boot:run
```

**在代码中使用**：注入 ReActAgent（如 `sqlAssistantAgent`），传入用户 `Msg` 调用；用 `getTextContent()` 取回复文本。智能体会根据技能描述决定何时调用 **read_skill** 并生成 SQL。

## 相关文档

- [智能体技能](../task/agent-skill.md) - AgentScope 中的技能格式、SkillBox 与渐进式披露
- [Pipeline](./pipeline.md) - 顺序与并行智能体组合
- [Agent as Tool](../task/agent-as-tool.md) - 子智能体即工具
