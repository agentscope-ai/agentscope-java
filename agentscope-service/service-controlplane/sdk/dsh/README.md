# DeepSeek Harness → AgentScope Service

English | [中文](README_zh.md)

Register a running [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) process with the AgentScope Service control plane. DSH keeps its own loop and Web UI. This package is an out-of-tree Cordis plugin you add to an existing profile.

The published package is `@agentscope-service/dsh-controlplane`. Install a release with `npm install @agentscope-service/dsh-controlplane@2.1.0-BETA1`; the prerelease channel is `@agentscope-service/dsh-controlplane@next`. Its Cordis bundle uses the same package name.

## Quick start

A source checkout does **not** put `dsh` on your PATH. The official launcher is `pnpm dsh web` from the **`deepseek-harness` repository root** (see DSH's [Run from source](https://github.com/deepseek-ai/deepseek-harness#run-from-source)). The steps below assume that. `pnpm dsh` forwards arguments to the in-repo CLI and is equivalent to an installed `dsh`.

Set two paths and reuse them:

```bash
DSH_ROOT=/path/to/deepseek-harness
PLUGIN=/path/to/agentscope-java/agentscope-service/service-controlplane/sdk/dsh
```

### 0. Confirm source DSH boots

First time from a source checkout, at the DSH repository root:

```bash
cd "$DSH_ROOT"
pnpm install
pnpm run build
pnpm dsh web
```

Default Web UI: http://127.0.0.1:3080. Chat once before continuing. `pnpm dsh web` is an alias for `--profile web`; replace `web` / `--profile web` if you use another profile.

If `pnpm install` / `pnpm run build` fails with leftover `refusing to replace user-owned core.hooksPath`: packages are already installed. The failure is only DSH's contributor Git-hook installer refusing to override your global `core.hooksPath` (for example a company AK-scan hook). pnpm 11 re-runs install before every `pnpm run …`, so leftover failure also blocks `build`. Do not change global `~/.gitconfig`. Let leftover succeed once in this repo, then build:

```bash
DSH_LEFTHOOK_ALLOW_HOOKS_PATH_OVERRIDE=1 pnpm install
pnpm run build
pnpm dsh web
```

That only switches this worktree to DSH leftover; other repos keep the global hook. To skip leftover without changing this repo's hooks, use `CI=true pnpm run build`.

### 1. Start the AgentScope Service control plane

In a second terminal, from the AgentScope Java repo (console login `admin` / `admin`):

```bash
cd /path/to/agentscope-java/agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

| Surface | URL |
| --- | --- |
| Console / Dashboard | http://localhost:18080 |
| service-controlplane (registration target) | http://localhost:8081 |

`scripts/dev-up.sh` defaults `BUILDER_INTERNAL_TOKEN` to `local-dev-internal-token-at-least-32chars`. DSH must use **the same** token or registration is rejected.

### 2. Build the plugin, then add it from the DSH repo

Do not fork DSH. Build `dist/` in this package, then run `pnpm dsh plugin add` from the **DSH repository root** (relative paths are resolved against the directory you invoke from, not the profile).

```bash
cd "$PLUGIN"
npm install
npm run build

cd "$DSH_ROOT"
pnpm dsh plugin --profile web add "$PLUGIN"
pnpm dsh web --dump-config
```

The dump should include a `# == @agentscope-service/dsh-controlplane` layer or an `id: controlplane` row.

### 3. Restart DSH with control-plane credentials

Stop the existing `pnpm dsh web`. Still in the **DSH repository root**, export the token and start as usual:

```bash
cd "$DSH_ROOT"
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
export CONTROL_PLANE_HTTP=http://localhost:8081
export CONTROL_PLANE_GRPC=localhost:15010
export CONTROL_PLANE_AGENT_NAME=deepseek-harness
# Optional: same path as service-controlplane CONTROL_PLANE_TRANSCRIPT_FS_ROOT so Operate can read history after DSH exits
# export CONTROL_PLANE_TRANSCRIPT_DIR=/tmp/controlplane-transcripts

pnpm dsh web
```

Logs should include:

```text
[controlplane] instrumented DeepSeek Harness as 'deepseek-harness' (contract :18091, control http://localhost:8081, agent-task=true)
[controlplane] registered <instanceId> at http://127.0.0.1:18091 with http://localhost:8081
```

Probe the contract port locally:

```bash
curl -s http://127.0.0.1:18091/agentscope/health
# {"status":"ok"}
```

Without `BUILDER_INTERNAL_TOKEN` the plugin still serves `/agentscope/*` but **does not** register, so the Dashboard stays empty.

### 4. Check the Dashboard

1. Open http://localhost:18080 and sign in as `admin` / `admin`.
2. **Dashboard**: a healthy instance named `deepseek-harness`.
3. Send a turn in the DSH Web UI (http://127.0.0.1:3080). Dashboard Sessions should move `idle` → `active` → `idle` and show token deltas.
4. For collaboration, create an Issue and start or assign an Orchestration Run to this Agent or a Team containing it. DSH receives a fenced `ExecutionAttemptCommand`, pulls the Issue discussion with its task-scoped token, and writes progress/result Comments and Artifacts.

Remove the plugin (from the DSH repository root):

```bash
cd "$DSH_ROOT"
pnpm dsh plugin --profile web remove @agentscope-service/dsh-controlplane
```

### If you use npx instead of a source checkout

The official npm entry is `npx @deepseek-ai/dsh web`. That also does not install a global `dsh` unless you ran `npm i -g @deepseek-ai/dsh`. Replace every `pnpm dsh` above with `npx @deepseek-ai/dsh` and skip `cd "$DSH_ROOT"`, for example:

```bash
npx @deepseek-ai/dsh plugin --profile web add "$PLUGIN"
npx @deepseek-ai/dsh web --dump-config
npx @deepseek-ai/dsh web
```

## What it does

1. Serves `/agentscope/health`, `/info`, `/sessions` (pages of 500 with `truncated`/`hasMore`), context, messages, tasks, plan-mode, subagent-tasks, export-transcript, abort/terminate/compress, and inbound `POST .../messages`.
2. `POST /api/v1/dataplanes/register` plus heartbeats (`X-Builder-Internal-Token`). Contract write operations require the same token; task actions additionally use `X-Agent-Task-Token`.
3. Maps DSH `session/event` and `agent/status` onto contract snapshots (`active` / `idle` / `compressing`), including `systemPrompt`.
4. When a token is present, advertises `agent-task` and exposes Issue/Comment/AgentTask/Artifact tools to an isolated execution context.
5. Optional ASDP gRPC client (`CONTROL_PLANE_GRPC`, default `localhost:15010`) receives `ExecutionAttemptCommand` / `SessionCommand` and reports fenced `ExecutionAttemptReport` transitions. Connect failure is a warning only — the DSH loop keeps running. HTTP self-register stays on so the prober can still use `baseUrl`.
6. Optional transcript JSONL under `CONTROL_PLANE_TRANSCRIPT_DIR` (`{tenant}/{agent}/{session}/events/...jsonl`) for Operate after the process exits.

Compress: if the agent is `running`, `POST .../compress` returns **409** `{ hint: wait_idle }`. If `ctx.compaction.compactNow` is missing (typical of non-Web profiles), it returns **501**. The web profile has compaction.

## Configuration

Plugin `config` fields merge with environment variables. Non-empty plugin fields win. The quick start only needs the environment variables.

| Field | Environment | Default |
| --- | --- | --- |
| `controlHttp` | `CONTROL_PLANE_HTTP` / `BUILDER_CONTROL_URL` | `http://localhost:8081` |
| `internalToken` | `BUILDER_INTERNAL_TOKEN` / `CONTROL_PLANE_INTERNAL_TOKEN` | empty (serves contract, skips register) |
| `agentName` | `CONTROL_PLANE_AGENT_NAME` | `deepseek-harness` |
| `namespace` | `CONTROL_PLANE_NAMESPACE` | `default` |
| `instanceId` | `CONTROL_PLANE_INSTANCE_ID` / `HOSTNAME` | OS hostname |
| `contractHost` | `CONTROL_PLANE_CONTRACT_HOST` | `127.0.0.1` |
| `contractPort` | `CONTROL_PLANE_CONTRACT_PORT` | `18091` |
| `publicBaseUrl` | `CONTROL_PLANE_PUBLIC_BASE_URL` | `http://127.0.0.1:<boundPort>` |
| `startHttpRegister` | `CONTROL_PLANE_HTTP_REGISTER` | `true` |
| `controlGrpc` | `CONTROL_PLANE_GRPC` | `localhost:15010` (service-controlplane ASDP) |
| `startGrpc` | `CONTROL_PLANE_ENABLE_GRPC` | `true` when token is set |
| `enableTeamCoordination` | `CONTROL_PLANE_TEAM_COORDINATION` | `true` when token is set |
| `transcriptDir` | `CONTROL_PLANE_TRANSCRIPT_DIR` | empty (no JSONL writer) |

`service-controlplane` probes `publicBaseUrl` (or the derived localhost URL) for `/agentscope/info`. If DSH and service-controlplane are not on the same host, set `publicBaseUrl` to an address the control plane can reach.

Locally, point `CONTROL_PLANE_TRANSCRIPT_DIR` at the same directory as service-controlplane `CONTROL_PLANE_TRANSCRIPT_FS_ROOT` (or a shared Docker volume). Without a shared disk, Operate still falls back to live `message-query`.

## One-shot `--patch` overlay (no profile change)

To try without `plugin add`, use `--patch` the way DSH's own plugin tutorial does. The loader resolves bare package names from the **profile directory**, so do not put `@agentscope-service/dsh-controlplane` in the overlay — use the **absolute path** to this package's build output.

After `npm run build`, write e.g. `$DSH_ROOT/controlplane.patch.yml`:

```yaml
- insert:
    - id: controlplane
      name: '/path/to/agentscope-java/agentscope-service/service-controlplane/sdk/dsh/dist/index.js'
      config:
        controlHttp: http://localhost:8081
        contractPort: 18091
        agentName: deepseek-harness
        namespace: default
```

```bash
cd "$DSH_ROOT"
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
export CONTROL_PLANE_HTTP=http://localhost:8081
pnpm dsh web --patch "$DSH_ROOT/controlplane.patch.yml"
```

## Development

```bash
cd "$PLUGIN"
npm install
npm test
npm run build
```
