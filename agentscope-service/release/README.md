# AgentScope Service release runbook

[中文发布手册（项目管理员操作指南）](README_zh.md)

Run all commands from the monorepo root. Development continues in the existing checkout; these scripts do not create a worktree or modify version files automatically.

## Distribution contract

| Component | Delivery | Version source |
| --- | --- | --- |
| Control plane + Dashboard | `as-controlplane` image | Service release version, injected into Go build |
| Gateway / Dataplane / Scheduler | `as-gateway`, `as-dataplane`, `as-scheduler` images | Service release tag; Java revision recorded separately |
| Complete deployment | Compose archive + `agentscope-service` Helm Chart (public HTTP repository) | Service release version |
| `as`, Runtime Host | Linux/macOS amd64/arm64 archives | Service release version |
| Java Application SDK | `io.agentscope:agentscope-extensions-controlplane` and reactor dependencies | Root `revision` |
| Python SDK | `agentscope-service-sdk` wheel and sdist | `service-controlplane/sdk/python/pyproject.toml` and `service-controlplane/sdk/python/agentscope_service/__init__.py` |
| DSH plugin | `@agentscope-service/dsh-controlplane` npm tarball | `service-controlplane/sdk/dsh/package.json` and lockfile |

The front end is private and bundled into the control image. `service-common` and executable Service modules remain excluded from Maven Central (`maven.deploy.skip=true`); they are not required by external Java SDK users. PostgreSQL is a separately operated dependency, not an AgentScope-published image. The legacy Control Plane Chart remains a separate Kubernetes-native offering. The complete Service Chart uses standalone HTTP, without ASDP gRPC.

## 1. Freeze source and choose versions

Choose a SemVer Service version and a registry namespace. `2.0.3-rc.1` in examples is a candidate, not a claim of publication. Update Python/DSH package versions before a new public package release; do not republish their existing `0.1.0` versions. Check both Python version declarations and the npm lockfile. Record the Java revision and required released Maven dependencies. Do not publish SDK POMs with unresolved SNAPSHOT dependencies.

Keep a source backup before cleanup. Generated UI files, build outputs, and raw test evidence are not source inputs. `release.py hygiene` rejects tracked generated artifacts. Generated CRDs/protobuf files, documentation illustrations and real test fixtures remain source distribution inputs.

## 2. Install build tools and verify

Use Java 21, Maven, Go from `service-controlplane/go.mod`, Node.js 22, Python 3.10+, Docker Buildx and Helm 3.17+ (or compatible versions).

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r agentscope-service/release/requirements.txt -e 'agentscope-service/service-controlplane/sdk/python[dev]'
python agentscope-service/release/release.py verify
```

Set `CONTROL_PLANE_TEST_POSTGRES_DSN` to a **disposable test database** for PostgreSQL integration tests; never point tests at development or production data. The verifier uses `go test -p 1` so packages sharing one PostgreSQL database do not contend during concurrent-index migrations. Some Go controller tests additionally require envtest assets (`make test-integration` in `service-controlplane`). Run the repository-wide `mvn clean verify` before release submission. The release verifier explicitly selects all three Java service modules and their dependencies: selecting only the aggregator with `-pl agentscope-service` does not test its children.

```bash
mvn -B -ntp -T1 clean verify
cd docs
npm ci
npm test
npm run validate
npm run broken-links
cd ..
```

## 3. Package candidates

```bash
python agentscope-service/release/release.py package \
  --version 2.0.3-rc.1 --repository REGISTRY/NAMESPACE
```

Default output: `agentscope-service/release/dist/2.0.3-rc.1/`. Existing output directories are not overwritten. Use another `--output` for a new rehearsal. The package includes deploy files, CLI/Host archives, Helm, Python and npm artifacts, `release-manifest.json` and `SHA256SUMS`. Packaging uses an explicit allowlist and never includes deploy `.env` files. Dirty source is recorded for local candidates.

Each CLI archive includes both executables and a bilingual installation README. The Kubernetes bundle `agentscope-service-VERSION-kubernetes.tar.gz` contains the Chart, Secret configuration template, database schema SQL, and installation README; the Chart is also emitted as a standalone `.tgz`. If SDKs are published separately, add `--distributions-only` to build just the CLI, Compose, Kubernetes, and Chart assets.

```bash
cd agentscope-service/release/dist/2.0.3-rc.1
shasum -a 256 -c SHA256SUMS
```

Install the wheel and npm tarball into clean environments and inspect their contents (`twine check`, `npm pack` listing). The Python SDK imports generated protobuf code and requires compatible grpcio/protobuf dependencies. Verify the Java SDK using the exact Maven revision chosen for publication.

## 4. Build and rehearse images

```bash
python agentscope-service/release/release.py images --version 2.0.3-rc.1 \
  --repository REGISTRY/NAMESPACE --platforms linux/arm64
