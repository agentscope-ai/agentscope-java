---
title: "运行第一个托管 Agent"
description: "创建基于 HarnessAgent 的 Managed Agent，执行文件读写任务并核对结果。"
en_link: /v2/en/service/create-managed-agent
---

本教程将带你创建并运行一个基于 **HarnessAgent 内核**的托管 Agent，让它把会议记录整理成待办事项，写入文件后再读取核对。你会依次完成 Agent 配置、任务提交和结果检查，了解怎样通过 API 使用一个能够执行工具的 Agent。整个过程由 Service 负责运行 Agent，你无需为这次练习另外编写和部署 Agent 应用。

<span id="准备执行资源"></span>
<span id="创建统一-agent-身份与托管定义"></span>
<span id="创建会话并提交一轮任务"></span>
<span id="增加能力与发布"></span>

## 1. 准备平台与执行资源

开始之前，请先完成[部署并准备 Service](/v2/zh/service/quickstart)，并继续使用执行该教程时的 Bash 终端。后续命令会向 `BASE_URL` 指定的 Service 发送请求，并通过 `TOKEN` 验证你的平台身份。资源会创建在 `TENANT` 和 `NAMESPACE` 选定的空间中，文件工具则使用 `ENVIRONMENT_ID` 指定的执行环境。这些变量都来自前面的准备步骤，如果换了终端，需要先重新设置它们。

如果团队已经部署了 Service，可以从部署教程的第 3 步开始，登录自己的账号、选择有权限的空间，并准备可用的执行环境。运行命令的终端需要安装 curl 和 jq；平台配置的模型需要支持工具调用，Agent 才能完成本次练习中的文件读写。

本教程通过 Session API 验证执行效果，这也是业务应用使用 Agent 的调用方式。Session 保存会话上下文和执行记录，每次提交的任务对应一个 Turn。后面的步骤会创建这两个资源，并演示如何读取任务进度。验证通过后，可以直接为业务应用配置调用凭据，继续使用同一个 Agent。偏好页面操作时，可以参考 [可视化Console](/v2/zh/service/console/index#console-agents)。

## 2. 创建资料助手

下面的请求会在平台中创建一个名为“资料助手”的 Agent。请求中的 `binding.kind` 设置为 `managed`，表示由 Service 使用 HarnessAgent 内核运行它；`definition` 则描述它应如何处理任务。我们在指令中要求它保留材料中的事实，将缺失信息标为待确认，并在写入文件后读取核对。

为了完成这项工作，示例只启用了文件读取和写入工具。它们会使用 `defaultEnvironmentId` 指定的执行环境中的工作目录，因此生成的文件所在位置取决于该环境，而不是运行 curl 命令的终端目录。请求没有指定模型，Agent 将使用部署时配置的默认模型。

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"notes-assistant", displayName:"资料助手",
      binding:{kind:"managed"},
      definition:{name:"资料助手", maxIters:20, defaultEnvironmentId:$env,
        system:"根据材料整理任务、负责人、期限和待确认事项。缺失的信息标为待确认，不虚构事实。需要文件交付时，写入文件并读取核对，在最终回复中同时给出内容。",
        tools:[{type:"agent_toolset", defaultConfig:{enabled:false}, configs:[
          {name:"read", enabled:true, permissionPolicy:{type:"always_allow"}},
          {name:"write", enabled:true, permissionPolicy:{type:"always_allow"}}
        ]}]}
    }')")
export AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '{agent, binding}'
```

创建成功后，响应会包含 Agent 身份、运行绑定及相关配置。命令从 `agent.id` 中取出平台分配的 ID，并保存到 `AGENT_ID`；后续创建会话时会用到它，请保留这个变量。请求中的 `definition.tools` 也已保存到这个 Agent 的定义中，因此创建 Session 时只需引用 `AGENT_ID`，不需要再单独绑定这两个文件工具。

请求中的 `agentKey` 是你为 Agent 选择的稳定业务标识，和平台返回的 `AGENT_ID` 用途不同。如果重复练习时希望继续使用这个 Agent，可以保留原来的 `AGENT_ID` 并跳过创建步骤；如果确实要创建另一个 Agent，则需要更换 `agentKey`。重复发送创建请求不会用新配置覆盖已有 Agent 的定义。

两个工具的权限策略都设为 `always_allow`，因此 Agent 在本次练习中可以直接读写文件，无需逐次等待确认。这个设置适合受控的体验环境。将工具接入真实业务前，应根据它能访问的数据和执行的操作，决定是否需要[人工确认](/v2/zh/service/tools#tool-permissions)。其他工具的配置方式见[工具与 MCP](/v2/zh/service/tools)。

## 3. 创建会话并提交任务

Agent 创建后，还需要为这次交互创建一个 Session。Session 用来保存这段会话中的消息和执行记录，使后续提交能够沿用已有上下文。下面的请求通过 `AGENT_ID` 指定使用哪个 Agent，并将返回的会话 ID 保存为 `SESSION_ID`。创建会话本身不会让 Agent 开始处理会议记录。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" '{target:{type:"agent",id:$agent}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

会话准备好后，向它的 `/turns` 接口提交会议记录和处理要求。这次提交会创建一个 Turn，由平台安排在后台执行；命令会把它的 ID 保存为 `TURN_ID`，供后面查询这一轮任务的进展。请求头中的 `Idempotency-Key` 用来帮助平台识别重复提交，本次练习使用固定值 `notes-check-001`。

```bash
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-check-001' \
  --data '{"message": "小李周五完成安装说明，下周一评审，具体时间待确认。请整理待办，写入 meeting-actions.md，再读取文件核对，最后给出文件内容。"}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
printf '%s' "$TURN_JSON" | jq '{id, status, error}'
```

提交接口返回 HTTP 状态码 `202 Accepted`，表示服务已经接收了这次请求，不能据此判断任务已经完成。如果返回的 JSON 中 `status` 为 `queued`，则表示这轮任务正在排队等待执行。你需要继续查询任务状态或观察执行事件，才能知道 Agent 是否完成了工作。

如果请求因网络超时等原因没有收到响应，可以在同一个 Session 中重新提交，但必须沿用原来的 `Idempotency-Key` 和相同的请求内容。这样，即使上一次请求已经被服务接收，平台也会返回原来的 Turn 记录，不会因为这次重试再创建一轮任务。这就是这里的幂等性。如果保留同一个 key 却修改了消息内容，接口会返回 `409 Conflict`；只有确实要提交一轮新任务时，才应使用新的 key。重试任务提交时，也不需要重新执行前面的创建会话命令。

如果你正在通过这些 API 开发应用，应保存 `SESSION_ID` 和 `TURN_ID`。用户刷新页面时，根据这些 ID 读取已有会话和任务的状态，恢复页面内容即可。刷新本身不代表用户又提交了一次任务，因此不应重新发送输入请求。下一节会演示如何读取已有状态并继续跟踪进展。

## 4. 读取进度与工具结果

任务提交后，可以先读取 Session 的快照。快照汇总了截至某个时间点已经保存的消息、工具执行情况和任务状态，既适合首次查看进展，也适合在页面刷新后恢复已有内容。下面的命令会读取并打印这份快照，同时保存其中的 `as_of`，供后续跟踪新的执行事件。

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN")
printf '%s' "$SNAPSHOT" | jq '{items, tools, turns, required_actions}'
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
```

在返回的数据中，`items[].data.item.content` 包含会话消息的正文，`tools` 包含工具调用及其结果。检查文件工具的记录，可以确认 Agent 是否实际写入了 `meeting-actions.md`，以及随后是否成功读回文件。快照中的 `turns` 用于查看各轮任务的状态；如果其中已经记录了本轮任务的完成结果，就可以直接核对交付，无需再等待后续事件。

