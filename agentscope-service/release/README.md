# Managed Agents release tools

[中文说明](README_zh.md)

This standalone GitLab repository has no active publication workflow. Archived
upstream workflows are historical reference only. Names below describe local
candidates until the new project's publisher accounts and distribution channels
are configured.

## Validate and package locally

From the repository root, install the Python SDK dev dependencies and
`managed-agents/release/requirements.txt` into `.venv`. Java builds require the
compatible external AgentScope Java binaries described in the root README.

```bash
.venv/bin/python managed-agents/release/release.py verify

.venv/bin/python managed-agents/release/release.py package \
  --version RELEASE_VERSION --repository REGISTRY/NAMESPACE \
  --platforms linux/amd64,linux/arm64,darwin/amd64,darwin/arm64 \
  --output managed-agents/release/dist/RELEASE_VERSION
```

Use `--distributions-only` to omit Java/Python/npm SDK packages. A local smoke
candidate can use only `--platforms darwin/arm64`. Output directories must not
already exist. Packaging produces Compose/Kubernetes archives, the Helm chart,
CLI/Runtime Host archives, `release-manifest.json` and `SHA256SUMS`; full packages
also include SDK distributions. The manifest records the dirty state of local
source and does not prove that registry images or package versions are published.

The five image names are `managed-agents-control-plane`,
`managed-agents-agent-service`, `managed-agents-agent-harness-runtime`,
`managed-agents-tool-worker` and `managed-agents-gateway`. `images` builds them
from this standalone source; `--push` explicitly publishes. Configure the real
registry hostname/namespace rather than using the example repository.

## SDK coordinates

| SDK | Name |
| --- | --- |
| Java | `io.agentcore.managed:managed-agents-client` |
| Python | `managed-agents-sdk`, import `managed_agents` |
| DSH | `@agentcore/managed-agents-dsh` |

`revision` controls the Java client version; `agentscope.version` controls external
Java libraries and remains independent. npm scope and Maven/PyPI publisher access
must be configured for this project. Do not publish the executable Java services
as SDK dependencies. Signing and Maven Central publication are not configured by
this migration.

## Publication targets

Configure and review each destination before publishing. The optional GitHub
Release helper requires `GH_REPO`; its source branch defaults to `master` and can
be set with `MANAGED_AGENTS_RELEASE_BRANCH`. It is an optional GitHub publisher,
not a GitLab Release implementation. The Go nested-module tag prefix is now
`managed-agents/control-plane/v` and must match the new module path and source.

The optional GitHub-based Helm synchronization helper has no default accounts.
It requires `MANAGED_AGENTS_GITHUB_REPOSITORY`,
`MANAGED_AGENTS_HELM_GITHUB_REPOSITORY` and `MANAGED_AGENTS_HELM_URL`; until these
are supplied it stops before contacting a repository. This migration does not
activate it or create a remote Helm repository.

`homebrew/managed-agents-cli.rb.in` is a template. Render the intended version,
release base URL and all four archive SHA-256 values from the exact published
bytes before installing it in a tap. The old project's checksums are not reused.

No package, image, chart, Git tag or release is published by the renaming checks.
Existing CRDs, databases, volumes and clients need a coordinated migration before
an existing installation adopts the new identifiers and headers. Persistence
schema/table IDs and MQ topic/group values remain unchanged.

### Optional PI Brain image

PI and PI Durable share one Node artifact. `python3 managed-agents/release/release.py images-pi --version <version> --repository <registry> --platforms linux/amd64 --output /tmp/pi-release` builds and loads only the local PI image; ordinary `images` retains the five-component default. Publishing requires explicit `--push`, preserving clean-source, source/platform verification and SBOM rules. PI metadata is separate from the default image manifest. See [PI deployment](../agent-harness-runtime/images/pi/README.en.md) for Helm selection, dedicated Secrets, model configuration and encoding upgrade order.
