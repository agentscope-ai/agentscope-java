# Security Policy

Chinese version: [`SECURITY_zh.md`](SECURITY_zh.md).

Aistio is a Kubernetes-native control plane for AI agent management. Application
owners decide which agents, model providers, tools, MCP servers, and credentials
are available at runtime.

## Supported Versions

Security fixes are considered for:

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| `main` branch | Best effort |
| Older releases | No, unless explicitly announced |

## Reporting a Vulnerability

Please do not open a public GitHub issue for a suspected vulnerability.

Report security issues by email:

- Email: `yuluo08290126@gmail.com`
- Subject: `Aistio security report`

Include as much detail as possible:

- Affected package, commit, tag, or module version.
- Operating system and Go version.
- Agent, model provider, MCP configuration, or Kubernetes resource involved.
- Minimal reproduction steps or proof of concept.
- Expected impact and whether credentials, cluster resources, or agent execution
  are involved.

The maintainer will try to acknowledge reports within 7 days and provide a
status update within 30 days. Coordinated disclosure timing will be discussed
with the reporter when a fix is available.

## Security Boundaries

Aistio manages the lifecycle of AI agents on Kubernetes. It does not make an
application safe by default.

- Model API keys and other credentials must be supplied via Kubernetes Secrets
  and must not be committed to the repository.
- Agent execution is controlled by the CRD specifications and webhook
  validation configured by the cluster operator.
- MCP servers are external trust boundaries. Use trusted server binaries and
  review their configuration before connecting them to an agent.
- The ASDP data plane connection carries configuration updates. Ensure network
  policies restrict access to the control plane endpoints.
- Messages, tool results, logs, and session data may contain sensitive
  information. The cluster operator is responsible for storage, redaction, and
  retention.

## Issues That Should Be Reported Privately

Please use the private reporting channel for issues such as:

- Webhook validation bypasses.
- Privilege escalation through CRD manipulation.
- Unauthorized access to agent sessions or team communications.
- Path traversal or unintended file access through sandbox or builtin tools.
- Secret leakage in logs, tool outputs, or generated configurations.
- MCP integration behavior that allows an untrusted server to access more than
  the configured tool surface.
- Dependency or supply-chain issues with a practical exploit path in this
  project.

## Non-Security Issues

Open a normal GitHub issue for:

- Incorrect agent behavior or model output.
- Controller reconciliation bugs.
- Documentation mistakes.
- Feature requests for new providers, tools, or integrations.
- Bugs where the operator intentionally granted the access being used.

When in doubt, report privately first.
