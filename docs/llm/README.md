# Using AgentScope Java with AI Code Assistants

This guide explains how to integrate the AgentScope Java framework documentation with AI-powered code editors.

## Supported AI IDEs

- **Cursor** - AI-first code editor
- **Windsurf** - AI coding assistant
- **GitHub Copilot** with custom documentation
- **Continue** in VS Code
- Other editors supporting context documentation

---

## Setup for Cursor

### 1. Add Documentation

1. Open Cursor Settings (⌘/Ctrl + ,)
2. Navigate to **Features** → **Docs**
3. Click **"+ Add new Doc"**
4. Paste the documentation URL:
   ```
   https://raw.githubusercontent.com/agentscope-ai/agentscope-java/main/docs/llm/agentscope-llm-guide.txt
   ```
5. Click **"Add"**

### 2. Use in Code

When writing code, load the documentation by:
1. Type `@` in the chat
2. Select **"Docs"**
3. Choose **"AgentScope Java"** from the list
4. Ask your question or request code generation

### Example Prompts

```
@docs Create a ReActAgent with tools for weather and calculation

@docs Show me how to implement streaming responses

@docs Set up RAG with local documents

@docs Configure long-term memory with Mem0
```

---

## Setup for Windsurf

### 1. Add to Workspace Context

1. Open Windsurf settings
2. Go to AI → Context
3. Add documentation file path or URL
4. Save configuration

### 2. Reference in Prompts

```
Using AgentScope Java framework, create an agent with tool calling capability
```

---

## Setup for GitHub Copilot

### Method 1: Local .copilot/docs

1. Create `.copilot/docs/` directory in your project root
2. Copy `agentscope-llm-guide.txt` to this directory
3. Copilot will automatically index it

### Method 2: Add to .github/copilot-instructions.md

Add a reference to the guide in your repository's Copilot instructions.

---

## Setup for Continue (VS Code Extension)

### 1. Install Continue Extension

