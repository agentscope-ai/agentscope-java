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

### d. 维护 LLM 指南

当以下情况发生时需要更新 LLM 指南（`docs/llm/agentscope-llm-guide.md`）：

- 添加新功能（Agent、工具、模型等）
- API 变更或弃用
- 出现新的最佳实践或模式

**建议使用以下 Prompt 来更新 LLM 指南：**

```
请更新 docs/llm/agentscope-llm-guide.md 文件，添加关于 [功能名称] 的内容。

背景信息：
- 新增功能位置：[包路径/类名]
- 主要用途：[简要描述]
- 版本要求：[最低版本号，如 1.0.5+]
- 相关 API 变更：[如果有]
- 依赖的其他功能：[如果有]

文档结构要求：
1. **确定章节位置**（根据功能类型选择合适章节）：
   - 核心功能 → 添加到 "## CORE CONCEPTS" （编号为 ### N. 功能名，如 "### 11. YourFeature"）
   - 使用模式 → 添加到 "## COMMON PATTERNS" （编号为 ### Pattern N: 描述，如 "### Pattern 7: Your Pattern"）
   - 最佳实践 → 添加到 "## BEST PRACTICES" （编号为 ### N. 标题）
   - 常见问题 → 添加到 "## COMMON ISSUES AND SOLUTIONS" （编号为 ### Issue N: 问题描述）
   - 高级用法 → 添加到 "## ADVANCED TOPICS" （适用于自定义扩展等）
   - 如果是新的通用提醒 → 更新 "## IMPORTANT REMINDERS" 章节

2. **代码示例标准**：
   ```java
   import io.agentscope.core.*;  // 完整 import
   import java.util.*;
   
   // Brief description of what this code does
   ClassName obj = ClassName.builder()
       .property(value)              // Comment for non-obvious properties
       .anotherProperty(value2)
       .build();
   
   // Show usage
   Result result = obj.someMethod().block();
   ```

3. **内容组织**：
   - 开头：简短描述（2-3句话）
   - 结构说明：列出关键属性/参数
   - 完整示例：至少一个可运行的完整示例
   - 变体示例：如果有多种用法，提供 2-3 个典型场景
   - 注意事项：说明常见陷阱或最佳实践

4. **技术准确性**：
   - 所有代码必须可编译运行
   - 使用正确的类名和包名
   - 包含必要的错误处理（至少在一个示例中展示）
   - 注释要解释"为什么"而不只是"是什么"

5. **API 参考更新**：
   如果涉及新的 Builder 方法或 API，同时更新 "## API QUICK REFERENCE" 章节中的相应子章节：
   - ReActAgent 的方法 → 更新 "### ReActAgent.Builder Methods"
   - Message 的方法 → 更新 "### Msg.Builder Methods"
   - Model 的方法 → 更新 "### Model Builder Methods"
   - Toolkit 的方法 → 更新 "### Toolkit Methods"
   如果是全新的类，考虑添加新的子章节

6. **示例索引**：
   如果添加了新的使用模式或示例，同时更新 "## EXAMPLES INDEX" 章节，添加该示例的简短描述和编号

7. **版本管理**：
   - 更新文档开头 "### Maven Dependencies" 中的版本号（如果需要）
   - 如果是破坏性变更，考虑添加迁移指南或在相关章节说明

8. **语言和风格**：
   - 使用英文（这是给 AI 代码助手看的文档）
   - 保持简洁专业的技术写作风格
   - 避免模糊表述，使用精确的技术术语
   - 代码注释要有实际价值，不写废话注释
   - 如果功能重要，考虑在文档开头 "## SYSTEM MESSAGE FOR AI ASSISTANTS" 中添加关键提示
```

更新时请注意：

1. 对照实际源代码验证所有代码示例
2. 测试示例确保它们能在当前版本中工作
3. 更新 Maven 依赖部分的版本号
4. 保持示例简洁但完整
5. 在代码片段中包含必要的导入语句


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

