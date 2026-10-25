---
title: "Hosted Agent configuration"
zh_link: /v2/zh/service/hosted-agent-configuration
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

If you have not connected a Hosted Agent yet, follow [Connect a Hosted Agent](/v2/en/service/connect-hosted-agent) to bring the execution host online and create an Agent. Use this page to adjust configuration after connection, and [Runtime Host setup and operations](/v2/en/service/runtime-host) for installation and ongoing maintenance.

Before changing a setting, identify the scope it should affect. Host connection settings determine which Service the host connects to, which providers it exposes, and how many executions it allows concurrently. A Runtime Profile stores a provider's runtime parameters, while an Agent adds its own instructions and model overrides. Keeping settings at the appropriate scope lets several Agents share an execution environment while retaining their individual behavior.

## Connection settings

```bash
as connect https://agentscope.example.com \
  --providers codex,qoder \
  --pool coding-default \
  --capacity 1
as runtime probe
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
| `--runtime-host-binary` | Path to `agentscope-runtime-host` |
| `AGENTSCOPE_ENROLLMENT_TOKEN` | Short-lived initial enrollment credential |
| `AGENTSCOPE_RUNTIME_TOKEN` | Existing Runtime Host credential |
| `AGENTSCOPE_RUNTIME_CONFIG` | Override local configuration file path |

The default file is `~/.agentscope/runtime-host/config.json`. Its `controlPlane`, `tenant`, `namespace`, `pool`, `capacity`, `workspaceRoot`, `stateRoot` and `providers` fields describe the connection. `credential` is private identity material. Check active work before changing a running Host, then restart and probe again.

## Agent versus Profile

Agent `system` defines responsibilities and `model` provides an optional override. A nonempty Agent model takes precedence over Profile `model`. When both are empty, the provider uses its own default.

`runtimeProfileId` selects provider configuration; `runtimePoolId` selects the Host pool. Agent authors select a discovered Runtime through `runtime-options`; administrators maintain Profiles and pools. Raising Host capacity does not increase model quota or remove Agent/task policy limits.

## Agent and runtime configuration APIs

Use a platform account Bearer token authorized for the target namespace. See [connect and create an Agent](/v2/en/service/connect-hosted-agent) for a complete creation request.

| Operation | API | Key request and response fields |
| --- | --- | --- |
| Discover runtimes | `GET /api/v1/agents/runtime-options?tenant=...&namespace=...` | Returns `runtimes`, `profiles`, `pools`; options include `provider`, `runtimeProfileId`, `runtimePoolId`, `hostCount`, `capabilities` |
| Create Hosted Agent | `POST /api/v1/agents` | `agentKey`, scope, `binding.kind: "hosted-runtime"`, `binding.configuration`, and `definition`; returns `agent`, `binding`, `policy`, `definition` |
| Read definition | `GET /api/v1/agents/{agentId}/definition` | `agentId`, `definition`; retain definition `version` |
| Update definition | `PATCH /api/v1/agents/{agentId}/definition` | Requires `name` and current `version` with the definition to save; returns `agent`, `definition` |
| Read runtime settings | `GET /api/v1/agents/{agentId}/hosted-settings` | `settings` with binding, profile/pool, overrides, concurrency, and both version numbers |
| Update runtime settings | `PATCH /api/v1/agents/{agentId}/hosted-settings` | Fields below; returns updated `settings` |
| Read definition history | `GET /api/v1/agents/{agentId}/versions`, `/versions/{version}` | Saved versions or an individual version |

Creation requires `runtimeProfileId` and `runtimePoolId` in `binding.configuration`, in the same scope, with optional `executionOverrides`. The portable `definition` uses the Managed format: commonly `name`, `system`, `model`, `tools`, `mcpServers`, `skills`, `workspaceId`, and `workspaceBinding`. Provider capabilities determine how these fields take effect. Read the complete definition before updating it; do not assume PATCH preserves every omitted field as a shallow merge.

### Hosted settings fields

| Field | Meaning |
| --- | --- |
| `bindingVersion` | Required current binding version for concurrent update checks |
| `policyVersion` | Return the current policy version to protect against concurrent changes |
| `runtimeProfileId`, `runtimePoolId` | Optional new runtime configuration/pool; omission retains the current value |
| `executionOverrides` | Optional `reasoningEffort`, `serviceTier`, `providerConfiguration`, `customArgs`; omission retains current overrides |
| `maxConcurrency` | Optional 0–50; 0 leaves the Agent-level limit unset, while Host and other limits still apply |

`providerConfiguration` is an object and `customArgs` is an argv string array. Provider validation rejects invalid or reserved options. Prefer per-Agent overrides for individual preferences instead of changing a shared Profile.

This example updates only concurrency. `AGENT_ID` is an existing Agent UUID; `SERVICE_URL` and `TOKEN` configure management access:

```bash
curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/hosted-settings" \
  -H "Authorization: Bearer $TOKEN" > hosted-settings.json

jq '{bindingVersion: .settings.bindingVersion,
     policyVersion: .settings.policyVersion,
     maxConcurrency: 2}' hosted-settings.json > hosted-settings-update.json

