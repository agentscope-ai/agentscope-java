# Client and documentation regression — 2026-10-02

This records the client portion of the concentrated regression requested for the session/event log and Agent as a Service work. It does not mark the two master backlogs complete. Backend, PostgreSQL, fencing, multi-replica, Managed runtime and full Java results are recorded by their respective regression runs.

## Workspace and isolation

- Main checkout: `/Users/ken/agentscope-3/agentscope-java`, branch `harness-context-redesign`. Existing staged and unstaged changes were retained; no commit or worktree was created.
- Read both `internal/implementation/session-event-log-regression-backlog.md` and `agentscope-service/docs/service-api/regression-checklist.md` before testing.
- Existing Playwright suite used an isolated preview on port 25437, `reuseExistingServer: false`, with a temporary Playwright configuration. It did not reuse the normal developer server on 5177.
- Real Service browser tests used the isolated Gateway at `http://127.0.0.1:28080` and the isolated Go control plane/PostgreSQL provided by the overall regression run. No browser API routes were mocked in these scenarios.
- Real Harness browser tests used the latest `agentscope-chat.jar`, `CHAT_MODEL=demo`, port 28085, workspace `/tmp/agentscope-java-regression-20261002/chat`. The deterministic model executes real tools and native session-log storage without paid model requests.
- Test credentials remain in mode-0600 temporary files and are not included here. Browser-only approval resources belong to a separate test Application owned by the seeded admin test account.

## Complete automated suites

| Area | Command / scope | Result | Log |
| --- | --- | --- | --- |
| Python SDK | `/tmp/agentscope-service-release-venv/bin/python -m pytest -q`, SDK directory | 103 passed, 17.09 s | `/tmp/client-regression-python-final.log` |
| Console unit tests | `npm test`, frontend directory | 36 files, 150 passed | `/tmp/client-regression-frontend-tests-final.log` |
| Console production build | `npm run build` (TypeScript and Vite) | Passed; output is `agentscope-service/service-controlplane/ui`, not `frontend/dist` | `/tmp/client-regression-frontend-build-final.log` |
| Console Playwright | All `e2e/**/*.e2e.ts` through isolated preview | 61 passed, including the new Invocation refresh case | `/tmp/client-regression-playwright-final.log` |
| Documentation validation | `npm run check`, docs directory | 460 pages and 504 redirects; navigation, syntax, local links, anchors and assets passed | `/tmp/client-regression-docs-check-final.log` |
| Documentation tests | `npm test`, docs directory | 12 passed | `/tmp/client-regression-docs-tests-final.log` |
| Public OpenAPI contract | Included in the full SDK suite | Actual public `/invoke/v1` routes match OpenAPI paths; references resolve; Application credential, terminal-status and expired-cursor assertions pass | Python log above |

The repository Playwright suite uses mocked API fixtures; its passing results do not stand in for the real-service checks below. Initial runs found one stale Endpoint contract assertion and the new refresh regression exposed a missing fixture readiness response. Both were corrected before rerunning the full suite.

## Real browser: native Harness session history

Chromium drove the actual Chat page and HTTP/SSE endpoints. No `page.route` mocking was used.

| Scenario | Verified behavior |
| --- | --- |
| Multi-message and multi-tool restoration | `/slow` generated three model responses and three tool calls. Refresh during partial tool arguments preserved existing item IDs; manually disconnecting and reconnecting the stream caught up the committed suffix. Final snapshot had seven display items and three tool cards, without duplicated item IDs. A final refresh preserved the rendered history. |
| Required external input | `/ask` suspended execution. The draft answer survived background updates. Refresh restored the same pending request ID. Submitting an answer cleared the pending request and completed the same turn in a new run. |
| Interruption and continuation | Interrupting an in-progress `/slow`, refreshing, and selecting resume completed the same turn in a new run. |
| Session ordering | Session options stayed lexicographically ordered under polling and page refresh; selection remained on the current session. |
| Completed history after process restart | Restarted the actual Java process on the same workspace. All saved item IDs and the completed cursor were unchanged, and all three tool cards were restored. |
| Pending request after process restart | Created a new suspended request, restarted Java, restored the same request, and answered it successfully. The turn remained the same and the run changed. |
| Checkpoint after process restart | Created an interrupted turn before restarting Java, then resumed it through the page. It completed with the original turn ID and a new run ID. |

No browser `pageerror` was observed. Evidence:

- `/tmp/agentscope-java-regression-20261002/chat-browser-report.json`
- `/tmp/agentscope-java-regression-20261002/chat-restart-prepare.json`
- `/tmp/agentscope-java-regression-20261002/chat-restart-verify.json`
- Screenshots in the same directory: `chat-live.png`, `chat-restored.png`, `chat-required-action.png`, `chat-interrupted.png`, `chat-process-restored.png`.
- Drivers: `/tmp/client-regression-chat-browser.mjs`, `/tmp/client-regression-chat-restart.mjs`.

## Real browser: Service Invocation API

Chromium signed into the isolated Console through the real Gateway. The API-only worker bootstrap provided a deterministic External Agent; a separate pure approval Workflow was created through the public management API for human approval testing.

