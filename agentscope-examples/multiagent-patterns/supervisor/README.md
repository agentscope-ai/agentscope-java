# Supervisor Personal Assistant Example

This example implements the **supervisor** pattern with **AgentScope**. A central supervisor ReActAgent coordinates specialized agents (calendar and email) by calling them as **tools** via `Toolkit.registration().subAgent()`.

## Architecture

- **Supervisor (main agent)**  
  Receives user requests, decides which specialized agent(s) to call, and synthesizes results. It only sees high-level tools: `schedule_event` and `manage_email`.

- **Calendar agent**  
  Handles scheduling: parses natural language (e.g. "next Tuesday at 2pm"), checks availability, and creates events. Exposed to the supervisor as the tool `schedule_event` with a single string input (the user's scheduling request).

- **Email agent**  
  Handles email: composes and sends messages from natural language. Exposed as the tool `manage_email` with a single string input (the user's email request).

Specialized agents are **stateless** from the user's perspective; the supervisor keeps the conversation and delegates one-off tasks to them. Each specialized agent runs in a focused context (its own instruction + the request string).

## Design choices

1. **Specialized agents as tools**  
   Calendar and email agents are AgentScope ReActAgents registered with `Toolkit.registration().subAgent()` so the supervisor invokes them as tools (`schedule_event`, `manage_email`).

2. **AgentScope Model**  
   All agents use **DashScopeChatModel** (AgentScope `Model`). API key from `spring.ai.dashscope.api-key` or `AI_DASHSCOPE_API_KEY`.

3. **Tool-per-agent**  
   One tool per specialized agent for clear routing and descriptions.

4. **Stub APIs**  
   Calendar and email "API" calls are stubbed in `CalendarStubTools` and `EmailStubTools` (AgentScope `@Tool`). Replace with real integrations in production.

## Project layout

```
agentscope-examples/multiagent-patterns/supervisor/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../supervisor/
    │   ├── SupervisorApplication.java      # Spring Boot entry
    │   ├── SupervisorConfig.java           # Model, calendarAgent, emailAgent, supervisorAgent (ReActAgent)
    │   ├── SupervisorRunner.java           # Optional demo runner (supervisor.run-examples=true)
    │   └── tools/
    │       ├── CalendarStubTools.java      # create_calendar_event, get_available_time_slots (@Tool)
    │       └── EmailStubTools.java         # send_email (@Tool)
    └── resources/
        └── application.yml
```

## How to run

### Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key** for the chat model (used by both supervisor and specialized agents).

Set your API key:

```bash
export AI_DASHSCOPE_API_KEY=your-dashscope-api-key
```

### Build

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/supervisor -am -B package -DskipTests
```

Or from this directory:

```bash
cd agentscope-examples/multiagent-patterns/supervisor
mvn -B package -DskipTests
```

### Run the application

Default: the app starts **without** calling the model (no demo run):

```bash
java -jar target/supervisor-*.jar
# or
./mvnw -pl agentscope-examples/multiagent-patterns/supervisor spring-boot:run
```

To run the **two demo scenarios** on startup (same as in the reference doc):

1. **Single-domain**: "Schedule a team standup for tomorrow at 9am" (calendar only).  
2. **Multi-domain**: "Schedule a meeting with the design team next Tuesday at 2pm for 1 hour, and send them an email reminder about reviewing the new mockups." (calendar + email).

Set:

```bash
export supervisor.run-examples=true
# or add to application.yml: supervisor.run-examples: true
```

Then start the app as above. The runner will call the supervisor with these two user messages and log the assistant replies.

### Using the supervisor in your own code

Inject the supervisor ReActAgent and call it with a user `Msg`:

```java
@Qualifier("supervisorAgent")
@Autowired
ReActAgent supervisorAgent;

Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("Schedule a meeting tomorrow at 10am").build();
Msg response = supervisorAgent.call(userMsg).block();
String text = response != null ? response.getTextContent() : "";
```

## Configuration

- **`spring.ai.dashscope.api-key`**  
  Required for the chat model (supervisor and specialized agents). Defaults to `AI_DASHSCOPE_API_KEY` env var.

- **`supervisor.run-examples`**  
  If `true`, runs the two demo scenarios on startup. Default: `false`.

## Example flow (multi-domain request)

1. User: "Schedule a meeting with the design team next Tuesday at 2pm for 1 hour, and send them an email reminder about reviewing the new mockups."
2. Supervisor decides to call two tools: `schedule_event` and `manage_email`.
3. **schedule_event** (calendar agent): receives the scheduling part, may call stub tools `get_available_time_slots` and `create_calendar_event`, returns a short confirmation text.
4. **manage_email** (email agent): receives the email part, calls stub `send_email`, returns a short confirmation text.
5. Supervisor combines both confirmations and replies to the user.

This mirrors the reference example's flow: supervisor routes to specialized agents, each agent runs in isolation with its own instruction and tools, and only the final assistant message is shown to the user.
