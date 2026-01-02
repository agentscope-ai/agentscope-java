[**English Contributing Guide**](CONTRIBUTING.md)
# 贡献到 AgentScope-Java

## 欢迎！🎉

感谢开源社区对 AgentScope-Java 项目的关注和支持，作为一个开源项目，我们热烈欢迎并鼓励来自社区的贡献。无论是修复错误、添加新功能、改进文档还是
分享想法，这些贡献都能帮助 AgentScope-Java 变得更好。

## 如何贡献

为了确保顺利协作并保持项目质量，请在贡献时遵循以下指南：

### 1. 检查现有计划和问题

在开始贡献之前，请查看我们的开发路线图：

- **查看 [Issue](https://github.com/agentscope-ai/agentscope-java/issues) 页面**
  - **如果存在相关问题** 并且标记为未分配或开放状态：
      - 请在该问题下评论，表达您有兴趣参与该任务
      - 这有助于协调开发工作，避免重复工作

  - **如果不存在相关问题**：
    - 请创建一个新 issue 用以描述对应的更改或功能
    - 我们的团队将及时进行回复并提供反馈
    - 这有助于我们维护项目路线图并协调社区工作

### 2. 提交信息格式

AgentScope 遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范。这使得提交历史更易读，并能够自动生成更新日志。

**格式：**
```
<type>(<scope>): <subject>
```

**类型：**
- `feat:` 新功能
- `fix:` 错误修复
- `docs:` 仅文档更改
- `style:` 不影响代码含义的更改（空格、格式等）
- `refactor:` 既不修复错误也不添加功能的代码更改
- `perf:` 提高性能的代码更改
- `ci:` 添加缺失的测试或更正现有测试
- `chore:` 对构建过程或辅助工具和库的更改

**示例：**
```bash
feat(models): add support for Claude-3 model
fix(agent): resolve memory leak in ReActAgent
docs(readme): update installation instructions
refactor(formatter): simplify message formatting logic
ci(models): add unit tests for OpenAI integration
```

### 3. 代码开发指南

#### a. 代码格式化

在提交代码之前，请确保代码已使用 Spotless 正确格式化：

**检查代码格式：**
```bash
mvn spotless:check
```

**自动修复格式问题：**
```bash
mvn spotless:apply
```

> **提示**：配置您的 IDE（IntelliJ IDEA / Eclipse）在保存时使用项目的代码风格自动格式化代码。

#### b. 单元测试

- 所有新功能都必须包含适当的单元测试
- 在提交 PR 之前确保现有测试通过
- 使用以下命令运行测试：
  ```bash
  # 运行所有测试
  mvn test

  # 运行特定测试类
  mvn test -Dtest=YourTestClassName

  # 运行测试并生成覆盖率报告
  mvn verify
  ```

#### c. 文档

- 为新功能更新相关文档
- 在适当的地方包含代码示例
- 如果更改影响面向用户的功能，请更新 README.md

## 贡献类型

我们欢迎各种类型的贡献！以下是寻找贡献方向的方法：

### 寻找可参与的 Issue

- **新贡献者**：查看标记为 [good first issue](https://github.com/agentscope-ai/agentscope-java/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22good%20first%20issue%22) 的 issue，这些是熟悉代码库的绝佳起点。

- **寻找更多挑战**：浏览标记为 [help wanted](https://github.com/agentscope-ai/agentscope-java/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22help%20wanted%22) 的 issue，这些是我们特别希望得到社区帮助的任务。

### 有新想法？

如果您有新功能、改进建议，或发现了尚未被跟踪的问题，请[创建新的 issue](https://github.com/agentscope-ai/agentscope-java/issues/new) 与社区和维护者讨论。


## Do's and Don'ts

### ✅ DO

- **从小处着手**：从小的、可管理的贡献开始
- **及早沟通**：在实现主要功能之前进行讨论
- **编写测试**：确保代码经过充分测试
- **添加代码注释**：帮助他人理解贡献内容
- **遵循提交约定**：使用约定式提交消息
- **保持尊重**：遵守我们的行为准则
- **提出问题**：如果不确定某事，请提问！

### ❌ DON'T

- **不要用大型 PR 让我们措手不及**：大型的、意外的 PR 难以审查，并且可能与项目目标不一致。在进行重大更改之前，请务必先开启一个问题进行讨论
- **不要忽略 CI 失败**：修复持续集成标记的任何问题
- **不要混合关注点**：保持 PR 专注于单一功能的实现或修复
- **不要忘记更新测试**：功能的更改应反映在测试中
- **不要破坏现有 API**：在可能的情况下保持向后兼容性，或清楚地记录破坏性更改
- **不要添加不必要的依赖项**：保持核心库轻量级

## 获取帮助

如果需要帮助或有疑问：

- 💬 开启一个 [Discussion](https://github.com/agentscope-ai/agentscope-java/discussions)
- 🐛 通过 [Issues](https://github.com/agentscope-ai/agentscope-java/issues) 报告错误
- 📧 通过钉钉交流群或 Discord 联系开发团队（链接在 README.md 中）


---

感谢为 AgentScope-Java 做出贡献！🚀

