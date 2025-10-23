# AgentScope Java Examples

This directory contains examples demonstrating core features of AgentScope Java framework.

## 🚀 Quick Start

### Prerequisites

- **JDK 17** or higher
- **Maven 3.6+**
- **DashScope API Key** - Get one at https://dashscope.console.aliyun.com/apiKey

### Build Examples

```bash
# From project root, build and install the main library
cd agentscope-core-java
mvn clean install

# Build examples
cd examples
mvn compile
```

### Environment Setup

Set your API key (optional - examples will prompt for it if not set):

```bash
export DASHSCOPE_API_KEY=your_api_key_here
```

## 📚 Examples Overview

| Example | Description | Core Concepts | Run Command |
|---------|-------------|---------------|-------------|
| **BasicChatExample** | Simplest agent conversation | Agent, Model, Memory | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample"` |
| **ToolCallingExample** | Equipping agents with tools | @Tool, Toolkit, Tool calling | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolCallingExample"` |
| **ToolGroupExample** | Autonomous tool group management | Meta-tool, Tool groups, Self-activation | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolGroupExample"` |
| **McpToolExample** | MCP tool server integration | MCP, External tools | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.McpToolExample"` |
| **HookExample** | Monitoring agent execution | Hook, Lifecycle callbacks | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.HookExample"` |
| **StreamingWebExample** | Spring Boot + SSE streaming | Web API, Real-time streaming | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.StreamingWebExample"` |
| **SessionExample** | Persistent conversations | JsonSession, State management | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.SessionExample"` |
| **InterruptionExample** | Agent interruption mechanism | User interruption, Recovery | `mvn exec:java -Dexec.mainClass="io.agentscope.examples.InterruptionExample"` |

## 📖 Detailed Examples

### 1. BasicChatExample

The simplest way to create and chat with an agent.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample"
```

**What you'll learn:**
- Creating a ReActAgent
- Configuring Model, Memory, and Formatter
- Interactive conversation

**Try asking:**
- "Hello, introduce yourself"
- "What can you help me with?"

---

### 2. ToolCallingExample

Learn how to give agents access to tools.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolCallingExample"
```

**What you'll learn:**
- Defining tools with `@Tool` annotation
- Registering tools to Toolkit
- Agent automatically calling tools

**Try asking:**
- "What time is it in Tokyo?"
- "Calculate 123 * 456"
- "Search for 'artificial intelligence'"

---

### 3. ToolGroupExample

Agent autonomously managing tool groups using meta-tool.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.ToolGroupExample"
```

**What you'll learn:**
- Creating tool groups to organize tools
- Agent autonomously activating tool groups using `reset_equipped_tools` meta-tool
- Agent deciding which tools to activate based on task requirements

**How it works:**
- All tool groups start as **INACTIVE**
- The agent has access to the `reset_equipped_tools` meta-tool
- When you give the agent a task, it will:
  1. Determine which tool groups are needed
  2. Call `reset_equipped_tools` to activate those groups
  3. Use the tools from the activated groups

**Example prompts to try:**

1. **Single tool group activation:**
   ```
   You> Calculate the factorial of 5
   ```
   Watch: Agent activates `math_ops`, then uses `factorial` tool

2. **Different tool group:**
   ```
   You> Ping google.com
   ```
   Watch: Agent activates `network_ops`, then uses `ping` tool

3. **Another tool group:**
   ```
   You> List files in /tmp
   ```
   Watch: Agent activates `file_ops`, then uses `list_files` tool

4. **Multiple tool groups in one task:**
   ```
   You> Calculate factorial of 7 and then ping github.com
   ```
   Watch: Agent activates both `math_ops` and `network_ops`

5. **Complex multi-group task:**
   ```
   You> Check if 17 is prime, then list files in /tmp
   ```
   Watch: Agent activates `math_ops` and `file_ops`

This example demonstrates **autonomous tool management** - the agent intelligently decides which tools to enable based on your request!

---

### 4. McpToolExample

Connect to external tool servers using Model Context Protocol (MCP).

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.McpToolExample"
```

