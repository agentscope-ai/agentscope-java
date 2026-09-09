# SDKs and application integration

Existing Agent applications can retain their own process lifecycle while exposing identity, availability, Session information and execution capabilities to the control plane.

## Choose an integration

| Component | Source directory | Distribution |
| --- | --- | --- |
| Java extension | `agentscope-extensions/agentscope-extensions-aistio` | Maven artifact |
| Python SDK | `agentscope-service/aistio/sdk/python` | `aistio-sdk` wheel / sdist |
| DSH plugin | `agentscope-service/aistio/sdk/dsh` | `@agentscope/dsh-aistio` npm package |
| Coding Agent Host | `agentscope-service/aistio/cmd` | CLI / daemon archive |

SDKs have their own versions. Do not infer package versions from the Service image tag; use the release manifest. Candidate packages not yet available in public registries can be installed from downloaded release artifacts.

```bash
python -m pip install ./aistio_sdk-0.1.0-py3-none-any.whl
npm install ./agentscope-dsh-aistio-0.1.0.tgz
```

Use the actual downloaded filenames. Java applications use the matching `io.agentscope:agentscope-extensions-aistio` version and its dependencies.

## Network and protocol

Check the adapter's transport requirements first. The complete Service Compose and Helm deployments use standalone HTTP mode without ASDP gRPC. Adapters requiring ASDP need a Kubernetes-native Aistio deployment and correctly configured gRPC connectivity. An HTTP port is not a gRPC endpoint.

The control plane must reach the application's advertised callback or contract address. `localhost` inside a container refers to that container; cross-host deployments need reachable addresses. Shared internal tokens are for trusted private service calls. External Hosts use dedicated identity credentials.

## Verify integration

Verify registration and heartbeat, create a Session and read its history, then exercise a supported task dispatch and result callback. Restart the application and verify identity continuity and recovery. Model execution, tools and application lifecycle remain the runtime's responsibility.

Use the package's examples and version notes for exact APIs. A healthy Service deployment does not establish that every third-party framework adapter has been qualified.
