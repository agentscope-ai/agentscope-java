# Cross-kernel online text preview verification

[中文](verification-previews-20261029.md)

Date: 2026-10-29. Actual directory: `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`, HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` plus existing uncommitted work. This records W09 / W10 implementation and targeted verification, not complete-plan or production acceptance.

## Implementation and boundaries

- Added service-authenticated `previews/interest` / `previews/publish`. Service checks scope, current owner/fence, a reasoning dispatch, and no committed model completion; a possibly still-reserved accounting status alone is insufficient. Redis operations run outside the Session transaction lock. Previews do not write execution ledgers or durable history.
- TypeScript and Rust use independent bounded lanes that preserve execution cancellation and receipt semantics. Overflow, rejection, absent subscribers, and timeouts stop only previews, without retry. Batches contain at most eight 1024-codepoint chunks; pending queues allow 64 KiB / 256 chunks, requests time out after 250 ms, and finishing waits at most 200 ms.
- PI publishes from main-answer message_update events. PI Durable publishes main-answer streams while excluding complete/completeSimple auxiliary calls. Both persist model-call identity in adapter-owned native message metadata for deltas, public facts, and final-message selection. Restored states without metadata retain native entry identity.
- Codex parses complete SSE frames before model HTTP response completion and publishes only output_text deltas from declared assistant items. It handles fragmented UTF-8 and CRLF and excludes reasoning, analysis, and tool arguments. Preview IDs derive from native thread / item IDs, matching rollout extraction. Executable responses still pass complete allowlist validation before Core receives them.
- Shared receivers reuse event_start / event_delta, epochs, sequences, interest, bounded delivery, and final-message reconciliation. Multiple messages in one model call have independent preview state. Initial sequence includes start; late subscriptions, duplicates/gaps, and stale epochs cannot append an incorrect suffix.

## Executed verification

| Check | Result and scope |
| --- | --- |
| Protocol `make check` | 10 schemas, 19 operations, 109 positive / 39 negative cases; four JCS and four identity vectors passed |
| TypeScript build and tests | Build passed; Runtime 34 and PI Adapter 36 tests passed; the historical TS Codex probe has three separate tests, excluded from Rust acceptance |
| Targeted Java Maven | 32 passed, zero skipped: shared previews 7, protocol Store 21, PI HTTP 4 |
| Rust `cargo test --locked --workspace` | 25 passed: adapter unit 5, native integration file 6 including the subprocess entry point, Runtime base 2, previews 3, recovery 9 |

The four PI HTTP cases run real Node workers, SDKs, adapters, Java Session Service, and Hands shell execution, with H2 and in-memory infrastructure. They cover regular/durable modes and uninterrupted/restarted execution after tool suspension. Previews cross HTTP before model completion commits; receiver SSE frames match durable message IDs. Existing assertions for every lost commit receipt, large content, and one physical tool execution remain passing.

The new Codex case runs real codex-core and a chunked HTTP model endpoint. The provider sends text deltas, waits for preview publication while the model is still incomplete, and only then sends response.completed. The test checks text and final-message identity. Session Service is a protocol transport double; this does not establish the Rust worker → real Java Service/Redis/public SSE flow.

Other assertions cover absent subscribers, private auxiliary output, queue/wire bounds, failed/hung transports without execution cancellation, missing dispatch/completed/non-reasoning/stale-owner rejection, no new durable facts, late subscribers, duplicate sequences, stale epochs, model-end closure, and slow consumers.

```sh
# Repository root
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=ManagedSessionPreviewsTest,HarnessProtocolStoreTest,PiBrainHttpFlowTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false

# typescript directory
pnpm -r build
pnpm -r test

# rust directory, pinned Rust 1.95.0
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

Local logs: `/tmp/managed-brain-preview-protocol.log`, `/tmp/managed-brain-preview-ts-build.log`, `/tmp/managed-brain-preview-ts-tests.log`, `/tmp/managed-brain-preview-java.log`, and `/tmp/managed-brain-preview-rust.log`. Logs are temporary; this record preserves verification scope.

## Remaining work

- W09 / W10 remain open: real Redis, multi-instance interest/epoch transitions, final-event interleaving through public SSE HTTP, concurrent capacity, and Rust → real Service composition need acceptance. AgentScope native calls and common identity rules also need cross-kernel W19 coverage.
- Public scope is text previews. Final messages remain authoritative. Delta replay, SSE resume, multimodal deltas, and tool-argument deltas are not supported.
- The current PI Durable public profile allows only the root. Opening subagents requires renewed root/child isolation checks; stream-method filtering does not establish safety for arbitrary child calls.
- Model spans and unknown accounting after crashes/upstream interruptions, plus configuration/resources/context completeness, remain in W05–W20. This work does not complete Control Plane assembly, real-model evaluation, or all fault paths.
- No repository-wide `clean verify` / `spotless:check`, image builds, or deployment were performed. Only changed Java files were formatted, followed by affected-module lifecycle format checks. No commit or push.
