# Cross-Kernel Durable Events and Usage Verification

[中文](verification-public-events-20261029.md)

Date: 2026-10-29. Directory: `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`, HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` plus the current uncommitted working directory. This record covers parts of W08 and W14, not acceptance of the complete implementation plan.

## Implementation

- Generic model dispatch / complete commits generate public model start/end facts and paired spans. Reported usage maps to unified fields; unknown usage is not represented as zero.
- PI extracts public text from newly appended SessionManager assistant messages; PI Durable uses root-conversation AssistantEntry writes; Codex uses rollout Messages from the current native Turn. Facts commit with the corresponding native state. Thinking, tool arguments, and compaction summaries are excluded.
- Optional `terminal.messageId` selects the existing public message for final output. Recovery preserves message identity without text comparison or transient commit IDs.
- Public-fact content and final output larger than 16 KiB use same-scope object references. Public facts have a cumulative inline-content budget of 128 KiB per commit. Object digests cover complete content; text is not truncated.
- Codex extracts total input, output, and cache subsets from Responses completed usage. Missing, invalid, or inconsistent usage remains unknown. PI adds disjoint uncached input, cache reads, and cache writes into platform total input so cached context is included in budgets.

## Executed Verification

| Check | Result and scope |
| --- | --- |
| Protocol `make check` | 9 Schemas, 17 API operations, 105 positive examples, and 31 negative examples passed; Node verified 4 vectors each for JCS and identity |
| TypeScript `pnpm -r build` / `pnpm -r test` | Build passed; Runtime 29 and PI Adapter 34 cases passed; the historical TS Codex probe's 3 cases are listed separately and do not establish real Rust-kernel acceptance |
| Rust `cargo test --locked --workspace` | 19 cases passed: proxy 3, native integration file 5 (including its child-process entry point), Runtime 2, recovery 9; actual scenarios include partial results from two tools, subsequent Turns, SIGKILL recovery, and 300 KB messages |
| Targeted Java Maven | `HarnessProtocolStoreTest` 18 and `PiBrainHttpFlowTest` 4 cases passed without failures/skips; HTTP tests were explicitly enabled |

New behavioral assertions cover paired spans for all three generic protocol modes, receipt retries without duplicate projection, omission of model_usage when unknown, selection of only the final message for output, no republishing of committed text after native recovery, exclusion of private thinking, and 300 KB public content without large commit metadata. Four PI HTTP combinations check intermediate/final messages, two model spans, and unique final-output identity through real Node SDKs and Java HTTP/transaction paths.

Commands:

```sh
# Repository root
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am -Dtest=HarnessProtocolStoreTest,PiBrainHttpFlowTest -Dsurefire.failIfNoSpecifiedTests=false -Dmanaged.pi.http=true test

# typescript directory
pnpm -r build
pnpm -r test

# rust directory, using 1.95.0 pinned in rust-toolchain.toml
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

Local logs: `/tmp/managed-brain-events-protocol.log`, `/tmp/managed-brain-events-ts-final.log`, `/tmp/managed-brain-events-rust-final.log`, and `/tmp/managed-brain-events-java-final.log`. Logs are temporary; this document records verification scope and results.

## Remaining Work and Limits

- Online previews were not integrated when this record was produced. Subsequent implementation and targeted coverage are in the [preview verification record](verification-previews-20261029.en.md). Executable Codex responses still require full validation before reaching Core; historical results here establish durable events only.
- Closing unfinished model dispatches and reconciling spans/unknown accounting after upstream interruption or process crashes remain incomplete. Normal completion pairing does not prove these paths.
- Model endpoints are deterministic doubles. PI uses H2 and test infrastructure; Codex uses a Session Service transport double. Complete construction from the real Control Plane, the real storage/MQ/Redis/sandbox combination, and real-model quality evaluation remain outstanding.
- Full-history Codex checkpoints, long-history costs, compaction, incremental recovery, and public checkpoint administration remain W14/W15 work. New public-message metadata must also be included in capacity checks.
- This phase did not run repository-wide `clean verify`, repository-wide `spotless:check`, image builds, or deployment. No code was committed or pushed.