```

Use `linux/amd64` on an amd64 Docker host. Local builds load one platform; multi-platform publication requires `--push`. The control Dockerfile builds the Dashboard from its lockfile. Java Dockerfiles build from the root Maven reactor. No committed JAR or UI build is needed. Image metadata goes into `image-*.json` in the output directory.

Deploy the generated Compose package under a separate project name and unused port. Verify bootstrap login, no demo users, a first Session, event history, shared files, restart persistence and an Issue/Team flow. Run Helm lint/render checks and a disposable-cluster installation using the same image versions. The reusable smoke script accepts a private env file and records resource IDs:

```bash
python agentscope-service/release/smoke.py --base http://127.0.0.1:18081 \
  --env-file /private/path/.env --state /tmp/service-smoke.json
# After restarting the test stack, add --resume with the same arguments.
```

The smoke script creates test resources and requires Local to be enabled on the disposable test installation. Add `--model-turn` only when configured model credentials are available; otherwise inference is explicitly not qualified. Verify external PostgreSQL, RWX storage on the intended cluster, Ingress/SSE and upgrade/recovery against a restored database copy. Cross-compiling is not a substitute for runtime validation on each advertised architecture.

## 5. Publish approved source

Commit the final release changes, tag the chosen source, and ensure the working tree is clean. Authenticate explicitly:

```bash
docker login REGISTRY
helm registry login REGISTRY
python agentscope-service/release/release.py images --version VERSION \
  --repository REGISTRY/NAMESPACE --platforms linux/amd64,linux/arm64 --push
python agentscope-service/release/release.py publish-chart \
  --version VERSION --repository REGISTRY/NAMESPACE
```

Image pushes request SBOM and provenance attestations. Preserve the resulting digest metadata alongside the package manifest. Source-package checksums do not cover subsequently created image metadata; publish that metadata separately. Do not reuse a released image tag.

The manual `AgentScope Service release` workflow verifies and packages before building images. `publish=false` builds a local amd64 candidate. `publish=true` requires a selected Git tag and `SERVICE_REGISTRY_HOST`, `SERVICE_REGISTRY_USER`, `SERVICE_REGISTRY_TOKEN` repository secrets. Registry namespace is an explicit input. This manual workflow remains available for image rehearsals and optional OCI Chart publication. The tag-triggered `service-dist-release.yml` workflow automatically pushes images before publishing GitHub Release assets and the Go module tag (section 7).

### Automatic images on future main tags

Configure these repository secrets in **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `SERVICE_REGISTRY_USER` | ACR registry login username with access to all four repositories |
| `SERVICE_REGISTRY_TOKEN` | The corresponding ACR registry password (not the Alibaba Cloud console password) |

The automatic workflow derives the login host from `SERVICE_IMAGE_REPOSITORY` (default `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`); it does not need `SERVICE_REGISTRY_HOST`. Local Docker login does not authenticate GitHub-hosted runners. See [ACR access credentials](https://help.aliyun.com/zh/acr/user-guide/configure-access-credentials/).

Once the release version is set in the Java POMs and Go version constant and the source is merged into `main`, pushing `vVERSION` runs packaging, then builds and pushes `as-controlplane`, `as-gateway`, `as-dataplane` and `as-scheduler` as `linux/amd64,linux/arm64` manifest lists with the image tag `VERSION` (without `v`). Buildx requests SBOM and provenance. Existing tags are reused only when both platforms and their version/source-commit labels match; conflicts and authentication/network errors stop the workflow. Missing images are built and checked after pushing. Retry a failed images job to resume a partial release. The current Maven `2.1.0-SNAPSHOT` is a development version; prepare the actual release versions before creating the next main tag.

The GitHub Release publish job waits for all four images. It attaches `images.json` containing the verified references, digests, source commit and platforms and includes its checksum in `SHA256SUMS`. Individual Buildx metadata files are available in the `service-image-metadata` Actions artifact. The public HTTP Helm repository and Homebrew tap keep their independent publication workflows; the automatic image job does not require Helm OCI login.

## 6. Publish SDK packages

For Python, configure a GitHub Pending Publisher in [PyPI account Publishing settings](https://pypi.org/manage/account/publishing/) before the first upload:

| Field | Value |
| --- | --- |
| PyPI project name | `agentscope-service-sdk` |
| Owner | `agentscope-ai` |
| Repository | `agentscope-java` |
| Workflow filename | `service-pypi-release.yml` (without `.github/workflows/`) |
| Environment | `pypi` |

The `AgentScope Service PyPI release` workflow tests Python 3.9–3.14, checks that the tag/input and both SDK version declarations agree, builds and validates the wheel/sdist, and publishes through OIDC. Only the upload job has `id-token: write`; no PyPI API token or GitHub SDK secret is needed. Its first successful upload creates the PyPI project. See [PyPI Trusted Publishing](https://docs.pypi.org/trusted-publishers/creating-a-project-through-oidc/).

It runs on `v*` and `agentscope-service-v*` tags containing the workflow, or through a manual version input once the workflow exists on the default branch. Both entry points require the selected source to have been merged into `main`. Tags created before the merge skip SDK publication; dispatch the workflow after merging. Use `2.1.0-BETA1` or the equivalent Python version `2.1.0b1` for this release. After configuring the publisher and merging the workflow into the default branch, publish the selected release source:

```bash
gh workflow run service-pypi-release.yml --repo agentscope-ai/agentscope-java \
  --ref main -f version=2.1.0-BETA1
