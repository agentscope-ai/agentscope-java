# AgentScope Service release runbook

[中文发布手册（项目管理员操作指南）](README_zh.md)

Run all commands from the monorepo root. Development continues in the existing checkout; these scripts do not create a worktree or modify version files automatically.

## Distribution contract

| Component | Delivery | Version source |
| --- | --- | --- |
| Control plane + Dashboard | `agentscope-service-control` image | Service release version, injected into Go build |
| Gateway / Dataplane / Scheduler | Three `agentscope-service-*` images | Service release tag; Java revision recorded separately |
| Complete deployment | Compose archive + `agentscope-service` Helm Chart (OCI) | Service release version |
| `agentscope`, `aistioctl`, Runtime Host | Linux/macOS amd64/arm64 archives | Service release version |
| Java Application SDK | `io.agentscope:agentscope-extensions-aistio` and reactor dependencies | Root `revision` |
| Python SDK | `aistio-sdk` wheel and sdist | `aistio/sdk/python/pyproject.toml` and `aistio/__init__.py` |
| DSH plugin | `@agentscope/dsh-aistio` npm tarball | `aistio/sdk/dsh/package.json` and lockfile |

The front end is private and bundled into the control image. `service-common` and executable Service modules remain excluded from Maven Central (`maven.deploy.skip=true`); they are not required by external Java SDK users. PostgreSQL is a separately operated dependency, not an AgentScope-published image. The legacy Aistio Chart remains a separate Kubernetes-native offering. The complete Service Chart uses standalone HTTP, without ASDP gRPC.

## 1. Freeze source and choose versions

Choose a SemVer Service version and a registry namespace. `2.0.3-rc.1` in examples is a candidate, not a claim of publication. Update Python/DSH package versions before a new public package release; do not republish their existing `0.1.0` versions. Check both Python version declarations and the npm lockfile. Record the Java revision and required released Maven dependencies. Do not publish SDK POMs with unresolved SNAPSHOT dependencies.

Keep a source backup before cleanup. Generated UI files, build outputs, and raw test evidence are not source inputs. `release.py hygiene` rejects tracked generated artifacts. Generated CRDs/protobuf files, documentation illustrations and real test fixtures remain source distribution inputs.

## 2. Install build tools and verify

Use Java 21, Maven, Go from `aistio/go.mod`, Node.js 22, Python 3.10+, Docker Buildx and Helm 3.17+ (or compatible versions).

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r agentscope-service/release/requirements.txt -e 'agentscope-service/aistio/sdk/python[dev]'
python agentscope-service/release/release.py verify
```

Set `AISTIO_TEST_POSTGRES_DSN` to a **disposable test database** for PostgreSQL integration tests; never point tests at development or production data. The verifier uses `go test -p 1` so packages sharing one PostgreSQL database do not contend during concurrent-index migrations. Some Go controller tests additionally require envtest assets (`make test-integration` in `aistio`). Run the repository-wide `mvn clean verify` before release submission. The release verifier explicitly selects all three Java service modules and their dependencies: selecting only the aggregator with `-pl agentscope-service` does not test its children.

## 3. Package candidates

```bash
python agentscope-service/release/release.py package \
  --version 2.0.3-rc.1 --repository REGISTRY/NAMESPACE
```

Default output: `agentscope-service/release/dist/2.0.3-rc.1/`. Existing output directories are not overwritten. Use another `--output` for a new rehearsal. The package includes deploy files, CLI/Host archives, Helm, Python and npm artifacts, `release-manifest.json` and `SHA256SUMS`. Packaging uses an explicit allowlist and never includes deploy `.env` files. Dirty source is recorded for local candidates.

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

The manual `AgentScope Service release` workflow verifies and packages before building images. `publish=false` builds a local amd64 candidate. `publish=true` requires a selected Git tag and `SERVICE_REGISTRY_HOST`, `SERVICE_REGISTRY_USER`, `SERVICE_REGISTRY_TOKEN` repository secrets. Registry namespace is an explicit input. The workflow does not publish Maven, PyPI, npm or a public GitHub Release automatically.

## 6. Publish SDK packages

After confirming package ownership, version availability and credentials:

```bash
python -m twine check agentscope-service/release/dist/VERSION/aistio_sdk-*.whl agentscope-service/release/dist/VERSION/aistio_sdk-*.tar.gz
# Upload only the Python artifacts, never the Compose/CLI tar.gz files:
python -m twine upload agentscope-service/release/dist/VERSION/aistio_sdk-*.whl agentscope-service/release/dist/VERSION/aistio_sdk-*.tar.gz
npm publish agentscope-service/release/dist/VERSION/agentscope-dsh-aistio-*.tgz --access public
```

For an npm prerelease, use an explicit prerelease dist-tag such as `--tag next`. Review the actual Python sdist filename before uploading. Maven SDK publication uses the repository's existing release profile and signing/Central credentials:

```bash
mvn -B -ntp -pl agentscope-extensions/agentscope-extensions-aistio -am \
  -Drevision=JAVA_RELEASE_VERSION -Prelease deploy
```

This command includes required reactor dependencies and parent POMs; review the reactor and publish them only under new, intentional versions. Do not enable Maven deployment for the executable Service modules just to publish the SDK.

The current release profile does not enable `autoPublish`. After Maven succeeds, inspect the deployment in Central Portal, finish the manual Publish step, and verify availability from a consumer project. See the [Central publishing plugin documentation](https://central.sonatype.org/publish/publish-portal-maven/).

## 7. Publish documentation and release notes

Attach package archives, manifest, checksums and image metadata to the Release. List exact image references/digests, OCI Chart reference, SDK coordinates, tested platforms, database compatibility, upgrade procedure and known limitations. Verify anonymous downloads/pulls when public distribution is intended.

Documentation lives under `docs/v2/{zh,en}/service/`. `_toc.yml` and `_config.yml` register every page and the Service tab. Build and inspect both languages before merging into `main`, the current website workflow's deployment source. Check direct page access, links, images, search and language switching after deployment.

## Current deployment boundaries

The full Chart deliberately uses one replica and Recreate updates. It does not install PostgreSQL or an RWX provisioner. Go runs startup migrations; Java still uses Hibernate `update`. Database rollback requires a matching backup and original keys, not just an image rollback. Local Environment is opt-in. Model credentials, sandbox services and third-party Coding Agent providers remain user-supplied.