如果任务还在执行，可以通过下面的 SSE 接口持续接收更新。SSE 是服务向客户端推送事件的连接方式；`as_of` 标记了快照已包含的事件位置，将它作为 `after` 参数传入后，服务会从这个位置之后继续发送事件。这样，读取快照和建立连接之间产生的更新也能被接收到。

```bash
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" --data-urlencode "after=$CURSOR"
```

这条命令会保持连接并打印陆续到达的事件。当收到 `turn.completed`，并且事件中的 `turn_id` 与保存的 `TURN_ID` 一致时，才说明本轮任务已经成功完成。某个文件工具执行完毕，只表示这一步结束，Agent 可能还需要读取文件或整理最终回复；如果任务失败或需要人工处理，应根据相应状态继续排查，而不是一直等待完成事件。

SSE 连接仅用于观察进展，断开它不会取消后台任务。你可以在这个终端中按 Ctrl-C 停止接收事件，然后执行下面的命令，直接查询本轮任务的最新状态，并重新读取会话内容。如果之前的快照已经显示任务完成，也可以跳过 SSE 连接，直接执行这两条查询。

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '{id, status, error}'
curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN" | jq '{items, tools, required_actions}'
```

## 5. 核对交付

先检查上一步查询到的 Turn 状态。`status` 为 `completed` 表示本轮执行已经成功结束，接下来还需要核对它是否完成了你要求的工作。在工具结果中，应能看到 `meeting-actions.md` 写入成功，并且之后又被成功读取；Agent 的最终回复应包含读回并核对过的文件内容，方便你直接检查交付。

核对内容时，应确认“小李在周五完成安装说明”和“下周一进行评审”这两项信息被保留，而评审的具体时间仍标记为待确认。模型可以采用不同的排版，但不能把缺失的信息自行补成确定事实。以上是这次练习的验收标准，实际是否通过，需要以你读取到的工具结果和回复内容为依据。

如果任务没有成功完成，先查看返回的 `error.code` 和工具执行结果。模型连接报错时，需要检查部署中的模型配置和凭据是否可用；文件读写失败时，则需要检查所选 Environment 是否正常，以及工具是否有权访问目标目录。先定位失败发生在哪一步，再决定怎样修正配置或继续执行。

如果状态为 `requires_action`，说明任务正在等待进一步处理，应查看快照中的 `required_actions`，根据具体请求提供确认或所需结果。如果执行失败或中断，可以按[会话与任务](/v2/zh/service/session-event-log)中的说明检查恢复条件，再决定是否继续原来的 Turn。直接换一个 key 重发任务会创建新的一轮，无法替代对原任务状态和已执行操作的检查。

文件写入成功后，`meeting-actions.md` 保存在工具执行环境的工作目录中。写入工具只负责生成这个文件，平台不会因此自动为它创建可通过 API 下载的 Artifact。如果需要让业务应用或用户下载文件，还需要按[文件与产物](/v2/zh/service/files)中的步骤上传文件或发布引用，并确认接收方具有相应的下载权限。

## 下一步

完成这次验证后，请保留 `AGENT_ID`，继续阅读[通过 Session API 接入应用](/v2/zh/service/service-api)。应用会直接使用刚才创建的 Agent，并保存自己业务对象与 Session、Turn 的关联。

如果它还需要具备更多专业能力，可以先通过 [Skills](/v2/zh/service/workspaces#skills)补充任务处理方法，或配置 [MCP 工具](/v2/zh/service/tools)来访问所需的外部系统。需要在不同会话间复用知识时，可以进一步了解 [Memory](/v2/zh/service/memory)。调整配置后，应使用新会话验证实际效果，再接入业务应用；当工作需要多个独立 Agent 分工完成时，可以参考[多 Agent 编排](/v2/zh/service/orchestration)。