Install from VS Code marketplace: [Continue](https://marketplace.visualstudio.com/items?itemName=Continue.continue)

### 2. Configure Context

Edit `.continue/config.json`:

```json
{
  "docs": [
    {
      "title": "AgentScope Java",
      "startUrl": "https://raw.githubusercontent.com/agentscope-ai/agentscope-java/main/docs/llm/agentscope-llm-guide.txt"
    }
  ]
}
```

### 3. Use with @docs Command

Type `@docs AgentScope Java` in Continue chat to load context.

---

## Local Development Setup

### Option 1: Host Locally

If you prefer local access:

1. Clone the repository:
   ```bash
   git clone https://github.com/agentscope-ai/agentscope-java.git
   ```

2. Reference the local file in your IDE settings:
   ```
   file:///path/to/agentscope-java/docs/llm/agentscope-llm-guide.txt
   ```

### Option 2: Project-specific Copy

Copy the guide to your project:

```bash
mkdir -p .copilot/docs
curl -o .copilot/docs/agentscope-guide.txt \
  https://raw.githubusercontent.com/agentscope-ai/agentscope-java/main/docs/llm/agentscope-llm-guide.txt
```

---

## Best Practices

### 1. Be Specific in Prompts

❌ Bad: "Create an agent"
✅ Good: "Create a ReActAgent with DashScope model, weather tool, and streaming enabled"

### 2. Reference Components

```
@docs Create an agent using:
- Model: DashScope qwen-max
- Memory: AutoContextMemory with compression
- Tools: weather and calculation
- Mode: streaming
```

### 3. Iterative Development

Start simple, then add features:
```
1. @docs Create basic ReActAgent
2. @docs Add tool calling capability
3. @docs Enable streaming responses
4. @docs Add long-term memory
```

### 4. Request Explanations

```
@docs Explain how the ReAct loop works

@docs What's the difference between AGENTIC and GENERIC RAG mode?

@docs Show best practices for tool design
```

---

## Example Workflows

### Workflow 1: New Project Setup

```
1. @docs Show minimal AgentScope Java setup with Maven

2. @docs Create a basic chat agent with DashScope

3. @docs Add tool calling for web search and calculation

4. @docs Implement streaming responses
```

### Workflow 2: Add RAG to Existing Agent

```
@docs I have a ReActAgent. Show me how to:
1. Add local knowledge base
2. Configure document chunking
3. Use AGENTIC mode for retrieval
```

### Workflow 3: Multi-Agent System

```
@docs Create a multi-agent system where:
- Agent A generates content
- Agent B reviews it
- Agent C makes final edits
Use SequentialPipeline
```

### Workflow 4: Production Deployment

```
@docs Show production-ready agent with:
- Error handling
- Session persistence
- Logging hooks
- Resource cleanup
```

---

## Troubleshooting

### Documentation Not Loading

**Cursor:**
- Check internet connection for URL-based docs
- Verify URL is correct
- Try re-adding the documentation

**Local Files:**
- Verify file path is correct
- Check file permissions
- Ensure file is readable

### AI Not Using Documentation

1. **Explicitly reference with @docs**
   - Type `@` and select documentation
   
2. **Include framework name in prompt**
   - "Using AgentScope Java framework..."
   
3. **Be specific about components**
   - Mention specific classes like "ReActAgent", "Toolkit"

### Generated Code Has Errors

1. **Check imports**
   - Ensure all imports use `io.agentscope.core.*`
   
2. **Verify Builder pattern usage**
   - All objects use `.builder()...build()`
   
3. **Review reactive programming**
   - Proper use of Mono/Flux and `.block()`

---

## Tips for Better Results

### 1. Provide Context

```
I'm building a customer service chatbot that needs:
- Multi-turn conversations with memory
- Access to FAQ knowledge base  
- Ability to search products
- Session persistence

@docs Show me the agent setup
```

### 2. Ask for Explanations

```
@docs Explain when to use AutoContextMemory vs InMemoryMemory

@docs What are the tradeoffs between RAG modes?
```

### 3. Request Complete Examples

```
@docs Show complete working example of agent with:
- Error handling
- Streaming
- Multiple tools
- Logging
```

### 4. Iterate and Refine

```
@docs Improve this agent code to add:
- Better error handling
- Resource cleanup
- Tool execution logging
```

---

## Documentation Updates

The LLM guide is maintained in the AgentScope Java repository:
- **Location**: `docs/llm/agentscope-llm-guide.txt`
- **Updates**: Synchronized with framework releases
- **Issues**: Report via [GitHub Issues](https://github.com/agentscope-ai/agentscope-java/issues)

To get the latest version:
```bash
curl -O https://raw.githubusercontent.com/agentscope-ai/agentscope-java/main/docs/llm/agentscope-llm-guide.txt
```

---

## Contributing

Help improve the LLM guide:

1. **Report Issues**: Missing information, errors, unclear explanations
2. **Suggest Examples**: Common use cases that should be documented
3. **Contribute**: Submit PRs with improvements

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines.

---

## Additional Resources

- **Full Documentation**: https://java.agentscope.io/
- **GitHub Repository**: https://github.com/agentscope-ai/agentscope-java
- **Examples**: Check `agentscope-examples/` in the repository
- **Discord Community**: https://discord.gg/eYMpfnkG8h

---

## FAQ

**Q: Does this work with all AI assistants?**
A: Most modern AI code assistants support custom documentation. The exact setup varies by tool.

**Q: Can I modify the guide for my team?**
A: Yes! Fork the repository and customize. Consider contributing improvements back.

**Q: How often is the guide updated?**
A: The guide is updated with each framework release and when significant features are added.

**Q: What if the AI generates incorrect code?**
A: Always review generated code. Report persistent issues to improve the guide.

**Q: Can I use this offline?**
A: Yes, download the guide locally and reference it in your IDE settings.

---

Happy coding with AgentScope Java! 🚀
