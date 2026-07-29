# Workspace

`workspace/local.Workspace` provides a local environment for tools and resources.

## Local Workspace

A local workspace initializes a directory with:

- `data/` for files used by tools,
- `skills/` for local skills,
- `sessions/` for offloaded context and tool results.

```go
ws, err := local.NewWorkspace("/tmp/agentscope-workspace")
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

## Docker Workspace

`workspace/docker.Workspace` runs workspace tools inside a Docker container. It is useful for local isolated shell, file, and search workflows.

```go
ws, err := docker.NewWorkspace(
	docker.WithImage("ubuntu:latest"),
	docker.WithHostWorkdir("/tmp/agentscope-docker-workspace"),
)
if err != nil {
	panic(err)
}
```

When `WithHostWorkdir` is set, offload, skills, and MCP indexes are written to the host mirror directory.

## Microsandbox Workspace

`workspace/microsandbox.Workspace` creates a local Microsandbox microVM through the official Microsandbox Go SDK and runs `Bash`, `Read`, `Write`, `Edit`, `Glob`, and `Grep` inside that microVM.

```go
ws, err := microsandbox.NewWorkspace(
	microsandbox.WithImage("python:3.12"),
	microsandbox.WithHostWorkdir("/tmp/agentscope-microsandbox-workspace"),
)
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

Prerequisites:

- Linux with KVM enabled, or macOS with Apple Silicon.
- Microsandbox runtime assets. By default, `Initialize` calls `EnsureInstalled` and the SDK downloads missing assets into `~/.microsandbox/`.

Use `WithEnsureInstalled(false)` when the runtime is already installed and startup must not download assets. Use `WithKeepSandbox(true)` to leave the microVM running for inspection when `Close` is called.

## Agent Sandbox Workspace

`workspace/agentsandbox.Workspace` creates Kubernetes `SandboxClaim` resources through the agent-sandbox Go SDK and runs `Bash`, `Read`, `Write`, `Edit`, `Glob`, and `Grep` inside an Agent Sandbox runtime.

```go
ws, err := agentsandbox.NewWorkspace(
	agentsandbox.WithTemplateName("python-sandbox-template"),
	agentsandbox.WithNamespace("default"),
	agentsandbox.WithHostWorkdir("/tmp/agentscope-agent-sandbox-workspace"),
)
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

Prerequisites:

- A reachable Kubernetes cluster.
- agent-sandbox controller, extensions, and sandbox-router installed.
- Current kubeconfig can create `SandboxClaim` resources.
- A `SandboxTemplate` exists in the target namespace. The examples use `python-sandbox-template`.

Connection modes:

- Default: port-forward mode for local and KinD tests.
- `WithAPIURL`: connect to a sandbox-router direct URL.
- `WithGateway`: connect through Kubernetes Gateway API.

The `Write` tool still accepts absolute paths. Because the agent-sandbox Go SDK `Write()` accepts only plain filenames, AgentScope-Go uploads a temporary file first and then moves it to the requested absolute path inside the sandbox.

## Daytona Workspace

`workspace/daytona.Workspace` creates or connects to Daytona sandboxes through the official Daytona Go SDK and runs `Bash`, `Read`, `Write`, `Edit`, `Glob`, and `Grep` inside the Daytona sandbox.

```go
ws, err := daytona.NewWorkspace(
	daytona.WithImage("python:3.12"),
	daytona.WithHostWorkdir("/tmp/agentscope-daytona-workspace"),
)
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

Prerequisites:

- A Daytona account or compatible self-hosted Daytona API.
- `DAYTONA_API_KEY`, or the JWT environment variables supported by the Daytona SDK.
- Optional `DAYTONA_API_URL` and `DAYTONA_TARGET` for custom API endpoints and targets.

By default, a newly created Daytona sandbox is deleted when `Close` is called. Use `WithKeepSandbox(true)` to keep it for inspection, or `WithSandboxID` / `WithSandboxName` to connect to an existing sandbox without deleting it.

## Tools

`ListTools` exposes built-in local file and shell tools:

```go
tools, err := ws.ListTools(ctx)
```

Register them in a Toolkit when the agent should use workspace-backed tools.

Docker, Microsandbox, and Agent Sandbox backends intentionally keep the same model-visible
`Bash`, `Read`, `Write`, `Edit`, `Glob`, and `Grep` schemas as the local
workspace, but execute them through their backend runtimes. Docker tools call
the Docker engine against the workspace container, Microsandbox tools call the
Microsandbox SDK handle, and Agent Sandbox tools call the sandbox handle. These
tool calls must not fall back to host execution.

This differs from the Python Docker/E2B implementation, where workspace tools
are exposed through an in-workspace gateway. In AgentScope Go this is an
explicit boundary: built-in workspace tools use typed Go runtime adapters, while
MCP servers can still be exposed through `workspace/gateway.Server` and the
host-side gateway client. Tests that require Docker, Microsandbox, or Agent
Sandbox remain opt-in through `AGENTSCOPE_TEST_DOCKER=1`,
`AGENTSCOPE_TEST_MICROSANDBOX=1`, and `AGENTSCOPE_TEST_AGENT_SANDBOX=1`.

## Skills

Seed skills with `local.WithSkillPaths`:

```go
ws, err := local.NewWorkspace(
	"/tmp/agentscope-workspace",
	local.WithSkillPaths("./skills/review"),
)
```

## Offload

The workspace can offload conversation context and tool results to files. This keeps large content outside the active model context while preserving a retrievable record.
