---
title: "Archive and restore context"
zh_link: /dev/zh/docs/harness/jev/guides/context-archive
---

`JevContextArchive` saves the message snapshot at the compaction boundary and restores it within the same user, Agent and session scope. Use it to reread evidence, inspect compaction decisions or retrieve earlier tool results.

## Example: inspect old file evidence after compaction

The Agent moved obsolete file contents out of context. Later the user asks what the file said at the time. Running the tool again might read a different version; replaying a write could duplicate side effects.

Read the archived snapshot without executing tools or replacing the Agent history that has accumulated since.

## 1. Choose an archive store

```java
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextArchive;

var archive = new FileJevContextArchive(archiveRoot, 20_000_000);
```

`archiveRoot` is an authorized host directory; the second argument limits bytes per archive. The file implementation isolates scopes by hash, publishes atomically and validates content on restore. On POSIX filesystems it uses private file and directory permissions.

For shared storage use `StoreJevContextArchive(baseStore, maxBytes)`. It reuses the BaseStore namespace and compare-and-set creation semantics. Use an in-memory store only for testing; multiple production replicas need durable shared storage.

## 2. Archive before adoption and keep the reference

Supply `archive` to [JevContextCompactor](/dev/en/docs/harness/jev/guides/context-compaction-api). ENFORCE writes the archive before adopting compaction; if writing fails it does not adopt the candidate. SHADOW never writes an archive.

Read the reference from the Plan or `context_compaction` decision record and store it with the authenticated scope. It identifies an archive to restore; it does not let the model specify a filesystem path.

## 3. Restore messages in the original scope

```java
var scope = new JevContextArchive.Scope(userId, agentId, sessionId);
var pending = archive.restore(scope, archiveReference);
var observed = pending.doOnNext(previous ->
    System.out.println("Messages restored: " + previous.size()));
```

Take identities from host authentication and invocation context and `archiveReference` from a successful prior archive. Show old evidence or selectively include it in future inputs. Do not overwrite newer history with the snapshot.

Archives include message IDs, timestamps, tool arguments, results and metadata. Apply the same ACL, retention and storage protection as the session. Unknown scope, corrupt contents or missing references fail; never fall back to reading arbitrary paths.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples):

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextCompactionExample
```

The example archives six pre-compaction messages in a temporary directory, keeps current test evidence and user constraints, and restores within the same user/agent/session. It prints the archive path and dispatches no historical tools. [Full source](/examples/jev/source/JevContextCompactionExample.java.txt)

## What restore covers

An archive contains what reached this compaction boundary. If earlier summarization or offloading already changed history, this is not the original complete conversation. Consult the host transcript and offload records for full history.

Cancellation after I/O begins can leave an unadopted archive. The host owns cleanup. For in-process plan restoration use [JevContextPlanner.Plan.restore()](/dev/en/docs/harness/jev/guides/context-planner-api); it is not durable storage.