| Scenario | Verified behavior |
| --- | --- |
| Endpoint submit and stream | Submitted real input through Test API. The worker emitted four assistant items and three tool calls. The page consumed real snapshot/SSE and reached completed with the expected final answer. |
| Refresh and restore | The submitted Invocation ID is now recorded in the URL. Refresh restores that ID and observation resumes from its snapshot. A caller using a manually supplied credential must re-enter it when the signed-in user cannot reveal that Application's key; the key is not stored in the URL or browser persistence by this feature. |
| Human approval | A real Workflow approval request appeared in the page, survived refresh with the same request ID, and accepted the seeded designated approver's platform identity. The Invocation reached completed. The form draft survived normal background updates before refresh. |
| Cancellation | Cancel execution sent the public command to the real runtime. The Invocation reached cancelled, and a page refresh restored the terminal snapshot with no active cancel button. |

No browser `pageerror` was observed. Invocation evidence:

- Agent restore: `3e2508f4-834f-4467-bcf8-e5d8382a8a97`.
- Human approval: `6149fc16-a8f9-46b1-b0ca-9037d7c666e6`.
- Cancellation: `a5e93a00-a5a2-471b-905a-e8821f1d46e1`.
- Reports: `/tmp/agentscope-regression-20261002/console-browser-report.json`, `console-actions-report.json`.
- Screenshots in that directory: `console-invocation-live.png`, `console-invocation-restored.png`, `console-approval.png`, `console-cancelled.png`.
- Drivers: `/tmp/client-regression-console.mjs`, `/tmp/client-regression-console-actions.mjs`, `/tmp/client-regression-console-resources.py`.

## Defects found and corrected

1. The API-only bootstrap pre-created External Agents without bindings, leaving them provisioning and causing worker registration rejection. External worker registration now atomically creates its Agent and binding; explicit Managed Agent creation remains supported.
2. Reusing an output filename could read an old worker identity before the new worker registered. Identities and durable event outboxes now use a distinct run-name subdirectory, preserving old files without selecting them.
3. Example workers and framework result mapping treated JSON `null` input/Team/child/outcome lists as iterable values. Nullable optional context fields now normalize to empty collections. A deterministic worker test executes all three tools against the real nullable context shape.
4. The bootstrap used a capability-name array where runtime policy requires an object. It now uses `{"agent-task": true, "event-reporting": true}` after the backend capability normalization fix. Registration and ASDP wire capability arrays remain the transport contract.
5. The Console lost the active Invocation selection on browser refresh after submission or manual Observe. Both now update the URL's `invocation` parameter. A full browser regression checks restoration without resubmission or exposing the credential.
6. Completed calls with no message events displayed “Waiting for output.” The empty state now says that no message output was recorded.
7. Public documentation now explains credential rotation overlap: migrate callers to the replacement, then explicitly revoke the old credential.
8. A control-plane SIGKILL/restart exposed `TaskContext.refresh()` failing the entire invocation on a brief connection refusal. Collaboration GET calls now retry only transient network/timeout failures and HTTP 500/502/503/504, with exponential backoff inside the original timeout budget. Authentication/business errors and all mutating requests are not retried. Twenty targeted cases cover remaining budget, exhaustion, writes, business errors and invalid successful JSON; all are included in the 103-test run.
9. Native execution copies RuntimeContext after sandbox acquisition. Clearing only the wrapper context left copied contexts referencing a finished sandbox. The acquisition now carries a shared release flag, cleanup is claimed once, and the filesystem rejects released bindings rather than falling back to another call's sandbox. Java tests cover cancellation, duplicate cleanup through copied contexts, and rejection before either stale or current sandbox receives a command; the Java regression owner confirmed all of these cases passed in the complete Harness run.

The overall real API run also exposed an execution-start acknowledgement race; the backend/SDK agent added the synchronous start barrier and regression coverage. Its tests are included in the 103-test SDK result above.

Additional Java fixture repairs were coordinated with the Java regression owner: native action observations read the native committed log; Sandbox tests provide an explicit durable log store; legacy cache identity is tested in explicit LEGACY mode; native async acquisition/release tests await the relevant lifecycle boundary; cancellation assertions verify actual stopped work instead of an obsolete method invocation. Java pass totals belong to the overall Java report, not the client totals above.

## Remaining boundaries

- These browser runs use deterministic offline models and workers. They do not establish behavior of paid provider streaming, provider-specific tool formats, a real LangChain installation, or the optional Python AgentScope package.
- Browser verification did not itself force multi-replica worker fencing, expired retention cursors, webhook callback failures, tenant penetration scenarios or a large-artifact memory benchmark. SDK/unit/backend suites cover parts of those paths; only the overall regression report should claim their integrated coverage.
- Browser process recovery used clean termination and restart of the dedicated Chat server. Abrupt crash and lease-expiry recovery are distinct backend scenarios.
- No production deployment, migration of user history or deletion of existing user data was performed. The dedicated Chat process on port 28085 was stopped after verification; its temporary workspace and evidence remain available. The shared isolated CP/DP/Gateway lifecycle remains owned by the overall regression run.

## Additional fixture lifecycle correction

The final Java run exposed an unrelated temporary-directory teardown race in `agentscope-examples/jev/src/test/java/io/agentscope/examples/jev/JevToolGuardBenchmarkTest.java`, method `actualHarnessCanQueryAfterDenialAndRefundOnce`. The fixture now uses `InMemorySessionLogStore` while keeping native event history enabled, and closes its HarnessAgent with try-with-resources. Judge decisions, query/refund counts and denied-result assertions are unchanged. This newly touched file was handed to the Java regression owner for the remaining complete module run; it is not included in the client test totals.
