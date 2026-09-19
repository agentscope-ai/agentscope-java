# AG-UI Example

A minimal Spring Boot WebFlux application exposing AgentScope agents over the AG-UI protocol.

## Run

```bash
mvn -q -pl agentscope-examples/agui spring-boot:run
```

The server listens on `http://localhost:8080` and exposes `POST /agui/run` (and `/agui/run/{agentId}`
when path routing is enabled).

## Presentation Snapshot Hydrate

This example enables the AG-UI presentation snapshot store:

```yaml
agentscope:
  agui:
    snapshot-store-enabled: true
    snapshot-max-threads: 1000
```

With the store enabled, a reconnecting client can rebuild the visible conversation **without
re-running the agent** by calling the read-only hydrate endpoint `POST /agui/connect`.

Try it:

1. Run the agent once against `/agui/run` with a `threadId`:

   ```bash
   curl -N http://localhost:8080/agui/run \
     -H 'Content-Type: application/json' \
     -d '{"threadId":"demo-1","runId":"run-1","messages":[{"id":"m1","role":"user","content":"hello"}]}'
   ```

2. Replay the same `threadId` against `/agui/connect` and observe a `MESSAGES_SNAPSHOT` restoring
   the conversation with **no model call**:

   ```bash
   curl -N http://localhost:8080/agui/connect \
     -H 'Content-Type: application/json' \
     -d '{"threadId":"demo-1","runId":"connect-1"}'
   ```

The hydrate response is strictly read-only: it never mutates the agent, the snapshot store, or the
resume coordinator, and only the **trailing unresolved** interrupt is ever replayed (so a resolved
historical interrupt cannot reappear).