**Prerequisites:**
Install an MCP server:
```bash
npm install -g @modelcontextprotocol/server-filesystem
```

**What you'll learn:**
- Connecting to MCP servers (StdIO, SSE, HTTP)
- Using external tools from MCP servers
- Interactive MCP configuration

**Try asking:**
- "List files in /tmp"
- "Read the content of /tmp/test.txt"

---

### 5. HookExample

Monitor and intercept agent execution in real-time.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.HookExample"
```

**What you'll learn:**
- Complete Hook lifecycle callbacks
- Streaming output monitoring
- Tool execution tracking
- ToolEmitter for progress updates

**Try asking:**
- "Process the customer dataset"

You'll see detailed logs of:
- Agent start
- Reasoning chunks (streaming)
- Tool calls and results
- Progress updates
- Completion

---

### 6. StreamingWebExample

Spring Boot web application with Server-Sent Events (SSE) streaming.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.StreamingWebExample"
```

**What you'll learn:**
- Building a Spring Boot REST API with reactive endpoints
- Real-time streaming with Server-Sent Events (SSE)
- Hook-based response collection for streaming
- Session persistence in web environment

**How to use:**
After starting the server, open your browser or use curl:

```bash
# Simple query
curl -N "http://localhost:8080/chat?message=Hello"

# With session persistence
curl -N "http://localhost:8080/chat?message=What%20is%20AI?&sessionId=my-session"

# Or open in browser
http://localhost:8080/chat?message=Hello
```

You'll see the agent's response streaming in real-time, character by character.

---

### 7. SessionExample

Maintain persistent conversation history across runs.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.SessionExample"
```

**What you'll learn:**
- Using JsonSession for persistence
- Saving/loading conversation state
- Session management

**Try this flow:**
```
# First run
Enter session ID: alice_session
You> My name is Alice and I love pizza

# Second run (same session ID)
Enter session ID: alice_session
You> What's my name and what do I like?
Agent> Your name is Alice and you love pizza!
```

---

### 8. InterruptionExample

Gracefully interrupt long-running agent tasks.

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.InterruptionExample"
```

**What you'll learn:**
- User-initiated interruption
- Cooperative interruption mechanism
- Fake tool results generation
- Graceful recovery

The example automatically demonstrates interruption by starting a long task and interrupting it after 2 seconds.

---

## 🛠️ Common Operations

### Running a Specific Example

```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample"
```

### Debugging Examples

Add debug logging:
```bash
mvn exec:java -Dexec.mainClass="io.agentscope.examples.BasicChatExample" \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug
```

### Code Formatting

```bash
mvn spotless:apply
```

## 📝 API Key Configuration

Examples support two ways to provide API keys:

1. **Environment Variable** (recommended):
   ```bash
   export DASHSCOPE_API_KEY=your_key_here
   mvn exec:java -Dexec.mainClass="..."
   ```

2. **Interactive Input**:
   If environment variable is not set, examples will prompt you to enter the API key.

## 🤔 Troubleshooting

### "DASHSCOPE_API_KEY not found"

Set the environment variable:
```bash
export DASHSCOPE_API_KEY=sk-xxx
```

Or the example will prompt you to enter it interactively.

### MCP Server Connection Failed

For McpToolExample, ensure the MCP server is installed:
```bash
# For filesystem server
npm install -g @modelcontextprotocol/server-filesystem

# For git server
npm install -g @modelcontextprotocol/server-git
```

### Compilation Errors

Make sure you've built the main library first:
```bash
cd /path/to/agentscope-core-java
mvn clean install
```

## 📚 Additional Resources

- [AgentScope Documentation](https://github.com/modelscope/agentscope)
- [API Reference](../docs/)
- [CLAUDE.md](../CLAUDE.md) - Development guidelines

## 💡 Contributing

When adding new examples:

1. Keep each example focused on a single feature
2. Add clear documentation and comments
3. Include interactive prompts for configuration
4. Follow the existing code style
5. Run `mvn spotless:apply` before committing

## 📄 License

Apache License 2.0 - See [LICENSE](../LICENSE) for details.
