# Sessions, tasks and approvals

Sessions support direct conversations and execution history. Tasks and Runs identify specific work within a collaboration.

## Read an execution

Start from the originating Session or Issue, then inspect its task and latest Attempt. Check the triggering input, selected Agent, Environment or Runtime, events and final result. Preserve failed-attempt records when retrying.

Streaming responses use SSE. After disconnecting, the browser can read durable history again. Reverse proxies must allow long connections and forward events promptly. A page refresh is not a reason to submit the same work again.

## Handle requests for human action

Tool confirmation belongs to a Session; Issue review concerns deliverable acceptance; a resource request concerns authorization. Identify the request type, actor, resource and intended effect before acting.

A denied tool may require an alternative approach. Missing access requires a grant from the resource owner or administrator. Retrying alone does not resolve these conditions.

## Diagnose stalled work

Check pending confirmations, model errors, Environment or Host connectivity, task dispatch and component logs. Preserve Session, Task, Attempt and Run IDs to correlate evidence.

See [Troubleshooting](troubleshooting.md) and [Issues and Teams](teams.md).
