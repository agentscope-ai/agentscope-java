# Shared model deployment profiles

[中文](README.md)

`qwen38-eval.json` is the shared evaluation configuration for AgentScope Java, pi and codex, read by both Helm and the `tests/general-agent-eval` drivers. Credentials are injected as `MANAGED_MODEL_KEY`; this file contains no secrets.

- Nonempty `brain.model.bindings` takes precedence over legacy `brain.pi.modelOptions` and `brain.codex.modelBindings` for every Harness.
- Use `helm template/upgrade ... -f <environment-values> -f managed-agents/deploy/profiles/qwen38-eval.json`. Environment values still supply networking, storage, images and dedicated Harness Secrets.
- Agent definitions select `dashscope:qwen3.8-max`. Catalog aliases match strictly; old or misspelled model names are not silently remapped. Update or recreate old Agents explicitly.
- Context and output limits are evaluation budgets. Zero cost fields satisfy SDK custom-model registration; use provider billing for actual costs.
- Create new Sessions after model changes and record the profile and image digests. See the [evaluation guide](../../tests/general-agent-eval/README.md) for execution steps.
