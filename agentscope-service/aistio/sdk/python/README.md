# aistio Python SDK

Integration SDK for AgentScope Service: framework observation, session events, application contracts and collaboration clients.

Install the `aistio-sdk` version listed in the Service release manifest, or install a downloaded wheel:

```bash
python -m pip install ./aistio_sdk-VERSION-py3-none-any.whl
```

Python 3.9+ is required by the package metadata. grpcio and protobuf constraints are declared in `pyproject.toml`. Optional OpenClaw WebSocket support is available through `aistio-sdk[openclaw]`.

## Choose a transport before connecting

The complete Service Compose/Helm deployment runs standalone HTTP mode without ASDP gRPC. Framework instrumentation requiring ASDP needs a Kubernetes-native Aistio installation and a reachable gRPC listener. Do not use the public HTTP port as a gRPC address. The control plane must also reach your application's advertised HTTP contract endpoint.

See the [AgentScope Service documentation](https://java.agentscope.io/v2/en/service/integrations.html) and the examples in this package's source tree for integration paths. Service image versions and SDK package versions are independent; use the matching release manifest.

## Develop

```bash
python -m pip install -e '.[dev]'
python -m pytest
python -m build
```

Licensed under Apache-2.0; see `LICENSE`.
