# Contributing to AgentScope

## Welcome! 🎉

Thank you for your interest in contributing to AgentScope! As an open-source project, we warmly welcome and encourage
contributions from the community. Whether you're fixing bugs, adding new features, improving documentation, or sharing
ideas, your contributions help make AgentScope better for everyone.

## How to Contribute

To ensure smooth collaboration and maintain the quality of the project, please follow these guidelines when contributing:

### 1. Check Existing Plans and Issues

Before starting your contribution, please review our development roadmap:

- **Check the [Issue](https://github.com/agentscope-ai/agentscope-java/issues) page**
    - **If a related issue exists** and is marked as unassigned or open:
    - Please comment on the issue to express your interest in working on it
    - This helps avoid duplicate efforts and allows us to coordinate development

  - **If no related issue exists**:
    - Please create a new issue describing your proposed changes or feature
    - Our team will respond promptly to provide feedback and guidance
    - This helps us maintain the project roadmap and coordinate community efforts

### 2. Commit Message Format

We follow the [Conventional Commits](https://www.conventionalcommits.org/) specification. This leads to more readable
commit history and enables automatic changelog generation.

**Format:**
```
<type>(<scope>): <subject>
```

**Types:**
- `feat:` A new feature
- `fix:` A bug fix
- `docs:` Documentation only changes
- `style:` Changes that do not affect the meaning of the code (white-space, formatting, etc)
- `refactor:` A code change that neither fixes a bug nor adds a feature
- `perf:` A code change that improves performance
- `ci:` Adding missing tests or correcting existing tests
- `chore:` Changes to the build process or auxiliary tools and libraries

**Examples:**
```bash
feat(models): add support for Claude-3 model
fix(agent): resolve memory leak in ReActAgent
docs(readme): update installation instructions
refactor(formatter): simplify message formatting logic
ci(models): add unit tests for OpenAI integration
```

### 3. Code Development Guidelines

#### a. Code Formatting

Before submitting code, you must ensure code is properly formatted using Spotless:

**Check code format:**
```bash
mvn spotless:check
```

**Auto-fix format issues:**
```bash
mvn spotless:apply
```

> **Tip**: Configure your IDE (IntelliJ IDEA / Eclipse) to format code on save using the project's code style.

#### b. Unit Tests

- All new features must include appropriate unit tests
- Ensure existing tests pass before submitting your PR
- Run tests using:
  ```bash
  # Run all tests
  mvn test

  # Run a specific test class
  mvn test -Dtest=YourTestClassName

  # Run tests with coverage report
  mvn verify
  ```

#### c. Documentation

- Update relevant documentation for new features
- Include code examples where appropriate
- Update the README.md if your changes affect user-facing functionality


## Types of Contributions

### Adding New Chat Models

AgentScope currently supports the following API providers at the chat model level: **OpenAI**, **DashScope**,
**Gemini**, **Anthropic**, and **Ollama**. These APIs are compatible with various service providers including vLLM,
DeepSeek, SGLang, and others.

**⚠️ Important Notice:**

Adding a new chat model is not merely a model-level task. It involves multiple components including:
- Message formatters
- Token counters
- Tools API integration

This is a substantial amount of work. To better focus our efforts on agent capability development and maintenance,
**the official development team currently does not plan to add support for new chat model APIs**. However, when there
is a strong need from the developer community, we will do our best to accommodate these requirements.

**If you wish to contribute a new chat model**, here are the components needed to be compatible with the
existing `ReActAgent` in the repository:

#### Required Components:

1. **Chat Model Class** (under `io.agentscope.model`):
   ```java
   package io.agentscope.model;

   public class YourChatModel extends ChatModelBase {
       /**
        * The functionalities that you need to consider include:
        * - Tools API integration
        * - Both streaming and non-streaming modes (compatible with tools API)
        * - tool_choice argument
        * - reasoning models
        */
   }
   ```

2. **Formatter Class** (under `io.agentscope.formatter`):
   ```java
   package io.agentscope.formatter;

   public class YourModelFormatter extends FormatterBase {
       /**
        * Convert Msg objects into the format required by your API provider.
        * If your API doesn't support multi-agent scenarios (e.g. doesn't support
        * the name field in messages), you need to implement two separate formatter
        * classes for chatbot and multi-agent scenarios.
        */
   }
   ```

3. **Token Counter** (under `io.agentscope.token`, recommended):
   ```java
   package io.agentscope.token;

   public class YourTokenCounter extends TokenCounterBase {
       /**
        * Implement token counting logic for your model.
        * This is recommended but not strictly required.
        */
   }
   ```

### Adding New Agents

To achieve true modularity, the `io.agentscope.agent` package currently aims to maintain only the **`ReActAgent`** class
as the core implementation. We ensure all functionalities in this class are **modular, detachable, and composable**.

In AgentScope, we follow an examples-first development workflow: prototype new implementations in the `agentscope-examples/`
module, then abstract and modularize the functionality, and finally integrate it into the core library.

For specialized or domain-specific agents, we recommend contributing them to the **`agentscope-examples`** module:

```
agentscope-examples/
└── your-example/
    ├── pom.xml
    ├── src/main/java/
    │   └── io/agentscope/examples/
    │       └── YourAgent.java
    └── README.md  # Explain the agent's purpose and usage
```

### Adding New Examples

We highly encourage contributions of new examples that showcase the capabilities of AgentScope.
Please add them to the `agentscope-examples/` module with a clear README explaining the purpose and usage of the example.

Our examples are organized as Maven submodules:

- `agentscope-examples/quickstart/` for getting started examples
- `agentscope-examples/advanced/` for advanced usage examples
- `agentscope-examples/werewolf/` for game-related examples

An example structure could be:

```
agentscope-examples/
└── {example_name}/
    ├── pom.xml
    ├── src/
    │   └── main/
    │       ├── java/
    │       │   └── io/agentscope/examples/{example_name}/
    │       │       └── Main.java
    │       └── resources/
    │           └── logback.xml
    └── README.md  # Explain the example's purpose and usage
```


## Do's and Don'ts

### ✅ DO:

- **Start small**: Begin with small, manageable contributions
- **Communicate early**: Discuss major changes before implementing them
- **Write tests**: Ensure your code is well-tested
- **Document your code**: Help others understand your contributions
- **Follow commit conventions**: Use conventional commit messages
- **Be respectful**: Follow our Code of Conduct
- **Ask questions**: If you're unsure about something, just ask!

### ❌ DON'T:

- **Don't surprise us with big pull requests**: Large, unexpected PRs are difficult to review and may not align with project goals. Always open an issue first to discuss major changes
- **Don't ignore CI failures**: Fix any issues flagged by continuous integration
- **Don't mix concerns**: Keep PRs focused on a single feature or fix
- **Don't forget to update tests**: Changes in functionality should be reflected in tests
- **Don't break existing APIs**: Maintain backward compatibility when possible, or clearly document breaking changes
- **Don't add unnecessary dependencies**: Keep the core library lightweight

## Getting Help

If you need assistance or have questions:

- 💬 Open a [Discussion](https://github.com/agentscope-ai/agentscope-java/discussions)
- 🐛 Report bugs via [Issues](https://github.com/agentscope-ai/agentscope-java/issues)
- 📧 Contact the maintainers at DingTalk or Discord (links in the README.md)


---

Thank you for contributing to AgentScope! Your efforts help build a better tool for the entire community. 🚀
