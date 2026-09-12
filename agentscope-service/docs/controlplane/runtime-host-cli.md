# Local Runtime Host CLI

The user-facing entry point for a local Hosted Agent runtime is `agentscope`; `aistio-runtime-host`
is its managed daemon process.

## Install from source

```bash
cd agentscope-service/aistio
make install-runtime-cli PREFIX="$HOME/.local"
```

Ensure `$HOME/.local/bin` is on `PATH`, then connect the machine:

```bash
agentscope connect
```

The command discovers a local AgentScope server, prompts for sign-in, exchanges that user session
for a Host-scoped `asrh_` credential, detects installed Codex/Claude Code/Qoder binaries, persists an
owner-only configuration, and starts the daemon. For a remote service, pass its public URL:

```bash
agentscope connect https://agentscope.example.com
```

Non-interactive environments can provide `AGENTSCOPE_API_TOKEN`. The preferred bootstrap flow uses a
short-lived, scope-bound enrollment token:

```bash
# Run by an operator. In single-scope mode the server ignores local scope flags.
agentscope runtime enrollment-token create

# Run on the machine being connected.
AGENTSCOPE_ENROLLMENT_TOKEN=asre_... agentscope connect https://agentscope.example.com
```

`agentscope connect` exchanges the `asre_` token for an `asrh_` credential bound to both the local
Host key and the token's tenant/namespace. It persists the scope returned by the server; it never
requires `--tenant` or `--namespace` in single-scope mode. A pre-issued Host credential can still be
provided with `AGENTSCOPE_RUNTIME_TOKEN`. Secrets are passed to the daemon through its environment,
not process arguments.

## Operate

```bash
agentscope runtime status
agentscope runtime logs -f
agentscope runtime restart
agentscope runtime stop
agentscope runtime start
agentscope runtime probe
```

Configuration and state default to `~/.agentscope/runtime-host/`:

- `config.json`: server, Host credential, pool, capacity, and detected provider paths (`0600`)
- `state/host.id`: stable machine identity (`0600`)
- `state/daemon.pid`: daemon process ID
- `state/daemon.ready`: successful control-plane registration
- `daemon.log`: daemon output (`0600`)
- `workspaces/`: task-scoped checkouts and portable Agent definitions

Advanced flags remain available on `agentscope connect --help`, but normal startup never requires the
control-plane URL, internal token, provider paths, pool, or workspace directories to be repeated.
`--tenant` and `--namespace` are multi-scope/debug compatibility flags. A mismatch with token claims
fails instead of overriding the server-assigned scope.

## Control-plane scope mode

`aistiod` defaults to `--scope-mode single`. The authoritative scope is configured with
`--default-tenant` and `--default-namespace` (or `AISTIO_DEFAULT_TENANT` and
`AISTIO_DEFAULT_NAMESPACE`). In this mode the console removes scope selectors and scope query
parameters, while the API canonicalizes query and top-level JSON scope values before authorization.

Use `--scope-mode multi` only for deployments that intentionally expose multiple scopes. The
storage and runtime protocols keep the same tenant/namespace fields in both modes. Namespace is not
renamed to Workspace because Workspace is already a distinct product resource.

## Task-scoped Agent CLI

The daemon exposes the same collaboration contract through MCP and the `agentscope` CLI. MCP-capable
providers prefer `agentscope-collaboration`; shell-capable providers can use these commands without
receiving a human or Host credential:

```bash
agentscope task context
agentscope issue current
agentscope issue comment list --roots-only --summary
agentscope task progress --content-file ./progress.md
agentscope task respond --content-file ./reply.md
agentscope task child --file ./child.yaml
agentscope artifact upload ./report.md
agentscope team current
agentscope task run graph
agentscope task run replan --file ./node.yaml
agentscope task run node-complete --file ./result.json
```

Task and Issue IDs, the control-plane endpoint, and the attempt/generation-scoped credential are
injected by the daemon. Explicit IDs remain available for diagnostics. The server rejects cross-Issue
access and stale task credentials. Agent-authored long text should use `--content-file` so shell
quoting cannot corrupt Markdown or non-ASCII content.
