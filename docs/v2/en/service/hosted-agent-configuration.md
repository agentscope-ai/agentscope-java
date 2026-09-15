---
title: "Hosted host and Runtime settings"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Hosted configuration has three layers: Host connectivity/capacity, provider parameters in Runtime Profiles, and Agent instructions/model overrides. Start with [Runtime Host installation](/v2/en/service/runtime-host).

## Connection settings

```bash
agentscope connect https://agentscope.example.com \
  --providers codex,qoder \
  --pool coding-default \
  --capacity 1
agentscope runtime probe
```

This exposes the selected providers. Their CLIs must already be installed, authenticated and executable.

| `connect` option/setting | Meaning |
| --- | --- |
| URL or `--server` | Service HTTP address reachable by the Host |
| `--providers` | `auto` or a comma-separated list: `codex,claude-code,qoder,qwenpaw,openclaw` |
| `--pool` | Runtime Pool name; defaults to `coding-default` |
| `--capacity` | Maximum concurrent Host executions; defaults to 1 |
| `--workspace-root` | Task workspace root |
| `--state-root` | Host identity and durable execution state root |
| `--runtime-host-binary` | Path to `aistio-runtime-host` |
| `AGENTSCOPE_ENROLLMENT_TOKEN` | Short-lived initial enrollment credential |
| `AGENTSCOPE_RUNTIME_TOKEN` | Existing Runtime Host credential |
| `AGENTSCOPE_RUNTIME_CONFIG` | Override local configuration file path |

The default file is `~/.agentscope/runtime-host/config.json`. Its `controlPlane`, `tenant`, `namespace`, `pool`, `capacity`, `workspaceRoot`, `stateRoot` and `providers` fields describe the connection. `credential` is private identity material. Check active work before changing a running Host, then restart and probe again.

## Agent versus Profile

Agent `system` defines responsibilities and `model` provides an optional override. A nonempty Agent model takes precedence over Profile `model`. When both are empty, the provider uses its own default.

`runtimeProfileId` selects provider configuration; `runtimePoolId` selects the Host pool. Most authors select a discovered Runtime in the console while administrators maintain Profiles and pools. Raising Host capacity does not increase model quota or remove Agent/task policy limits.

## Provider parameters

These fields belong to Runtime Profile `configuration`, not to `connect` and not to a universal provider schema.

| Provider | Common fields | Behavior |
| --- | --- | --- |
| Codex | `model`, `profile`, `sandbox`, `reasoningEffort`, `serviceTier` | Starts/resumes app-server threads; default sandbox is `workspace-write`, with adapter-managed approval integration |
| Claude Code | `model`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `appendSystemPrompt`, `reasoningEffort` | Uses supported CLI parameters and provider tool names |
| Qoder | `model`, `reasoningEffort`, `contextWindow`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `appendSystemPrompt`, `agent` | Requires matching installed version and task approval behavior |
| QwenPaw | `agent`, `model`, `permissionMode`, `runtimeProvider`, `localDiagnostics` | Uses ACP Session and permission requests |
| OpenClaw | `model`, `fallbacks`, `thinking`, `codeMode`, `timeoutSeconds`, `localModelLean`, `isolated`, `authEnvOnly`, `reasoningEffort` | Runs `agent exec`; this adapter does not provide MCP or Session resume |

Model identifiers, reasoning levels and account availability depend on the installed provider. Saving a Profile does not install models, authenticate accounts or grant external permissions.

## Definition conflicts

Host translates portable instructions, skills and supported MCP/tool definitions into provider configuration. Unsupported requested capabilities fail before execution. See the [provider matrix](/v2/en/service/hosted-agent-providers).

Custom arguments are argv entries. Reserved arguments controlling workspace, model, output protocol, MCP and permissions cannot be freely overridden. Configure Codex native tools through Profile sandbox/approval settings; Managed built-in tool policies do not transfer to it.
