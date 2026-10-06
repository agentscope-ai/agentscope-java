---
title: "Hosted providers and capability differences"
zh_link: /v2/zh/service/hosted-agent-providers
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Runtime Host includes five provider adapters. `runtime probe` checks executable discovery; real work also needs valid login, models and tools.

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

## Choosing a provider

Check the columns for persistent sessions, approvals and Subagents before choosing. Review tool policy support before reusing a definition designed for Managed Agents. For example, Codex supports MCP while rejecting Managed built-in tool policies.

Teams can mix providers. Verify each member's independent execution and collaboration tools before testing Leader delegation and final delivery. Native Subagents and Service Teams operate at different levels.

Next: [configuration](/v2/en/service/hosted-agent-configuration) and [execution model](/v2/en/service/hosted-agent-execution).

## Read capabilities reported by current Hosts

The tables explain mappings; actual availability comes from Host reports. Query with a platform account Bearer token:

```bash
curl -sS "$SERVICE_URL/api/v1/agents/runtime-options?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

Select an entry in `runtimes`. Each entry provides `provider`, `version`, `runtimeProfileId`, `runtimePoolId`, `hostCount`, and `capabilities`. The descriptor contains `instructions`, `workspace`, `skills`, `subagents`, `tools`, `shell`, `mcp`, `model`, `customArgs`, `approval`, and `resume`. `resume` is a boolean; other capability entries use `supported`, `mode`, and `target` to describe support and its mapping.

`GET /api/v1/runtime-hosts?tenant=...&namespace=...` returns per-Host provider versions/descriptors in `items[].capabilities`. Put the selected profile/pool UUIDs in the Agent binding. Update per-Agent provider options through `/api/v1/agents/{agentId}/hosted-settings`; see [configuration](/v2/en/service/hosted-agent-configuration) for fields.

Provider `resume` describes native Session recovery. Unified Agent API `capabilities.resume` describes invocation-level command support. Query Endpoint/invocation capabilities for business interactions instead of inferring them from provider features.
