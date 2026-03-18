# Skills (Progressive Disclosure)

In the **skills** pattern, specialized capabilities are packaged as invokable "skills" that augment an agent’s behavior. Skills are primarily **prompt-driven**: the agent sees skill metadata (e.g. name and description) first and loads full skill content **on demand** via a tool (e.g. `read_skill`). This keeps the initial context small and avoids loading all skill text upfront.

AgentScope implements this with **SkillBox**, **SkillRepository** (e.g. `ClasspathSkillRepository`), and skill tools. The core feature is documented in [Agent Skill](../task/agent-skill.md); this page focuses on the **multi-agent example** that uses the same mechanism for a SQL assistant.

## Overview

Key characteristics:

- **Progressive disclosure**: The model sees skill **descriptions** in the system prompt; when the user asks for something that needs schema or examples, the agent calls **read_skill(skill_name)** to load the full content (e.g. `SKILL.md`).
- **Prompt-driven**: Each skill is defined by a prompt (and optional resources); no separate sub-agent is invoked—just a tool that returns the skill’s text.
- **Lightweight**: Skills are simpler than full sub-agents and can be developed and versioned independently (e.g. per team or domain).

Use the skills pattern when you want one agent with many possible specializations, don’t need strict constraints between skills, or want to avoid loading all domain content into context at once.

## Architecture

```
User → Agent (sees skill descriptions) → read_skill(name) → full SKILL.md content
                ↓
        Agent uses content to answer (e.g. generate SQL)
```

In the example:

- **ClasspathSkillRepository**: Loads skills from classpath `skills/` (each skill is a directory with a `SKILL.md`; frontmatter has `name` and `description`, body is full content).
- **SkillBox**: Holds a **Toolkit** and registers skills; provides the skill system prompt and the **read_skill** (and optionally **use_skill**) tools.
- **ReActAgent**: Uses **DashScopeChatModel**, the toolkit, **skillBox**, and **InMemoryMemory**. The agent’s system prompt includes “Available Skills” with descriptions; when needed, it calls **read_skill(skill_name)** to load the full `SKILL.md`.

## Implementation

1. **Skill format**: Under `src/main/resources/skills/`, each skill is a directory with a `SKILL.md` file. YAML frontmatter: `name`, `description`. Body: schema, business logic, example queries, etc.
2. **Repository**: `new ClasspathSkillRepository("skills")` so skills are loaded from the classpath (e.g. `skills/sales_analytics/SKILL.md`, `skills/inventory_management/SKILL.md`).
3. **SkillBox**: `SkillBox(toolkit)` and for each skill from `skillRepository.getAllSkills()` call `skillBox.registration().skill(skill).apply()`. The SkillBox adds the skill system prompt and skill tools to the agent.
4. **Agent**: Build **ReActAgent** with **DashScopeChatModel**, **toolkit**, **skillBox**, and **InMemoryMemory**. The agent sees skill descriptions and the **read_skill** tool; it loads full content only when relevant.

## Example Project

The **SQL assistant** example uses two skills: **sales_analytics** (customers, orders, revenue) and **inventory_management** (products, warehouses, stock). The user asks in natural language; the agent calls **read_skill("sales_analytics")** or **read_skill("inventory_management")** when needed, then generates SQL.

- **Location**: `agentscope-examples/multiagent-patterns/skills/`
- **Key classes**: `SkillsConfig` (ClasspathSkillRepository, SkillBox, ReActAgent), `SkillsRunner` (optional demo).
- **Run**: Set `skills.runner.enabled=true` to run a single-query demo on startup (e.g. “Write a SQL query to find all customers who made orders over $1000 in the last month”).

**Build and run** (from repo root):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/skills -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/skills spring-boot:run
```

**Use in your code**: Inject the ReActAgent (e.g. `sqlAssistantAgent`) and call it with a user `Msg`; get text from the response with `getTextContent()`. The agent will use skill descriptions to decide when to call **read_skill** and then generate the SQL.

## Related Documentation

- [Agent Skill](../task/agent-skill.md) - Skill format, SkillBox, and progressive disclosure in AgentScope
- [Pipeline](./pipeline.md) - Sequential and parallel agent composition
- [Agent as Tool](../task/agent-as-tool.md) - Sub-agents as tools
