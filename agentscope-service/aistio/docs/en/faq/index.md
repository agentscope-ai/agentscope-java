# FAQ

This page answers common adoption questions for AgentScope Go. For complete API details, see Quick Start and Advanced Topics.

## When Should I Use Only ChatModel

Use `model.ChatModel` and `message.Message` for plain chat, summarization, classification, structured extraction, and provider abstraction.

Use `agent.Agent` when the application needs model-driven tool calls, permission confirmation, event streaming, or long-context compression.

## When Should I Use Agent

Use Agent when you need the framework to run the loop: model reasoning, permission check, tool execution, and tool-result feedback.

If the caller needs full control over every model request and tool execution, use `model` and `tool.Toolkit` directly and implement the loop in application code.

## When Should I Use a Sandbox

Use a Sandbox when tools need a filesystem, Shell, Skill loading, MCP persistence, or large-content offload.

Plain function tools, regular model calls, and business tools that do not need a file environment do not require a Sandbox.

## How Should I Choose Between Call and Stream

`Call` returns one complete response. It fits server-side processing, summarization, and classification.

`Stream` returns a response channel. It fits frontend rendering of text deltas, tool status, reasoning blocks, and multimodal output.

## How Should Tool Execution Be Controlled

High-risk tools should go through `permission.Engine` before execution. Common modes include read-only exploration, user confirmation, automatic classification, and bypass in controlled environments.

File writes, Shell commands, external system access, and tools that mutate business state should have explicit rules.

## How Do I Configure Local Pre-commit

After cloning the repository for the first time, run:

```bash
make setup
make install-tools
```

Before committing, you can run all hooks manually:

```bash
.venv/bin/pre-commit run --all-files
```
