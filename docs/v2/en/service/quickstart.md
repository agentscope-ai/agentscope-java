---
title: "Deploy AgentScope Service"
description: "Start AgentScope Service with Docker Compose, sign in to the console, and run your first Agent."
zh_link: /v2/zh/service/quickstart
---

Start Service locally with Docker Compose, then open Console or call the APIs directly. Local development automatically uses one developer identity and the default namespace, without sign-in, user tokens, application keys, or grant configuration.

Prepare Git, Docker Engine or Docker Desktop with Compose v2, and a DashScope API key. Docker supplies the Java, Go, and Node.js tools needed to build the services on the first start.

<Note>
Unauthenticated local mode requires a source build containing this feature. Published `2.1.0-BETA1` images do not support it. See [production deployment](/v2/en/service/kubernetes) for published images, Helm installation, and identity configuration.
</Note>

## 1. Prepare the source and model

```bash
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java/agentscope-service
export DASHSCOPE_API_KEY="YOUR_DASHSCOPE_API_KEY"
```

With an existing checkout, enter its `agentscope-service` directory and set the model credential.

## 2. Start Service

```bash
docker compose up -d --build --wait --wait-timeout 600
```

Source Compose enables `BUILDER_LOCAL_DEV=true` and Local tool execution by default and binds service ports to the local machine. Local tools run in the Dataplane container. Model and external tool providers still require their own credentials.

The first build may take several minutes. On failure, inspect `docker compose ps -a`, then `docker compose logs --tail=100`.

## 3. Start using it directly

Open [http://localhost:18080](http://localhost:18080). Console automatically enters with the development identity.

Open **Design → Agents → New agent**, enter a name and **Instructions**, select **AgentScope Managed**, then click **Create & open agent**. Leave **Model** empty to use the default model.

Under **Connections → Session API**, enter a **Task message**, such as “Turn these meeting notes into action items: Lee finishes the installation guide Friday; review on Monday.” Leave **Application API key** empty and click **Create Session and submit**. Wait for the response.

You can also call the API without authentication headers:

```bash
curl -sS --fail-with-body http://localhost:18080/api/v1/agents
```

Next, [run your first Managed Agent through the API](/v2/en/service/create-managed-agent). Stop with `docker compose down`; data volumes remain. See [production deployment](/v2/en/service/kubernetes#production-api-access) for authentication, authorization, , and [operations](/v2/en/service/operations) for backups and upgrades.

<span id="1-start"></span>
<span id="prepare"></span>
<span id="1-initialize-deployment-and-configure-a-model"></span>
<span id="2-start-and-sign-in"></span>
<span id="2-sign-in"></span>
<span id="3-configure-execution"></span>
<span id="self-hosting"></span>
<span id="three-deployment-boundaries"></span>
<span id="choose-a-deployment-path"></span>
<span id="hand-over-a-usable-platform"></span>
<span id="operate-the-platform"></span>
<span id="3-prepare-api-identity-and-namespace"></span>
<span id="4-prepare-tool-execution"></span>
<span id="5-check-readiness"></span>
<span id="deployment-boundaries-and-production-planning"></span>
<span id="network-surfaces"></span>
<span id="enable-remote-access"></span>
<span id="persist-data"></span>
<span id="change-configuration-or-version"></span>
<span id="stop-resume-and-diagnose"></span>
<span id="1-download-and-configure"></span>
<span id="3-sign-in-and-start-using-service"></span>
