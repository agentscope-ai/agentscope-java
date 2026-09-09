# Connect a Runtime Host

A Runtime Host runs on a computer or server with a Coding Agent provider installed. The control plane dispatches and records work; the Host executes it with the local provider.

## Install

Download `agentscope-cli-VERSION-OS-ARCH.tar.gz` for the operating system and CPU architecture from the same Service release. Verify its checksum and extract it. The archive contains `agentscope`, the compatibility name `aistioctl`, and `aistio-runtime-host`. Put all three executables in the same directory on PATH.

Packaging targets Linux/macOS on amd64 and arm64; use the release notes for actually published and tested platforms. Install, authenticate and verify the provider separately.

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

A Runtime Host is distinct from a Managed Agent Hands Worker. See [Workspaces and Environments](workspaces.md).
