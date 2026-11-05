---
title: "Deploy AgentScope Service"
description: "Start AgentScope Service with Docker Compose, sign in to the console, and run your first Agent."
zh_link: /v2/zh/service/quickstart
---

Start Service locally with Docker Compose, then open Console or call the APIs directly. Local development automatically uses one developer identity and the default namespace, without sign-in, user tokens, application keys, or grant configuration.

Prepare Git, OpenSSL, Docker Engine or Docker Desktop with Compose v2, a DashScope API key, and container-reachable Redis and RocketMQ Cluster Proxy endpoints. Initialize the [five business Lite Topics and groups](/v2/en/service/kubernetes#prepare-rocketmq) before starting Service. Docker supplies the Java, Go, and Node.js tools needed to build the services on the first start.

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
./deploy/init-env.sh 2.1.0-local agentscope-local
# Edit deploy/.env with reachable Redis/MQ endpoints and optional MQ authentication/TLS.
export BUILDER_LOCAL_DEV=true
export BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
docker compose --env-file deploy/.env config --quiet
docker compose --env-file deploy/.env up -d --build --wait --wait-timeout 600
```

These explicit development settings enable one developer identity and Local tool execution; source Compose binds Gateway to loopback. Local tools run in the Hands container. PostgreSQL starts with Compose; Redis and RocketMQ are operated separately. Four migration jobs initialize `cp/rt`, `portal`, `dp` and `ma`, then business-role grants run before services start. Checkpoints and oversized objects are stored in PostgreSQL. Model and external tool providers still require their own credentials.

The first build may take several minutes. On failure, inspect `docker compose --env-file deploy/.env ps -a`, then `docker compose --env-file deploy/.env logs --tail=100`.

## 3. Start using it directly

Open [http://localhost:18080](http://localhost:18080). Console automatically enters with the development identity.

Open **Design → Agents → New agent**, enter a name and **Instructions**, select **AgentScope Managed**, then click **Create & open agent**. Leave **Model** empty to use the default model.

Continue with [your first Managed Agent](/v2/en/service/create-managed-agent) to configure an Environment, create a native Session, submit a `user.message` through `/events` and inspect the resulting Turn. This walkthrough uses the current `/v1/sessions` contract; the compatibility API for other backends is a separate path.

You can also call the API without authentication headers:

```bash
curl -sS --fail-with-body http://localhost:18080/api/v1/agents
```

Next, [run your first Managed Agent through the API](/v2/en/service/create-managed-agent). Stop with `docker compose --env-file deploy/.env down`; data volumes remain. See [production deployment](/v2/en/service/kubernetes#production-api-access) for authentication and authorization, and [operations](/v2/en/service/operations) for backups and upgrades.

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
