# Installation

AgentScope Go is a regular Go module. Add it to your project with `go get`.

## Requirements

- Go 1.26.3 or newer.
- Network access for downloading Go modules.
- Optional provider API keys for live model calls.

## Add the Module

```bash
go mod init example.com/my-agent
go get github.com/yuluo-yx/agentscope-go
```

## Configure Provider Keys

DashScope examples use `AI_DASHSCOPE_API_KEY`:

```bash
export AI_DASHSCOPE_API_KEY="your-key"
```

You can override the DashScope model in examples:

```bash
export AI_DASHSCOPE_MODEL="qwen3.7-max"
```

## Verify the Repository

When working inside this repository, run:

```bash
go test ./...
```

The project also exposes Make targets:

```bash
make test
make lint
make docs-build
```

## Run Examples

Each example directory is an independent Go module:

```bash
cd example/tool/mcp
go run .
```

Most model-related examples support an offline path. If the required API key is missing, they still print the tool schema or token estimate so the example remains runnable.
