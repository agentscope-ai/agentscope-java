---
title: "Your first conversation and deliverable"
---

[简体中文](/v2/zh/service/first-session)

Start with Chat, verify a reply and turn the request into an Issue with acceptance. Skip Agent creation if you already have a suitable Agent.

## 1. Prepare a Managed Agent

Sign in. For a new installation, configure model credentials and an Environment through [local setup](/v2/en/service/quickstart). Create “Notes assistant” under **DESIGN → Agents**, explicitly choose **AgentScope Managed** and use:

```text
Organize the supplied material. Separate facts from open questions.
Identify missing information rather than inventing sources.
```

Select an available Environment in Advanced settings. Start with text-only work and save the Agent before adding external tools.

## 2. Verify Chat

Open **WORK → Chat → New chat**, select the assistant and send:

```text
Turn these meeting notes into action items:
Alex will finish the installation guide by Friday.
Review is planned for Monday; its time is unconfirmed.
List tasks, owners, deadlines and open questions.
```

Check that the reply uses only supplied facts, then ask which information needs confirmation. Refresh, reopen the same Chat and confirm both turns remain available.

## 3. Create deliverable work

Select **Create issue**, use “Organize installation-guide actions” as the title, include the material, assign the assistant and review Sharing. Add acceptance criteria requiring owners, deadlines and open questions without an invented meeting time.

Follow Executions and read result comments and deliverables. If no execution starts, inspect ownership and runtime readiness. If blocked, provide the information requested in the latest update.

## 4. Accept the result

When human-review work enters In review, open **WORK → Inbox → Review result**. Compare the output with the criteria. Choose **Accept result** if satisfied, or **Request changes** with specific feedback and arrange follow-up execution.

Done means completion according to the work's policy. Verify the actual content, not only a green execution state. Next add Workspace files and one read-only tool using the [Managed Agent guide](/v2/en/service/managed-agent).
