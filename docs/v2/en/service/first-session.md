# Your first Session

By the end of this guide, you should be able to send a message, receive an Agent response and find the conversation again after refreshing the console.

## 1. Prepare a model and Environment

Start the platform with the [quickstart](quickstart.md). Configure an available model and its credentials. Enable Local only for a trusted evaluation; otherwise create a Sandbox or Self-hosted Environment and confirm connectivity.

## 2. Create an Agent

Create a Managed Agent in Agents. Give it a name and instructions, such as “Answer briefly and explain which files you need before using them.” Select its model, Workspace and Environment. Enable only the tools needed for the first task.

After saving, check the Environment binding. Saving a configuration does not start execution.

## 3. Create a Session and send a message

In Sessions, select the Agent and create a Session. Send “Introduce the work you can do in one sentence.” Follow messages, tool calls and state changes. Handle tool confirmations in the conversation when requested.

For a file-based task, add a small example file to the Workspace and ask the Agent to list or summarize it. Verify that it uses the intended Environment.

## 4. Check the result

Refresh the page and reopen the Session to confirm that its history remains available. Refreshing during execution should not be treated as a request to resend the message. Judge completion from the result and state, rather than a streaming connection closing.

## Corresponding APIs

| Operation | API |
| --- | --- |
| Sign in | `POST /api/auth/login` |
| Create Agent | `POST /api/v1/agents` |
| Create Environment | `POST /api/environments` |
| Create Session | `POST /api/sessions` |
| Send message | `POST /api/sessions/{id}/events` |
| Read history | `GET /api/sessions/{id}/events` |
| Subscribe | `GET /api/sessions/{id}/events/stream` |

Agent creation uses `/api/v1/agents` with `agentKey`, `displayName`, `binding` and `definition`. Without an explicit Namespace, requests use the account’s personal space. Do not assume a new installation contains a shared Namespace named `default`.

A message body is `{"events":[{"type":"user.message","payload":{"text":"Hello"}}]}`. Use the signed-in user's Bearer token. Internal service tokens are not browser credentials.

If no response arrives, inspect the model, Environment and Dataplane with the [troubleshooting guide](troubleshooting.md).
