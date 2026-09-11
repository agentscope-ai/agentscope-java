---
title: "Environments: execution locations"
---

[简体中文](/v2/zh/service/environments)

**Resources → Environments** defines where Managed Agents execute file, Shell and other tools. It is separate from a definition Workspace and from a Hosted Agent's Runtime Host.

## Choose a type

| Type | Execution model |
| --- | --- |
| local | Runs with the Dataplane; requires Local to be enabled by an administrator |
| sandbox | Isolated Shell/filesystem execution through E2B |
| remote | Shared BaseStore filesystem without Shell execution |
| self_hosted | Execution supplied by a Worker you operate |

In Docker, Local means inside the Dataplane container, not arbitrary access to the host filesystem. Choose a backend according to production isolation and networking requirements.

## Configure and verify

Create an Environment with a name and type, then edit its backend-specific JSON connection settings. Type is read-only after creation. Credentials and capabilities must match the selected backend; Runtime Host enrollment credentials are not Worker credentials.

Select it in the Agent's advanced settings or environment binding. Verify connection, working directory and permissions with a read-only file operation before enabling writes or commands.

## Self-hosted execution

A self-hosted Worker connects using an Environment API key. Save the key when created and supply it through the Worker's connection configuration. Check online status and a real tool call after connection. Workers execute Managed tools; Runtime Hosts run Coding Agent providers. They are not interchangeable processes.

## Diagnose tool failures

Check Environment availability, network and authentication, directory mounts, required executables and permissions. A successful model reply does not prove that file tools work. Verify configuration changes with new work and update every consumer after key rotation.

Next: [Managed Agents](/v2/en/service/managed-agent) · [Configuration](/v2/en/service/configuration).

## E2B sandbox example

An administrator supplies `BUILDER_E2B_API_KEY` through deployment configuration. Create a sandbox Environment and use this Config:

```json
{
  "templateId": "base",
  "isolationScope": "SESSION",
  "sandboxTimeoutSeconds": 300
}
```

Select a custom E2B template for additional executables. `workspaceRoot` controls the sandbox path; `persistenceMode` can be `TAR` or `NATIVE_SNAPSHOT`. Verify save/restore with the chosen template and backend. The remote type is filesystem-only, not a remote Shell Worker.

## Run a self-hosted Worker from the published image

Create a self_hosted Environment and save its API key. Set `SCHEDULER_IMAGE` to the full scheduler image reference from the manifest, `BASE_URL` to a Gateway URL reachable from the Worker, and `ENVIRONMENT_ID`/`ENVIRONMENT_KEY` to the new Environment's values.

```bash
docker run --rm \
  --name agentscope-hands \
  -v agentscope-hands:/data \
  --entrypoint java "$SCHEDULER_IMAGE" \
  -Dloader.main=io.agentscope.builder.worker.HandsWorkerMain \
  -cp /app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --base-url "$BASE_URL" \
  --environment-id "$ENVIRONMENT_ID" \
  --environment-key "$ENVIRONMENT_KEY" \
  --hands-root /data/hands \
  --worker-id hands-1
```

The Worker makes outbound Gateway requests without exposing an inbound port. Bind a Managed Agent to this Environment, request a small file read/write and observe tool suspension followed by Worker results and resumed execution. Working files persist in the named volume. Preinstall task-specific programs in your Worker image.

Use your process/container manager for restarts and distinct worker IDs for multiple Workers. Inspect claimed work before stopping; process shutdown is not business-task cancellation.
