---
title: "Run your first Managed Agent"
description: "Create a HarnessAgent-based Managed Agent, execute file tools, and verify the result."
zh_link: /v2/zh/service/create-managed-agent
---

This tutorial walks you through creating and running a Managed Agent built on the **HarnessAgent core**. The Agent will organize meeting notes into action items, write them to a file, and read the file back to check it. You will configure the Agent, submit a task, and inspect the result to learn how to use an Agent that can execute tools through the API. Service runs the Agent throughout this process, so you do not need to develop and deploy a separate Agent application for the exercise.

<span id="prepare-execution-resources"></span>
<span id="create-the-identity-and-managed-definition"></span>
<span id="create-a-session-and-submit-work"></span>
<span id="add-capabilities-and-publish"></span>

## 1. Prepare platform and execution resources

Before you begin, complete [Deploy and prepare Service](/v2/en/service/quickstart) and continue in the same Bash terminal. The commands send requests to the Service at `BASE_URL` and authenticate your platform account with `TOKEN`. Resources are created in the scope selected by `TENANT` and `NAMESPACE`, while file tools use the environment identified by `ENVIRONMENT_ID`. These variables come from the preparation steps; if you open another terminal, set them again before continuing.

If your team already provides a Service deployment, start at step 3 of the deployment guide to sign in, select an authorized space, and prepare an available execution environment. The terminal needs curl and jq installed. The platform’s model must support tool calls so the Agent can read and write files during this exercise.

This tutorial verifies execution through the Session API, which business applications also use. A Session stores conversation context and execution history; each submitted task is a Turn. The following steps create both resources and observe their progress. Once the task works, configure application credentials and continue using the same Agent. For the interface walkthrough, see the [Visual Console](/v2/en/service/console/index#console-agents).

## 2. Create the notes assistant

The following request creates an Agent named “Notes assistant” on the platform. Setting `binding.kind` to `managed` tells Service to run it using the HarnessAgent core, while `definition` describes how it should handle tasks. The instructions require it to preserve the supplied facts, mark missing information as unconfirmed, and read files back after writing them.

The example enables only the file reading and writing tools needed for this task. They use the working directory in the environment selected by `defaultEnvironmentId`, so the generated file’s location depends on that environment rather than the terminal directory where you run curl. Because the request does not specify a model, the Agent uses the deployment’s default model.

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"notes-assistant", displayName:"Notes assistant",
      binding:{kind:"managed"},
      definition:{name:"Notes assistant", maxIters:20, defaultEnvironmentId:$env,
        system:"Organize materials into tasks, owners, deadlines, and open questions. Mark missing information as unconfirmed; do not invent facts. For file delivery, write the file and read it back for verification, and also include its contents in the final response.",
        tools:[{type:"agent_toolset", defaultConfig:{enabled:false}, configs:[
          {name:"read", enabled:true, permissionPolicy:{type:"always_allow"}},
          {name:"write", enabled:true, permissionPolicy:{type:"always_allow"}}
        ]}]}
    }')")
export AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '{agent, binding}'
```

After creation succeeds, the response includes the Agent’s identity, runtime binding, and related configuration. The command extracts the platform-assigned ID from `agent.id` and saves it as `AGENT_ID`. Keep this variable because you will use it to create a session and later publish the service.

The request's `definition.tools` is now saved in this Agent's definition. Creating a Session only needs to reference `AGENT_ID`; there is no separate binding step for the two file tools.

The request’s `agentKey` is a stable business identifier you choose for the Agent; it serves a different purpose from the returned `AGENT_ID`. To repeat the exercise with the same Agent, retain its `AGENT_ID` and skip creation. To create a separate Agent, choose another `agentKey`. Sending the creation request again does not overwrite an existing Agent’s definition with new configuration.

Both tools use the `always_allow` permission policy, so the Agent can read and write files during this exercise without waiting for confirmation on each call. This setting suits a controlled evaluation environment. Before connecting tools to a real business system, decide whether they need [human confirmation](/v2/en/service/tools#tool-permissions) based on the data they can access and the operations they can perform. See [tools and MCP](/v2/en/service/tools) to configure other tools.

## 3. Create a session and submit a task

After creating the Agent, create a Session for this interaction. A Session stores the conversation’s messages and execution records so later submissions can use the existing context. The following request selects the Agent through `AGENT_ID` and saves the returned session ID as `SESSION_ID`. Creating the session alone does not start processing the meeting notes.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" '{target:{type:"agent",id:$agent}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

With the session ready, submit the meeting notes and processing instructions to its `/turns` endpoint. This submission creates a Turn for the platform to execute in the background. The command saves its ID as `TURN_ID` so you can follow this task later. The `Idempotency-Key` request header helps the platform recognize duplicate submissions; this exercise uses the fixed value `notes-check-001`.

```bash
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-check-001' \
  --data '{"message": "Li will finish the installation guide on Friday. Review is next Monday, with the exact time unconfirmed. Organize the action items, write meeting-actions.md, read it back to verify it, and include its contents in your final response."}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
