# 共享模型部署配置

[English](README.en.md)

`qwen38-eval.json` 是 AgentScope Java、pi 和 codex 的共同评测配置源，供 Helm 和 `tests/general-agent-eval` 驱动共同读取。模型凭据通过 `MANAGED_MODEL_KEY` 注入，文件中不包含密钥。

- `brain.model.bindings` 非空时，对三种 Harness 都优先于历史的 `brain.pi.modelOptions` 和 `brain.codex.modelBindings`。
- 使用 `helm template/upgrade ... -f <环境配置> -f managed-agents/deploy/profiles/qwen38-eval.json`；环境配置仍负责网络、存储、镜像和各 Harness 的专用 Secret。
- Agent 定义使用 `dashscope:qwen3.8-max`。目录严格匹配别名，不会把拼错或旧的模型名称静默替换；旧 Agent 需显式更新或新建。
- 上下文和输出数值是评测预算。cost 的零值只满足 SDK 的自定义模型注册要求，真实费用以供应商账单为准。
- 模型变更后新建 Session，并保存 profile 与镜像摘要。完整运行步骤见 [评测说明](../../tests/general-agent-eval/README_zh.md)。
