---
title: "Chat: talk to an Agent"
---

[简体中文](/v2/zh/service/chat)

Chat is your personal, multi-turn conversation space. Use it to ask questions, explore a solution and refine a request. Move work into an [Issue](/v2/en/service/issues) when it needs ownership, collaboration or acceptance.

## Start a conversation

1. Open **WORK → Chat** and select **New chat**.
2. Choose an Agent. The picker reflects conversation availability; capability messages explain why a runtime cannot currently participate.
3. Send a small request such as “Describe your responsibilities before changing any files.”
4. Continue in the same Chat. Refresh and reopen it from the list to confirm that history is available.

The Agent uses its assigned execution environment. A path on your browser's computer is not automatically accessible to the Agent. Put required material in a Workspace the Agent can access.

## Follow execution

The conversation displays replies and tool events supplied by the runtime. For a confirmation request, review the operation and its arguments before deciding. If streaming disconnects, reopen the existing Chat and check its state before sending the same work again.

A Chat is the user conversation; a Session holds its runtime context. Operators can use Session diagnostics when needed. Conversation persistence, recovery and tool confirmation depend on the selected Agent's capabilities.

## Turn a discussion into an Issue

Select **Create issue**, review the suggested title and description, and add the objective, deliverables and acceptance requirements. Choose an owner and sharing scope before creating it. The Issue records its Chat source; write the relevant conclusions into the description rather than assuming that collaborators can read the entire private conversation.

For example, after discussing release-note structure, create an Issue with the input versions, expected output file and fact-checking requirements.

## Organize history

| Action | Effect |
| --- | --- |
| Pin / Unpin | Keep a frequent conversation easy to find |
| Archive | Move it to Archived; restore it to continue |
| Delete chat | Move it to Deleted; Restore chat remains available and execution diagnostics are retained |

Archiving or deleting history is not a cancellation operation for running work.

## If no reply appears

Check Agent availability, model credentials, the Environment and Runtime Host status. Check your account and scope when an existing Chat is inaccessible. Use a new Chat to verify configuration changes. Give an administrator the Chat ID, time and visible error without sharing credentials.

Next: [Issues](/v2/en/service/issues) · [Core concepts](/v2/en/service/concepts).
