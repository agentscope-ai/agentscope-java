# Publishing components

This guide is for release maintainers. Scripts live in `agentscope-service/release/`. Installation starts with the [quickstart](quickstart.md).

## Artifacts

Publish four images: `agentscope-service-control`, `agentscope-service-gateway`, `agentscope-service-dataplane` and `agentscope-service-scheduler`. The console ships inside the control image.

The release also contains the complete Helm Chart, Compose archive, platform CLI/Runtime Host archives, Python wheel/sdist, DSH npm package, `release-manifest.json` and `SHA256SUMS`. Publish the Java SDK and required libraries through Maven; service applications remain excluded from Maven Central by default.

Service, Java and SDK versions may evolve independently. The manifest records their relationship and source commit. Check Python and DSH package versions before publishing; an already published version must not be overwritten.

## Prepare locally

Use Java 21, Maven, Go as specified by go.mod, Node.js 22, Python 3.10+, Docker Buildx and Helm. Create a Python environment and install `release/requirements.txt` plus the Python SDK dev dependencies.

From the repository root:

```bash
python agentscope-service/release/release.py verify
python agentscope-service/release/release.py package   --version VERSION --repository REGISTRY/NAMESPACE
```

Output defaults to `agentscope-service/release/dist/VERSION/`. Packaging refuses to overwrite an existing directory. Its explicit file allowlist excludes local `.env` files. Candidate packages may use uncommitted source and record dirty status; external pushes require a clean source tree.

## Images and Chart

Build and load a local platform:

```bash
python agentscope-service/release/release.py images --version VERSION   --repository REGISTRY/NAMESPACE --platforms linux/arm64
```

Authenticate Docker and Helm to the target registry. After candidate installation and compatibility checks, publish:

```bash
python agentscope-service/release/release.py images --version VERSION   --repository REGISTRY/NAMESPACE --platforms linux/amd64,linux/arm64 --push
python agentscope-service/release/release.py publish-chart   --version VERSION --repository REGISTRY/NAMESPACE
```

Image pushes request SBOM and provenance attestations and record digests in `image-*.json`. OCI Charts use [Helm's registry publication workflow](https://helm.sh/docs/topics/registries/). Verify anonymous pulls separately if the release is intended to be public.

## Release sequence

Freeze source and versions, complete tests and candidate installation, create the corresponding Git tag, then run the `AgentScope Service release` workflow. It builds by default; publication requires selecting a tag and configuring registry credentials.

Attach artifacts, checksums, digests, tested platforms and limitations to the Release. Publish Maven/Python/npm artifacts and verify installation from a new environment. See `agentscope-service/release/README.md` for package publication commands.

The website workflow deploys `/docs` from `main`. Merge the matching documentation into that publication entry point, then verify both Service navigation trees, direct links, images and search.
