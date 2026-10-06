---
title: "Conversations, tasks, and feedback"
zh_link: /v2/zh/service/console/tasks
---

<Note>
This documentation is a preview. The official release is not yet available.
</Note>

Use a conversation to clarify a request and an Issue to assign ownership, retain deliverables, and review results. In the console, you can discuss a goal in Chat before assigning it to an Agent or Team, or create an Issue directly. Applications use the [Issue API](/v2/en/service/issues), [feedback and review APIs](/v2/en/service/inbox), and [event interfaces](/v2/en/service/sse-events) for the same workflow.

## Clarify the request in Chat

Open **Work → Chat → New chat**, select an available Agent, and send a small request. For example, ask the notes assistant to organize meeting actions, then ask what information is missing. Read messages in Conversation and runtime events in Events. When a tool requires confirmation, inspect its target and arguments before deciding.

<Frame caption="Chat interface with fixed demonstration data.">
  <img src="/imgs/service/chat.png" alt="Chat messages, events, and Create issue entry point" />
</Frame>

After a refresh or connection loss, reopen the existing Chat to inspect its state and history. Execution may still be progressing; sending the same request again can start another turn. Persistent conversations, recovery, and tool confirmation depend on the Agent runtime's capabilities.

Once the goal is clear, click **Create issue**. Check the prefilled content and write the key conclusions, material locations, and acceptance criteria into the description. The new Issue retains a Chat source reference, but collaborators should not be expected to have read an entire private conversation.

The history list supports Pin, Archive, and Delete chat. Deleted chats can be restored with Restore chat under Deleted. These history operations do not cancel execution.

## Create and assign work

Open **Work → Issues → New issue**, or continue in the dialog opened from Chat.

1. Enter an outcome-oriented title such as “Organize this week's meeting actions.” Include materials, boundaries, and deliverables in Description.
2. Choose Sharing. Private is visible only to you; Namespace members shares with members of the space, including execution records and attachments. Add individual collaborators after creation if needed.
3. Select an Agent, Team, or Human owner. Choose a Workflow as the execution target when the work should follow a fixed process; it must have a published version.
4. After creation, add Acceptance criteria in the detail page and check whether Executions contains an execution.

For example:

```text
Deliver a list of tasks, owners, and deadlines.
Every entry must be supported by the supplied meeting materials.
Mark missing dates or owners as unconfirmed instead of inventing them.
```

You can assign an owner later. Assignee in the detail page supports Agent, Team, and Human reassignment. To run a Workflow against an existing Issue, use its **Run Workflow** entry point. Check Executions after assignment; successful Issue creation alone does not establish that execution has started.

<Frame caption="Issue list with fixed demonstration data.">
  <img src="/imgs/service/issues.png" alt="Issue owners, priorities, and states" />
</Frame>

## Follow discussion and deliverables

Read comments in the Issue detail and use Attach for materials or deliverables. Add a comment when new information arrives. Use Mention when a particular Agent should participate, then inspect the response and subsequent execution. Split larger work into child Issues with their own owners and outputs, and bring the results together in the parent.

Executions contains the actual execution records. Open one to inspect steps, attempts, and errors; for Team work, use the task map to inspect delegation. Before dispatching again after a failure, identify the failed step to avoid repeating external operations that already completed.

| State | What to check next |
| --- | --- |
| Backlog / Todo | Whether inputs and ownership are clear |
| In progress | Whether executions, discussion, and artifacts advance the goal |
| Blocked | Which information, authorization, or dependency is missing |
| In review | Whether the result meets the acceptance criteria |
| Done / Cancelled | Whether the final result or cancellation reason is recorded |

One successful execution and a completed piece of work are different outcomes. Policy in the detail page determines whether human review is required. Resolving a comment thread also does not complete the Issue.

## Review and approve in Inbox

Open **Work → Inbox**. Use Needs action for pending decisions or Unread for unread updates. Selecting a message shows the associated work on the right; Open issue opens the full record.

<Frame caption="Inbox review interface with fixed demonstration data.">
  <img src="/imgs/service/inbox.png" alt="Inbox with an associated Issue and result review" />
</Frame>

For Review result, compare the result, attachments, and child Issues with Acceptance criteria. Choose **Accept result** when they meet the requirements. Otherwise, select **Request changes**, explain the missing work, and click **Send review**. Requesting changes records feedback and returns the Issue to In progress; a subsequent execution or follow-up still needs to be arranged. If the work changed, use **Refresh review** and check the updated result before deciding.

An approval item asks whether an operation may continue. Inspect the requester, target, reason, and associated work before approving or rejecting it. This is a separate decision from accepting the final deliverable. Interactive tool confirmations in Chat are handled by that session and do not necessarily appear in Inbox.

Reading or archiving a notification organizes the inbox; it does not replace review, approval, or cancellation. See the [Inbox API](/v2/en/service/inbox) for version constraints and automation. Continue with [Teams and orchestration](/v2/en/service/console/orchestration) for collaboration and fixed processes.
