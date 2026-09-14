---
title: "Managed Agents: hosted execution and extensions"
---

[简体中文](/v2/zh/service/managed-agent)

Service manages a Managed Agent's Harness, Sessions and model execution. Configure its responsibilities, model and resources without deploying a separate application for each Agent. Use it for knowledge work, document processing and tasks supported by platform tools.

## Prepare and create

Complete [installation](/v2/en/service/quickstart), provide model credentials and prepare an [Environment](/v2/en/service/environments). Choose **AgentScope Managed** when creating the Agent, enter Instructions and select a configured model or its default. Choose the Environment in Advanced settings, save and verify a first reply in [Chat](/v2/en/service/chat).

Example Instructions:

```text
You organize technical material. Check whether the input is sufficient and cite evidence.
Read only the files specified by the task. Ask for missing material.
Deliver conclusions, supporting evidence and open questions.
```

Model credentials, tool credentials and login credentials serve different purposes. Installation does not include model credits, and a Vault does not automatically configure every model provider.

## Add files and knowledge

Link a Workspace and read a known file in a small task. Choose sandbox or self_hosted for isolated file and Shell execution. Local runs in the Dataplane, without automatic access to the browser computer or arbitrary host directories.

Bind a Memory Store for durable knowledge. The Agent reads shared documents through tools as needed; Session working memory belongs to its execution context. Verify file access through identifiable excerpts or counts rather than only an “I read it” response.

## Extend capabilities

| Capability | Configuration | Verify |
| --- | --- | --- |
| MCP tools | Tools connection with Vault credential references | Endpoint, authentication, permissions and confirmation |
| Skills | Workspace/Skills instructions and supporting files | Discovery and actual file use |
| Subagents | Specialist roles under Subagents | Delegation boundaries, context and returned results |
| Team membership | Add the Agent with a role | Independent dispatch, resources and collaborative output |

A Skill describes a procedure; it does not install system dependencies. Subagents provide delegation inside an Agent, while Teams provide durable collaboration across members.

## Move from conversation to delivery

Verify text replies, then read-only tools, then an Issue with acceptance criteria. Inspect actual wait reasons for confirmation or Worker execution rather than treating every wait as a stalled model.

Check deliverables and Issue state after execution. Human-review work still needs acceptance in Inbox. See [task outcomes and failures](/v2/en/service/managed-harness-task-outcomes).

## Operate and improve

Verify model, skill and shared-resource changes with new work. Retain a representative successful input and result to compare tool behavior and output quality. Use Issue, Run and Session IDs to diagnose model, environment and permission failures with the [troubleshooting guide](/v2/en/service/troubleshooting).
