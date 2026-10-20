---
title: "Deploy AgentScope Service"
description: "Start AgentScope Service with Docker Compose, sign in to the console, and run your first Agent."
zh_link: /v2/zh/service/quickstart
---

Start AgentScope Service locally with Docker Compose, then create and use an Agent in your browser. This guide uses the published `2.1.0-BETA1` prerelease.

Prepare Docker Engine or Docker Desktop with Compose v2, Bash, curl, OpenSSL, and a DashScope API key. If your team already provides Service, use the address and account supplied by your administrator and start at step 3.

<span id="1-start"></span>

<span id="prepare"></span>
<span id="1-initialize-deployment-and-configure-a-model"></span>

## 1. Download and configure

Download the Compose bundle, extract it, and generate the configuration file:

```bash
curl -fLO https://github.com/agentscope-ai/agentscope-java/releases/download/v2.1.0-BETA1/agentscope-service-2.1.0-BETA1-compose.tar.gz
tar -xzf agentscope-service-2.1.0-BETA1-compose.tar.gz
cd agentscope-service
./init-env.sh 2.1.0-BETA1 sca-registry.cn-hangzhou.cr.aliyuncs.com/agentscope
```

Edit the generated `.env` to supply your DashScope API key and enable tool execution for local evaluation:

```dotenv
DASHSCOPE_API_KEY=YOUR_DASHSCOPE_API_KEY
BUILDER_ALLOW_LOCAL_ENVIRONMENT=true
```

Local tools run inside the Dataplane container. See [Agent configuration](/v2/en/service/managed-agent-configuration) and [Environments](/v2/en/service/environments) for other models and execution environments.

<span id="2-start-and-sign-in"></span>

## 2. Start Service

```bash
docker compose up -d --wait --wait-timeout 600
```

Compose pulls the images and starts the database and all Service components. Once the command completes successfully, open [http://localhost:18080](http://localhost:18080).

If startup fails, run `docker compose ps -a` to check status and `docker compose logs --tail=100` to inspect errors.

<span id="2-sign-in"></span>
<span id="3-configure-execution"></span>

## 3. Sign in and start using Service

On a new deployment, sign in as **`admin`** with the value of **`CONTROL_PLANE_BOOTSTRAP_PASSWORD`** from `.env`. Change your password in **Profile** after signing in.

Open **Design → Agents → New agent**, enter a name (for example, `notes-assistant`) and **Instructions**, select **AgentScope Managed** as the **Runtime**, and click **Create & open agent**. Leave **Model** empty to use the default model configured above.

In the Agent detail page, open **Connections → Session API**, enter a **Task message** (for example, “Turn these meeting notes into action items: Li will finish the installation guide on Friday; review it next Monday”), and click **Create Session and submit**. For your first test, leave **Application API key** empty to use your signed-in identity. Wait for the Turn to complete and inspect the response to verify the model call. See the [Visual Console guide](/v2/en/service/console/index) for the full interface walkthrough.

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

Next, [run your first Managed Agent through the API](/v2/en/service/create-managed-agent) to verify file tools and prepare application integration. See [production installation](/v2/en/service/kubernetes#self-hosting) for deployment planning, networking, and remote access, and [operations](/v2/en/service/operations#compose-operations) for persistence, upgrades, and backups.
