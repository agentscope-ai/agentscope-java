# Tool-in-sandbox scenario

[简体中文](README.md) · [Back to overview](../../README.en.md)

This is a complete Brain → Sandbox Hands → Brain example. An offline `ReActAgent` is the Brain.
`DurableRuntime` persists its Turn, Run, native Agent Thread Journal, and tool intent. A local
`WorkerRuntime` is the Hands and drives `SandboxBindings` through the Hands execution ledger. The
Brain registers `worker.localExecutor()` as its explicit same-JVM execution endpoint; WorkerRuntime
itself owns Hands scheduling and lifecycle. The example assembles both in one process, while their
tool request and result cross only the durable protocol boundary.

This is an `integrated` scenario without a queue. To deploy Brain and Hands independently, see
[Brain + Hands tool-in-sandbox](../../brain-hands/tool-in-sandbox/README.en.md).

`ToolInSandboxExample` runs as separate `start` and `resume` Spring Boot processes. These are
recovery phases, not Brain/Hands roles. They share Brain's `brain/durable.db`, Hands' `hands.db`,
and the Sandbox directory, but no Java objects.

```mermaid
sequenceDiagram
    participant B1 as start Brain
    participant DB as Durable SQLite
    participant H1 as start Hands
    participant Box as Detached Sandbox
    participant H2 as resume Hands
    participant B2 as resume Brain
    B1->>DB: submit Turn + invoke model
    B1->>DB: checkpoint Agent journal + sandbox.exec intent
    DB->>H1: admit execution
    H1->>Box: launch gated command
    B1-->>B1: exit with Turn WAITING
    H2->>DB: recover existing execution
    H2->>Box: release continue gate
    Box-->>H2: exactly-once effect + recovered output
    H2->>DB: seal Hands result
    B2->>DB: apply result to original Turn
    B2->>B2: restore Agent context + invoke model
    B2->>DB: complete the same Turn
```

During `start`, the model emits a `sandbox.exec` tool call. The command writes `launched`, then
waits for a `continue` file. The example confirms that the Brain Turn is `WAITING`, the Hands
execution is `RUNNING`, and the `count` effect does not exist before the first JVM exits normally.

During `resume`, the same session and request ID recover the original Turn. The persisted Sandbox
binding finds the same resource, and Hands releases the gate and resumes it. Hands seals
`recovered` in the execution ledger. `DurableRuntime` applies that result to the Agent's native
context, invokes the model again, and produces `Sandbox result applied: recovered`.

The scenario verifies that:

- both processes observe the same Turn ID and Sandbox resource ID;
- the model runs once during `start` and twice in total after recovery;
- `count` contains exactly one `once`, so the Sandbox effect is not repeated;
- Hands seals `recovered`, and the Brain Turn reaches `SUCCEEDED`;
- the Sandbox workspace can be deleted after the execution reaches a terminal state.

```sh
bash agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
```

The script checks `WAITING/RUNNING/modelInvocations=1/deleted=false`, followed by
`SUCCEEDED/SUCCEEDED/modelInvocations=2/deleted=true`. The example colocates Brain and Hands in
each JVM for deterministic verification. A production deployment can host Hands separately and
replace the transport, Sandbox target, and retention policy while keeping the Brain checkpoint,
Hands ledger, and result-application protocol unchanged.

## Module and configuration

Standalone Maven module `agentscope-durable-example-tool-in-sandbox`, packaged as
`agentscope-examples/durable/integrated/tool-in-sandbox/target/agentscope-durable-example-tool-in-sandbox.jar`. The configuration prefix is `durable.tool-in-sandbox`
with properties `phase` (`start`/`resume`), `data-directory`, `namespace`, `session-id`, `request-id`, `timeout`. Defaults live in `src/main/resources/application.yml`; override them with
command-line arguments, environment variables, or an external `application.yml`.