printf '%s' "$TURN_JSON" | jq '{id, status, error}'
```

The submission endpoint returns HTTP status `202 Accepted` to indicate that Service has received the request. This response alone does not establish that the task has finished. If the returned JSON has `status: queued`, the Turn is waiting to execute. Continue querying its status or observing execution events to find out whether the Agent has completed the work.

If a network timeout or similar problem prevents you from receiving the response, retry the submission in the same Session with the original `Idempotency-Key` and identical request content. If Service already received the first request, it returns the existing Turn record instead of creating another Turn for the retry. This is what idempotency means here. Keeping the key but changing the message returns `409 Conflict`; use a new key only when you intend to submit a new task. Retrying a task submission does not require running the session creation command again.

If you are building an application with these APIs, save `SESSION_ID` and `TURN_ID`. When a user refreshes the page, use those IDs to retrieve the existing conversation and task state and restore the interface. A page refresh does not mean the user has submitted another task, so it should not resend the input request. The next section shows how to read existing state and continue following progress.

## 4. Read progress and tool results

After submitting the task, read the Session snapshot. It brings together the messages, tool activity, and task states saved up to a particular point, making it useful both for an initial progress check and for restoring a page after a refresh. The following command prints the snapshot and saves its `as_of` value so you can follow subsequent execution events.

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN")
printf '%s' "$SNAPSHOT" | jq '{items, tools, turns, required_actions}'
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
```

In the response, `items[].data.item.content` contains message content, while `tools` contains tool calls and their results. Inspect the file tool records to check whether the Agent actually wrote `meeting-actions.md` and then successfully read it back. The snapshot’s `turns` collection shows the state of each task. If it already records this Turn’s completion, you can check the delivery directly without waiting for more events.

If the task is still running, use the following SSE endpoint to receive updates continuously. SSE is a connection through which the server pushes events to the client. The snapshot’s `as_of` value marks the event position it already includes. Passing that value as `after` asks the server to send subsequent events, including updates produced between reading the snapshot and establishing the connection.

```bash
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" --data-urlencode "after=$CURSOR"
```

This command keeps the connection open and prints events as they arrive. A `turn.completed` event confirms successful completion of this task when its `turn_id` matches your saved `TURN_ID`. A file tool finishing means only that step has ended; the Agent may still need to read the file or compose its final reply. If the task fails or needs human action, follow that state instead of waiting indefinitely for a completion event.

The SSE connection is for observing progress, and disconnecting it does not cancel the background task. Press Ctrl-C in this terminal to stop receiving events, then run the following commands to query the Turn’s latest state and read the conversation again. If the earlier snapshot already showed completion, you can skip the SSE connection and run these two queries directly.

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '{id, status, error}'
curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN" | jq '{items, tools, required_actions}'
```

## 5. Verify delivery

First check the Turn state returned by the preceding query. A `status` of `completed` means execution has finished successfully; you still need to check whether it accomplished the requested work. The tool results should show a successful write of `meeting-actions.md` followed by a successful read. The Agent’s final reply should include the file contents it read and checked so you can inspect the delivery directly.

Check that the content preserves Li’s responsibility to finish the installation guide on Friday and the review scheduled for next Monday. The exact review time must remain unconfirmed. The model may format the result differently, but it must not turn missing information into asserted facts. These are the exercise’s acceptance criteria; determine whether they pass from the tool results and reply returned by your own execution.

If the task did not complete successfully, inspect `error.code` and the tool results first. For a model connection error, check that the deployment’s model configuration and credentials are usable. For a file operation failure, check that the selected Environment is available and that the tools can access the target directory. Identify the step that failed before deciding how to correct the configuration or continue execution.

If the state is `requires_action`, the task is waiting for further handling. Inspect `required_actions` in the snapshot and provide the confirmation or result requested. If the state is `failed` or `interrupted`, follow [sessions and tasks](/v2/en/service/session-event-log) to check recovery conditions before deciding whether to resume the original Turn. Resubmitting with a different key creates a new Turn; it does not replace checking the original task’s state and the operations it already performed.

After a successful write, `meeting-actions.md` resides in the tool execution environment’s working directory. The write tool creates the file, but the platform does not automatically publish it as an Artifact that can be downloaded through the API. To let an application or user download it, follow [files and artifacts](/v2/en/service/files) to upload the file or publish a reference, and check that the recipient has the required download permissions.

## Next

After verifying execution, keep `AGENT_ID` and continue to [integrate through the Session API](/v2/en/service/service-api). Your application uses the same Agent and records the relationship between its business objects, Sessions and Turns.

If the Agent needs more specialist capabilities first, use [Skills](/v2/en/service/workspaces#skills) to provide task methods or configure [MCP tools](/v2/en/service/tools) to access the external systems it needs. To reuse knowledge across conversations, explore [Memory](/v2/en/service/memory). After changing the configuration, verify its behavior in a new session before integrating it into an application. When the work requires several independent Agents, see [Multi-agent orchestration](/v2/en/service/orchestration).
