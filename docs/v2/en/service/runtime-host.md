---
title: Connect a Runtime Host
---

[简体中文](/v2/zh/service/runtime-host)

A Runtime Host runs on a computer or server with a Coding Agent provider installed. The control plane dispatches and records work; the Host executes it with the local provider.

## Install

Download `agentscope-cli-VERSION-OS-ARCH.tar.gz` for the operating system and CPU architecture from the same Service release. Verify its checksum and extract it. The archive contains `agentscope`, the alias `aistioctl`, and `aistio-runtime-host`. Put all three executables in the same directory on PATH.

Choose Linux/macOS and amd64/arm64 according to the release manifest and the machine you will connect. Install, authenticate and verify the provider separately.

## Extract onto PATH

Check the platform with `uname -sm`; package names use `linux`/`darwin` and `amd64`/`arm64`. Download the matching [Release](https://github.com/agentscope-ai/agentscope-java/releases) archive and replace the filename below:

```bash
mkdir -p agentscope-cli "$HOME/.local/bin"
tar -xzf agentscope-cli-VERSION-OS-ARCH.tar.gz -C agentscope-cli
install -m 0755 agentscope-cli/agentscope agentscope-cli/aistioctl agentscope-cli/aistio-runtime-host "$HOME/.local/bin/"
export PATH="$HOME/.local/bin:$PATH"
```

Persist the PATH setting in your shell configuration for later terminals.

## Connect

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

Follow the CLI's login or enrollment prompts. Connection saves local configuration and starts the daemon. Normal operation does not require repeatedly supplying a shared internal service token.

## Daily operation

```bash
agentscope runtime logs
agentscope runtime stop
agentscope runtime start
```

Configuration and state default to `~/.agentscope/runtime-host/`. Preserve the Host identity and state files to avoid accidentally registering an existing computer as a new instance. Do not distribute this directory as a public configuration example.

After connecting, verify Host and provider availability in the console, bind a Hosted Agent and dispatch a small task. Diagnose failures with both Task Attempt records and Host logs.

A Runtime Host is distinct from a Managed Agent Hands Worker. See [Environments](/v2/en/service/environments).

## Extract onto PATH

Check the platform with `uname -sm`; package names use `linux`/`darwin` and `amd64`/`arm64`. Download the matching [Release](https://github.com/agentscope-ai/agentscope-java/releases) archive and replace the filename below:

```bash
mkdir -p agentscope-cli "$HOME/.local/bin"
tar -xzf agentscope-cli-VERSION-OS-ARCH.tar.gz -C agentscope-cli
install -m 0755 agentscope-cli/agentscope agentscope-cli/aistioctl agentscope-cli/aistio-runtime-host "$HOME/.local/bin/"
export PATH="$HOME/.local/bin:$PATH"
```

Persist the PATH setting in your shell configuration for later terminals.

## Connect an unattended server

An authorized operator creates a short-lived enrollment token:

```bash
agentscope runtime enrollment-token create
```

On the target machine, supply it through `AGENTSCOPE_ENROLLMENT_TOKEN` and run connect. The service exchanges it for a credential bound to Host identity and scope. Keep enrollment tokens and `config.json` out of shared scripts.

## CLI reference

| Command | Purpose |
| --- | --- |
| `agentscope connect URL` | Authenticate/register, save configuration and start the daemon |
| `agentscope runtime status` | Inspect state |
| `agentscope runtime probe` | Check provider availability |
| `agentscope runtime logs -f` | Follow logs |
| `agentscope runtime restart` | Restart the daemon |
| `agentscope runtime stop` / `start` | Stop or start |
| `agentscope connect --help` | Inspect advanced flags in the installed version |

The state directory holds connection identity in `config.json`, stable Host identity in `state/host.id`, diagnostics in `daemon.log` and task directories in `workspaces/`. Check active tasks before updating paired CLI binaries, restart and preserve these files.
