[**中文贡献者指南**](CONTRIBUTING_zh.md)
# Contributing to AgentScope-Java

## Welcome! 🎉

Thank you for your interest in contributing to AgentScope-Java! As an open-source project, we warmly welcome and encourage
contributions from the community. Whether you're fixing bugs, adding new features, improving documentation, or sharing
ideas, your contributions help make AgentScope-Java better for everyone.

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

We welcome all kinds of contributions! Here's how to find something to work on:

### Finding Issues to Work On

- **New contributors**: Check out issues labeled [good first issue](https://github.com/agentscope-ai/agentscope-java/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22good%20first%20issue%22) - these are great starting points for getting familiar with the codebase.

- **Looking for more challenges**: Browse issues labeled [help wanted](https://github.com/agentscope-ai/agentscope-java/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22) - these are tasks where we'd especially appreciate community help.

### Have a New Idea?

If you have ideas for new features, improvements, or find bugs that aren't already tracked, please [create a new issue](https://github.com/agentscope-ai/agentscope-java/issues/new) to discuss with the community and maintainers.


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

Thank you for contributing to AgentScope-Java! Your efforts help build a better tool for the entire community. 🚀
