# Skills (Progressive Disclosure) SQL Assistant Example

This example implements the **skills** (progressive disclosure) pattern using **AgentScope** (aligned with [AgentSkillExample](../../quickstart/src/main/java/io/agentscope/examples/quickstart/AgentSkillExample.java)). A single SQL assistant agent is aware of available skills via the SkillBox and loads full skill content on demand with the **read_skill** tool.

## Architecture

- **AgentScope components**
  - **ClasspathSkillRepository**: Loads skills from classpath `skills/` (each skill is a directory with a `SKILL.md` file; frontmatter defines `name` and `description`, body is full content).
  - **SkillBox**: Holds a **Toolkit** and registers all loaded skills; provides the skill system prompt and the read_skill / use_skill tools.
  - **ReActAgent**: Uses **DashScopeChatModel**, the toolkit, skillBox, and **InMemoryMemory**.

- **Progressive disclosure**
  - The model sees skill **descriptions** in the system prompt (e.g. “sales_analytics: Database schema and business logic for sales data…”).
  - When the user asks for something that requires schema or examples, the agent calls **read_skill(skill_name)** to load the full SKILL.md content (tables, business logic, example queries).
  - This keeps the initial context small and avoids loading all skill text upfront.

- **Skills in this example**
  Two skills under `src/main/resources/skills/`: **sales_analytics** (customers, orders, revenue) and **inventory_management** (products, warehouses, stock). Each has a `SKILL.md` with YAML frontmatter and markdown body.

## Design choices (aligned with AgentSkillExample)

1. **ClasspathSkillRepository**
   Uses `new ClasspathSkillRepository("skills")` so skills are loaded from `src/main/resources/skills/` (and the same path inside the packaged JAR).

2. **SkillBox**
   **SkillBox(toolkit)** and `skillBox.registration().skill(skill).apply()` for each skill from `skillRepository.getAllSkills()`. The SkillBox provides the skill system prompt and skill tools to the agent.

3. **Agent configuration**
   **ReActAgent** is built with **DashScopeChatModel** (API key from `spring.ai.dashscope.api-key` or `AI_DASHSCOPE_API_KEY`), **toolkit**, **skillBox**, and **InMemoryMemory**. No framework hooks or interceptors—skill behavior comes from AgentScope’s SkillBox.

4. **SKILL.md format**
   Each skill directory contains one `SKILL.md` with frontmatter (`name`, `description`) and a markdown body (schema, business logic, example queries). Same format as in AgentScope quickstart and core tests.

## Project layout

```
agentscope-examples/multiagent-patterns/skills/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../skills/
    │   ├── SkillsApplication.java
    │   ├── SkillsConfig.java           # ClasspathSkillRepository, SkillBox, sqlAssistantAgent (ReActAgent)
    │   └── SkillsRunner.java            # optional demo runner
    └── resources/
        ├── application.yml
        └── skills/                     # classpath:skills (AgentScope format)
            ├── sales_analytics/
            │   └── SKILL.md
            └── inventory_management/
                └── SKILL.md
```

## How to run

### Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key** for the chat model.

Set it:

```bash
export AI_DASHSCOPE_API_KEY=your-dashscope-api-key
```

Or set `spring.ai.dashscope.api-key` in `application.yml`.

### Build

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/skills -am -B package -DskipTests
```

Or from this directory:

```bash
cd agentscope-examples/multiagent-patterns/skills
mvn -B package -DskipTests
```

### Run the application

Default: the app starts **without** running the demo:

```bash
java -jar target/skills-*.jar
# or
./mvnw -pl agentscope-examples/multiagent-patterns/skills spring-boot:run
```

To run the **demo** on startup (one user query that should trigger `read_skill("sales_analytics")` and then generate a SQL query):

Set:

```bash
export skills.runner.enabled=true
# or in application.yml: skills.runner.enabled: true
```

Then start the app. The runner sends: “Write a SQL query to find all customers who made orders over $1000 in the last month” and logs the assistant reply.

### Using the SQL assistant in your own code

Inject the AgentScope **ReActAgent** and call it with a user **Msg**; get text from the response with **getTextContent()**:

```java
@Autowired
ReActAgent sqlAssistantAgent;

Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("Write a SQL query to find products below reorder point").build())
    .build();
Msg response = sqlAssistantAgent.call(userMsg).block();
String text = response.getTextContent();
```

The agent will use the skill descriptions to decide when to call **read_skill** (e.g. `inventory_management` for reorder-point queries) and then generate the SQL.

## Configuration

- **`spring.ai.dashscope.api-key`**
  Optional. Defaults to `AI_DASHSCOPE_API_KEY` env var.

- **`skills.runner.enabled`**
  If `true`, runs the single-query demo on startup. Default: `false`.

## Example flow

1. User: “Write a SQL query to find all customers who made orders over $1000 in the last month.”
2. Agent sees “Available Skills” in the system prompt (sales_analytics, inventory_management descriptions) from the SkillBox.
3. Agent calls **read_skill("sales_analytics")** (AgentScope skill tool) to get the full SKILL.md content.
4. Tool returns the sales_analytics content (customers, orders, revenue rules, example query).
5. Agent uses that content to generate a SQL query and replies to the user.