curl -sS -X PATCH "$SERVICE_URL/api/v1/agents/$AGENT_ID/hosted-settings" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @hosted-settings-update.json
```

On a version conflict, read the current settings, merge your changes, and retry.

### Manage shared Profiles and Pools

`GET /api/v1/runtime-profiles` and `/runtime-pools` accept `tenant` and `namespace` and return `items`. Read individual resources through `/runtime-profiles/{name}` or `/runtime-pools/{name}`, returning `profile` or `pool`.

| Save API | JSON fields |
| --- | --- |
| `POST /api/v1/runtime-profiles` or `PUT /api/v1/runtime-profiles/{name}` | `tenant`, `namespace`, `name`, `provider`; optional `runtime`, `configuration`, `requirements` |
| `POST /api/v1/runtime-pools` or `PUT /api/v1/runtime-pools/{name}` | `tenant`, `namespace`, `name`; optional `hostSelector`, `configuration` |

For PUT, the path name selects the resource. Submit the complete desired configuration and retain returned UUIDs for Agent bindings. See [Runtime Host APIs](/v2/en/service/runtime-host#host-enrollment-and-management-apis) for enrollment, capacity, and pausing new work.

## Provider parameters

These fields belong to Runtime Profile `configuration`, not to `connect` and not to a universal provider schema.

| Provider | Common fields | Behavior |
| --- | --- | --- |
| Codex | `model`, `profile`, `sandbox`, `reasoningEffort`, `serviceTier`, `skipGitRepoCheck` | Starts/resumes app-server threads; default sandbox is `workspace-write`, with adapter-managed approval integration |
| Claude Code | `model`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `appendSystemPrompt`, `reasoningEffort` | Uses supported CLI parameters and provider tool names |
| Qoder | `model`, `reasoningEffort`, `contextWindow`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `maxOutputTokens`, `strictMCPConfig`, `appendSystemPrompt`, `agent` | Requires matching installed version and task approval behavior |
| QwenPaw | `agent`, `model`, `permissionMode`, `runtimeProvider`, `localDiagnostics` | Uses ACP Session and permission requests |
| OpenClaw | `model`, `fallbacks`, `thinking`, `codeMode`, `timeoutSeconds`, `localModelLean`, `isolated`, `authEnvOnly`, `reasoningEffort` | Runs `agent exec`; this adapter does not provide MCP or Session resume |

Model identifiers, reasoning levels and account availability depend on the installed provider. Saving a Profile does not install models, authenticate accounts or grant external permissions.

## Definition conflicts

Host translates portable instructions, skills and supported MCP/tool definitions into provider configuration. Unsupported requested capabilities fail before execution. See the [provider matrix](/v2/en/service/hosted-agent-configuration#hosted-agent-providers).

Custom arguments are argv entries. Reserved arguments controlling workspace, model, output protocol, MCP and permissions cannot be freely overridden. Configure Codex native tools through Profile sandbox/approval settings; Managed built-in tool policies do not transfer to it.

<span id="hosted-agent-providers"></span>
<span id="choosing-a-provider"></span>

## Runtime types

| Console name | `--providers` value | Default executable | Execution |
| --- | --- | --- | --- |
| Codex | `codex` | `codex` | app-server threads and events |
| Claude Code | `claude-code` | `claude` | Streaming JSON CLI |
| Qoder | `qoder` | `qodercli` | Streaming CLI events and control requests |
| QwenPaw | `qwenpaw` | `qwenpaw` | ACP Session |
| OpenClaw | `openclaw` | `openclaw` | `agent exec` |

Install and authenticate the provider before connecting Host. These are Service adapters; provider binaries are not bundled in the Service CLI release.

## Portable definition mapping

| Provider | Instructions / skills | MCP | Managed-style built-in tool policy | Platform Subagent definition mapping | Resume |
| --- | --- | --- | --- | --- | --- |
| Codex | Developer instructions / `.agents/skills` | Yes | No; use native sandbox/approval | Shared workspace supported | Yes |
| Claude Code | `CLAUDE.md` / `.claude/skills` | Yes | Allow/deny lists | Not currently advertised | Yes |
| Qoder | Prompt / definition skill directory | Yes | Allow/deny lists | Shared workspace supported | Yes |
| QwenPaw | Prompt / `skills` | Yes | Native policy | Not currently advertised | Yes |
| OpenClaw | Prompt / `skills` | No | Native policy | Not currently advertised | No |

Support indicates an implemented adapter path; installed CLI version and account capabilities still matter. Native Subagent mapping requires Codex 0.153.4+ or Qoder 1.0.37+ and currently requires shared workspaces. Codex mapping cannot enforce Subagent `tools` or `maxIters`. Qoder tool names must map to supported native tools.

Codex, Qoder and QwenPaw provide control-plane tool approval integration. Claude Code uses its CLI permission configuration; this does not promise the same Inbox approval flow. OpenClaw uses the task CLI through Shell for collaboration because its adapter does not inject MCP.

## Read capabilities reported by current Hosts

The tables explain mappings; actual availability comes from Host reports. Query with a platform account Bearer token:

```bash
curl -sS "$SERVICE_URL/api/v1/agents/runtime-options?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

Select an entry in `runtimes`. Each entry provides `provider`, `version`, `runtimeProfileId`, `runtimePoolId`, `hostCount`, and `capabilities`. The descriptor contains `instructions`, `workspace`, `skills`, `subagents`, `tools`, `shell`, `mcp`, `model`, `customArgs`, `approval`, and `resume`. `resume` is a boolean; other capability entries use `supported`, `mode`, and `target` to describe support and its mapping.

`GET /api/v1/runtime-hosts?tenant=...&namespace=...` returns per-Host provider versions/descriptors in `items[].capabilities`. Put the selected profile/pool UUIDs in the Agent binding. Update per-Agent provider options through `/api/v1/agents/{agentId}/hosted-settings`; see [configuration](/v2/en/service/hosted-agent-configuration) for fields.

Provider `resume` describes native runtime recovery, which does not automatically provide a public Turn resume command. Applications should read Session and Turn capabilities and display commands only when `available_commands` includes them.
