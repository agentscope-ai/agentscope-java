# 贡献者

AgentScope Go 欢迎开发者通过代码、文档、示例、问题反馈和使用经验参与项目建设。

## 贡献范围

常见贡献包括：

- 修正文档中的错误、缺失说明或过期示例。
- 补充模型供应商、工具、Sandbox 沙箱和中间件示例。
- 提交可复现的问题反馈，说明版本、环境、输入和实际结果。
- 修复 bug，补充测试，改进错误信息和运行日志。
- 讨论 API 设计、模块边界和兼容性取舍。

## 参与方式

1. 在 GitHub 上查看现有 issue 和 pull request，确认是否已有相同讨论。
2. 对文档或小问题，可以直接提交 pull request。
3. 对 API、运行时行为或兼容性有影响的改动，先提交 issue 描述背景、方案和影响范围。
4. 提交代码前，先配置本地 pre-commit，并运行必要的格式化、测试和文档检查。

## 本地 pre-commit 配置

仓库已经提供 `.pre-commit-config.yaml` 和 `.githook/pre-commit`。首次克隆后运行：

```bash
make setup
make install-tools
```

`make setup` 会安装项目固定版本的 `pre-commit`，并执行：

```bash
git config core.hooksPath .githook
```

配置完成后，普通 `git commit` 会自动触发本地 pre-commit。提交前也可以手动运行全部检查：

```bash
.venv/bin/pre-commit run --all-files
```

pre-commit 当前覆盖通用文件检查、Go lint、拼写检查、密钥扫描、`go mod tidy` 和 Markdown lint。若提交时提示 `pre-commit is not installed in .venv`，重新运行 `make setup`。

## 本地检查

文档改动通常至少运行：

```bash
make docs-lint
make docs-build
```

代码改动通常至少运行：

```bash
make fmt
make test-unit
```

涉及示例、Sandbox 沙箱、权限或模型适配时，请补充对应示例说明和可复现的验证步骤。

## 贡献者列表

完整贡献记录以 GitHub 为准：

- [GitHub Contributors](https://github.com/yuluo-yx/agentscope-go/graphs/contributors)

| GitHub ID / 头像 | 职务 | 任期 |
| --- | --- | --- |
| <img src="https://github.com/yuluo-yx.png?size=64" alt="yuluo-yx" width="32" height="32" style="border-radius: 50%; vertical-align: middle; margin-right: 8px;" /> <a href="https://github.com/yuluo-yx">yuluo-yx</a> | maintainer | 2025 年 5 月至今 |
