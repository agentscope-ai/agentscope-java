# AgentScope Harness Example — Text-to-SQL Agent

A practical example showing how to build a domain-specific agent with the **AgentScope Harness**
framework. The scenario: translate natural-language questions into SQL queries against the
[Chinook](https://github.com/lerocha/chinook-database) SQLite sample database.

## What This Example Covers

| Feature | How it is demonstrated |
|---------|------------------------|
| **Workspace** | Pre-populated from bundled resources by `WorkspaceInitializer` |
| **AGENTS.md** | Agent persona, principles, and workflow loaded automatically |
| **skills/** | `query-writing` and `schema-exploration` loaded on demand |
| **knowledge/** | Chinook schema reference injected as domain knowledge |
| **subagents/** | `schema-analyst` and `query-optimizer` available for delegation |
| **Custom tool** | `SqliteTool` registered via `Toolkit` alongside harness defaults |
| **Memory tools** | `memory_search` / `memory_get` enabled for cross-session recall |
| **RuntimeContext** | Session ID bound per invocation for stateful multi-turn use |

## Project Layout

```
harness-example/
├── pom.xml
├── .env.example                         # Environment variable template
├── README.md                            # This file
└── src/main/
    ├── java/io/agentscope/harness/example/
    │   ├── TextToSqlExample.java        # Main entry point (CLI)
    │   ├── SqliteTool.java              # Custom @Tool: list_tables, get_schema, execute_query
    │   └── WorkspaceInitializer.java    # Copies bundled workspace to disk on first run
    └── resources/
        ├── log4j2.xml                   # Log4j2 console layout and logger levels
        ├── log4j2.component.properties  # Log4j2 component flags (e.g. disable JMX)
        ├── agentscope.json.example      # Agent configuration template
        ├── io/agentscope/harness/example/
        │   └── chinook-default.sqlite   # Bundled Chinook DB (copied to AGENTSCOPE_DB_PATH if missing)
        └── workspace/                   # Bundled workspace template (extracted at runtime)
            ├── AGENTS.md                # Agent identity and core rules
            ├── MEMORY.md                # Persistent notes (pre-seeded)
            ├── knowledge/
            │   └── KNOWLEDGE.md         # Full Chinook schema reference
            ├── skills/
            │   ├── query-writing/
            │   │   └── SKILL.md         # SQL query writing workflow
            │   └── schema-exploration/
            │       └── SKILL.md         # Database structure discovery workflow
            └── subagents/
                ├── schema-analyst.md    # Schema documentation specialist
                └── query-optimizer.md   # Query optimisation specialist
```

## Quick Start

### 1. Build

```bash
cd agentscope-java
mvn -pl agentscope-examples/agents/harness-example package -am -DskipTests
```

### 2. Chinook database (optional)

The example ships a bundled Chinook SQLite file in the JAR. On first run, if `chinook.db` (or
`AGENTSCOPE_DB_PATH`) is missing, it is copied from the classpath automatically. Use your own file
only when you want a different path or a refreshed copy from upstream:

```bash
curl -L -o chinook.db \
  https://github.com/lerocha/chinook-database/raw/master/ChinookDatabase/DataSources/Chinook_Sqlite.sqlite
```

### 3. Set your API key

```bash
export DASHSCOPE_API_KEY=your_key_here
```

Or copy `.env.example` → `.env` and fill in your values, then load it:

```bash
cp .env.example .env
# edit .env, then:
source <(grep -v '^#' .env | sed 's/^/export /')
```

### 4. Run

Interactive (no arguments — type questions at the `>` prompt; `quit`, empty line, or Ctrl-D to
exit):

```bash
java -cp target/harness-example-*.jar \
  io.agentscope.harness.example.TextToSqlExample
```

One-shot (single question, then exit — useful for scripts):

```bash
java -cp target/harness-example-*.jar \
  io.agentscope.harness.example.TextToSqlExample \
  "What are the top 5 best-selling artists?"
```

Example prompts you can paste at `>`:

- Which customers are from Brazil?
- Show me the monthly revenue trend for 2013
- Which employee has the most customers?
- What are the top genres by number of tracks?
- How many tracks are longer than 5 minutes?

## Logging (Log4j2)

This module uses **Log4j2** with `log4j-slf4j2-impl` as the SLF4J binding. Configuration lives in
[`src/main/resources/log4j2.xml`](src/main/resources/log4j2.xml) (console appender, tuned levels
for Netty / Reactor / OkHttp).

Set **`AGENTSCOPE_LOG_LEVEL`** (for example `DEBUG`) to change verbosity for `io.agentscope.*`
without editing the XML. To use a different file at runtime:

```bash
java -Dlog4j.configurationFile=/path/to/log4j2-custom.xml ...
```

## How It Works

### Workspace Initialisation

On first run `WorkspaceInitializer.init(workspace)` extracts the bundled template files from the
JAR into `.agentscope/workspace/`. On subsequent runs existing files are preserved so the agent's
accumulated notes survive across restarts.

```
.agentscope/workspace/
├── AGENTS.md          ← always loaded into system prompt
├── MEMORY.md          ← loaded into <memory_context>
├── knowledge/KNOWLEDGE.md  ← loaded into <domain_knowledge_context>
├── skills/            ← skill descriptions shown; full content loaded on demand
└── subagents/         ← subagent specs registered as callable agents
```

### Agent Construction

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("text-to-sql")
    .model(model)
    .workspace(workspace)          // ← harness reads all workspace files from here
    .toolkit(toolkit)              // ← includes our custom SqliteTool
    .maxIters(20)
    .build();
```

### Custom Tool Registration

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SqliteTool(dbPath));  // adds sql_list_tables, sql_get_schema, sql_execute_query
```

`SqliteTool` uses `@Tool` / `@ToolParam` annotations — the same mechanism used by all harness
built-in tools. Methods are discovered at runtime via reflection and exposed to the LLM as JSON
schemas.

### Calling the Agent

```java
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("my-session-id")
    .build();

Msg reply = agent.call(Msg.userMsg("Top 5 artists?"), ctx).block();
```

The `RuntimeContext` carries the session ID used by hooks (WorkspaceContextHook, MemoryFlushHook) to
isolate per-session state and persist memory between turns in the same session.

## Customising the Agent

All behaviour can be tuned by editing files in `.agentscope/workspace/` — no recompilation needed:

| File | What to change |
|------|----------------|
| `AGENTS.md` | Persona, rules, communication style |
| `MEMORY.md` | Pre-seed knowledge the agent should know from turn one |
| `knowledge/KNOWLEDGE.md` | Domain knowledge (schema details, business rules) |
| `skills/*/SKILL.md` | Step-by-step workflows for specific task types |
| `subagents/*.md` | Add / remove / reconfigure specialist subagents |

## Dependencies

| Dependency | Purpose |
|------------|---------|
| `agentscope-harness` | Core framework (HarnessAgent, tools, hooks, workspace) |
| `jackson-dataformat-yaml` | YAML front-matter parsing in skill/subagent files |
| `sqlite-jdbc` | SQLite JDBC driver used by `SqliteTool` |

## License

Apache 2.0 — see the root `LICENSE` file.