```

After publication, verify `pip install agentscope-service-sdk==2.1.0b1` and import the SDK in a fresh virtual environment. A published version cannot be uploaded again. For a local Twine fallback, confirm package ownership, version availability and token credentials:

```bash
python -m twine check agentscope-service/release/dist/VERSION/agentscope_service_sdk-*.whl agentscope-service/release/dist/VERSION/agentscope_service_sdk-*.tar.gz
# Upload only the Python artifacts, never the Compose/CLI tar.gz files:
python -m twine upload agentscope-service/release/dist/VERSION/agentscope_service_sdk-*.whl agentscope-service/release/dist/VERSION/agentscope_service_sdk-*.tar.gz
python agentscope-service/release/release.py publish-npm --version VERSION
```

The npm command tests, builds and publishes `@agentscope-service/dsh-controlplane` to `https://registry.npmjs.org` with public access. It selects `next` for prereleases and `latest` for stable versions. Use `--dry-run` to inspect a candidate without uploading. Actual publication requires committed, clean source.

The `service-npm-release.yml` workflow runs automatically on `v*` and `agentscope-service-v*` tags, and also supports a manual version input. The tag/input must match the SDK version. Configure the package Trusted Publisher once with GitHub owner `agentscope-ai`, repository `agentscope-java`, workflow `service-npm-release.yml`, and environment `npm`. It uses OIDC with npm 11; no `NPM_TOKEN` secret is needed. The workflow must be present in the tagged source, and in the default branch for manual dispatch. After the first local package publication, configure the publisher on npmjs.com or use npm 11.15+:

```bash
npm trust github @agentscope-service/dsh-controlplane \
  --repo agentscope-ai/agentscope-java --file service-npm-release.yml \
  --env npm --allow-publish
```

