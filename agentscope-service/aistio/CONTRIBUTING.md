# Contributing to Aistio

Chinese version: [`CONTRIBUTING_zh.md`](CONTRIBUTING_zh.md).

Thank you for helping improve Aistio. This guide explains how to set up
the repository, make focused changes, and submit contributions that are easy to
review.

## Before You Start

- Check existing issues and pull requests before starting a large change.
- Keep pull requests focused on one behavior, package, or documentation topic.
- Do not document unsupported APIs as available features.
- Do not add release automation or version bump workflows unless the maintainer
  explicitly requests them.
- If you use AI assistance, you are still responsible for reviewing, testing,
  and explaining the final change.

## Development Environment

Required tools:

| Tool | Version |
| --- | --- |
| Go | `1.26+` |
| GNU Make | Any recent version |
| controller-gen | Latest |
| Helm | `3.x` |

Bootstrap the repository:

```bash
make install-tools
```

## Common Commands

| Command | Purpose |
| --- | --- |
| `make build` | Build the `aistiod` binary. |
| `make test` | Run `go test ./...` with coverage. |
| `make test-integration` | Run envtest integration tests. |
| `make fmt` | Format Go code. |
| `make vet` | Run `go vet ./...`. |
| `make generate` | Regenerate deepcopy methods. |
| `make manifests` | Regenerate CRDs, RBAC, and webhook manifests. |
| `make sync-helm` | Sync generated CRDs and RBAC into the Helm chart. |
| `make verify` | Verify generated code and manifests are up to date. |
| `make helm-lint` | Lint the Helm chart. |
| `make docker-build` | Build multi-arch Docker image. |

## Repository Conventions

- Keep public APIs small, explicit, and Go idiomatic.
- Match the existing package boundaries before introducing a new package.
- Keep Go comments and public API documentation in English.
- Put Chinese user-facing documentation in `*_zh.md` files.
- Use the project license header format:

```go
// Copyright The Aistio Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
```

- Prefer standard Go libraries and mature ecosystem libraries over custom
  implementations.
- Wrap errors with enough context for callers to understand the failing package,
  operation, and external dependency.
- Never commit API keys, model credentials, generated secrets, or private MCP
  server configuration.

## Testing Expectations

For code changes, run:

```bash
make fmt
make vet
make test
```

For CRD or webhook changes, also run:

```bash
make generate
make manifests
make verify
```

## Pull Request Checklist

Before opening a pull request, confirm that:

- The change has a clear motivation and scope.
- Code, tests, and documentation are updated together when needed.
- Relevant local checks pass.
- Any skipped checks are explained in the pull request.
- Security-sensitive behavior, such as agent execution or credential handling,
  is called out clearly.

Use the repository pull request template and keep the title concise.

## Reporting Security Issues

Do not open a public issue for a vulnerability. Follow
[`SECURITY.md`](SECURITY.md) for private reporting instructions. The Chinese
version is [`SECURITY_zh.md`](SECURITY_zh.md).

## Code of Conduct

All project spaces follow [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). The
Chinese version is [`CODE_OF_CONDUCT_zh.md`](CODE_OF_CONDUCT_zh.md).