The registry may require browser/2FA verification for this one-time setup. A new Trusted Publisher must complete its first successful CI publication within two days; otherwise recreate the expired binding before the next release. A first version already uploaded locally cannot be uploaded again to validate CI. See [npm Trusted Publishing](https://docs.npmjs.com/trusted-publishers/#trusted-publisher-configuration-expiry). Confirm it using `npm trust list @agentscope-service/dsh-controlplane`. Review the actual Python sdist filename before uploading. Maven SDK publication uses the repository's existing release profile and signing/Central credentials:

```bash
mvn -B -ntp -pl agentscope-extensions/agentscope-extensions-controlplane -am \
  -Drevision=JAVA_RELEASE_VERSION -Prelease deploy
```

This command includes required reactor dependencies and parent POMs; review the reactor and publish them only under new, intentional versions. Do not enable Maven deployment for the executable Service modules just to publish the SDK.

The current release profile does not enable `autoPublish`. After Maven succeeds, inspect the deployment in Central Portal, finish the manual Publish step, and verify availability from a consumer project. See the [Central publishing plugin documentation](https://central.sonatype.org/publish/publish-portal-maven/).

## 7. Publish documentation and release notes

Attach package archives, manifest, checksums and image metadata to the Release. Keep public Release notes to one or two sentences linking to the official Service documentation. Document Compose startup, Go CLI installation, the public HTTP Helm repository, versions and operational requirements in the corresponding installation guides. Verify anonymous downloads/pulls when public distribution is intended.

The public HTTP Helm repository is [chickenlj/helm-charts](https://github.com/chickenlj/helm-charts).
After publishing the upstream `vVERSION` Release with its standalone Chart asset,
run the independent repository's workflow; OCI publication is optional for this channel:

```bash
gh workflow run publish.yml --repo chickenlj/helm-charts --ref main -f version=2.1.0-BETA1
helm repo add agentscope https://chickenlj.github.io/helm-charts
helm repo update agentscope
helm pull agentscope/agentscope-service --version 2.1.0-BETA1
```

Use the intended version for future releases. The workflow fetches the original
Release archive, verifies its SHA256 and Chart metadata, preserves existing index
entries, refuses different bytes under an existing version, and deploys Pages.
No extra registry credential or cross-repository write token is required. Production checks currently return 404 for the official `/helm/index.yaml` route despite the merged redirects, so installation guides use the verified direct Pages URL until the alias passes real Helm checks.

The `AgentScope Service distribution release` workflow (`service-dist-release.yml`) runs when a main `v*` tag is pushed. First update the root Java revision and Go version constant, complete release validation, and merge the release source and this workflow into `main`. Then create only the main tag from the reviewed commit; for example, after preparing the next version:

```bash
git fetch origin main
RELEASE_TAG=v2.1.0-BETA2
git tag -a "$RELEASE_TAG" origin/main -m "$RELEASE_TAG"
git push origin "$RELEASE_TAG"
```

The workflow checks that the tagged commit is on `main`, that Java/Go versions match the tag, and that the Go module major matches. It tests packaging and the CLI commands, builds all four Linux/macOS amd64/arm64 CLI/Runtime Host archives, the Compose and Kubernetes archives, and the standalone Helm Chart. After pushing all four multi-platform images, it attaches these seven archives plus `release-manifest.json`, `images.json` and `SHA256SUMS` to a draft, creates `agentscope-service/service-controlplane/vVERSION` at the exact main-tag commit, then publishes the GitHub Release. Versions containing a prerelease suffix are marked as prereleases. Release notes contain one sentence linking to the official English and Chinese documentation.

Only the GitHub Release publish job needs `contents: write`, using the built-in `GITHUB_TOKEN`; the images job uses the two ACR secrets described in section 5. `SERVICE_IMAGE_REPOSITORY` is an optional repository variable for image references in the packages; it defaults to `sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope`. This workflow builds and pushes the four images; it does not publish Maven/npm/PyPI packages or update the separate HTTP Helm repository or Homebrew tap. Those channels retain their existing workflows and configuration. The SDK tag workflows also run independently on the main tag.

For an existing tag containing this workflow, rerun it without creating another tag:

```bash
gh workflow run service-dist-release.yml --repo agentscope-ai/agentscope-java \
  --ref main -f tag=vVERSION
```

Published Releases are left unchanged. Matching Go tags and matching draft assets are reused; a conflicting tag or checksum stops publication without overwriting it. Rerun a failed **publish job** with its original Actions artifacts; rebuilding archives can produce different bytes and is deliberately not allowed to replace existing draft assets. Keep the already published `v2.1.0-BETA1` and its Go tag unchanged; they predate this workflow. New automation applies to future tagged source containing the workflow. Tags created by `GITHUB_TOKEN` do not trigger further push workflows, so the generated Go module tag does not recursively run release jobs. See [GitHub workflow triggering](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

The HTTP Helm repository and optional Homebrew tap remain independent publication channels.

Users install CLI and Runtime Host with the `go install` commands in the [Runtime Host guide](https://java.agentscope.io/v2/en/service/runtime-host). The optional public personal tap is [chickenlj/homebrew-tap](https://github.com/chickenlj/homebrew-tap). The formula source is retained under `release/homebrew/agentscope-cli.rb`. For each new CLI release, update all four URLs, their verified SHA-256 values, the formula version and its version assertion, then publish the updated formula to the tap. Existing binary archives remain immutable. A future CI job updating this personal repository from the organization repository needs a separate credential with Contents write permission on the tap; the organization's default `GITHUB_TOKEN` does not grant that access.

The Go module requires Go 1.26 and uses the `/v2` module path. In addition to the main `vVERSION` tag, the distribution workflow creates the nested-module tag `agentscope-service/service-controlplane/vVERSION` at the same commit. This lets users install either CLI command with `@v2.1.0-BETA1`; see the bilingual Runtime Host installation page. Future Go releases need both tags at the same commit; administrators only push the main tag. Do not move published tags.

Documentation lives under `docs/v2/{zh,en}/service/`; `docs/docs.json` configures navigation and redirects. Validate both languages with the documentation npm scripts before merging into `main`, Mintlify's deployment branch. Confirm the Mintlify deployment separately from the validation workflow, then check direct page access, links, images, search and language switching.

## Current deployment boundaries

The full Chart deliberately uses one replica and Recreate updates. It does not install PostgreSQL or an RWX provisioner. Go runs startup migrations; Java still uses Hibernate `update`. Database rollback requires a matching backup and original keys, not just an image rollback. Local Environment is opt-in. Model credentials, sandbox services and third-party Coding Agent providers remain user-supplied.
