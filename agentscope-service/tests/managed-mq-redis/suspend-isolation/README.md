# Isolated LiteTopic Suspend verification

This is a standalone RocketMQ SDK capability test. It does not change or start the
Service runtime. It creates its own Kubernetes namespace, NameServer, Broker,
Proxy, Redis observation store, two Turn consumers and one independent Resume
consumer. Only this isolated Broker and its consumer deployments receive fault
injection.

The infrastructure uses the source deployment's Broker image and configuration.
Its Broker data goes to a new 20 GiB cloud disk, with a disposable StorageClass
whose reclaim policy is `Delete`. Registry credentials are copied in memory and
are never included in generated manifests or reports. The probe uses the source
Dataplane image solely as a Java/SDK container: a separate main class is launched
instead of the Service application, with no Service configuration or credentials.

## Assertions

- Repeated Suspend: retry limit is three retries/four total attempts; T1 suspends
  at least 120 times before Resume, without T2/T3 callbacks or attempt exhaustion.
- Replica switch: stop whichever consumer last handled T1, observe the other
  consumer handle the same suspended T1, then process Resume independently.
- Broker JVM crash: SIGKILL the isolated Broker JVM, retain its disk, wait for
  actual container restart and T1 redelivery, then check T1/T2/T3 ordering.
- Broker Pod replacement: replace the isolated Broker Pod while keeping its PVC;
  require a different Pod UID and restored T1 delivery before sending Resume.
- Failure control: real `FAILURE` reaches the advertised four-attempt limit,
  leaves the queue, and permits the following control message to arrive.

Redis only supplies the simulated T1 completion flag and a shared, atomic event
ledger. **It never blocks, defers or rejects a T2/T3 callback.** Any such callback
before the T1 success boundary is recorded as a violation and fails verification.
An unrelated LiteTopic must progress while T1 is suspended. The global event
sequence must satisfy `Resume < T1 success < T2 < T3`.

Each case first waits until T1 has reached its listener before sending T2/T3.
Fresh LiteTopic initial-offset behavior is outside this test. Delivery attempts
are recorded separately from the number of Suspend callbacks; a passing result
does not require the attempt number to remain one.

This tests transport semantics and consumer control. It does not exercise actual
Harness reasoning, PostgreSQL checkpoint transactions, application epoch fencing,
or long-term retention. Pod replacement uses a normal Kubernetes deletion with a
10-second grace period; the separate SIGKILL case supplies abrupt Broker failure.
The reported `resume_to_success_seconds` includes starting the producer JVM and
is not a pure MQ latency measurement. Use the event ledger timestamps to measure
the interval between the Resume callback and T1 success.

## Observed cluster result: 2026-10-18

With the deployed Broker/Proxy 5.5.1 image and SDK 5.2.2, the four primary cases
passed. Suspend callback counts were 136, 79, 51 and 69 respectively; their
delivery attempt numbers never exceeded two. All four observed
`Resume < T1 success < T2 < T3`, with no early T2/T3 callbacks. An unrelated
LiteTopic progressed while T1 was suspended. The largest T1 callback gaps were
81.104 seconds for the JVM crash and 78.058 seconds for Pod replacement.

**The complete suite failed its ordinary FAILURE control.** The same message
repeated attempt numbers `1,2,3,4` across delivery rounds. The SDK logged
successful dead-letter forwarding and the Broker accumulated dead-letter
messages, but the following control message did not arrive within 90 seconds.
Further diagnostic observation recorded at least 97 failed callbacks. Therefore,
four advertised attempts must not be interpreted as a verified global callback
limit or a verified guarantee that a permanently failing Lite message unblocks
its successors. The original run did not locate the root cause; the follow-up
diagnosis below identifies it. No Service flow or shared Broker was changed.

## Dead-letter root cause

The follow-up isolates a RocketMQ Broker 5.5.1 file-ConsumeQueue defect. SDK 5.2.2
is used directly, without Service, Redis, SQL, Suspend or a preceding Broker
restart. The trigger is a LiteTopic message at logical offset zero failing into
DLQ before its successor is published. A single consumer reproduces the fault.

The implementation chain is:

1. `AbstractSendMessageProcessor.consumerSendMsgBack` copies the original
   `INNER_MULTI_DISPATCH` and `INNER_MULTI_QUEUE_OFFSET` properties into the DLQ
   message.
2. `MultiDispatchUtils.isNeedHandleMultiDispatch` excludes retry/system Topics
   but omits DLQ. The file queue dispatcher therefore applies the original Lite
   routing information to the new dead-letter message.
3. `ConsumeQueue.putMessagePositionInfo` checks duplicate logical positions only
   when `cqOffset != 0`. A copied zero offset bypasses that check and is appended
   at the Lite queue tail, without a corresponding producer offset increment.
4. The successor is stored in the parent Topic, but its Lite offset is already
   occupied by the incorrectly appended DLQ entry. Its Lite index insertion is
   skipped as a duplicate. Receiving the DLQ copy exposes the retained original
   message ID and starts another local SDK attempt cycle, producing the apparent
   `1,2,3,4` loop and additional dead-letter messages.

The raw file index proves the discrepancy, independently of SDK logging:

| Queue slot | Parent Topic physical position | Lite queue physical position |
| --- | ---: | ---: |
| Original failed message, 0 | 344823 | 344823 |
| Successor, 1 | 377344 | 361726 (the first DLQ message) |

After adding only `&& !topic.startsWith("%DLQ%")` to the isolated Broker's
multi-dispatch predicate, the first-message failure case completed four callbacks
and delivered its late successor during a 90-second observation. Its Lite index
matched the parent index exactly; the DLQ physical position was absent from the
Lite queue. SDK, message format and consumption configuration were unchanged.
Removing that single class override reproduced the same fault again.

| Same offset-zero / late-successor case | Recorded failure callbacks | Successor delivered |
| --- | ---: | --- |
| Original Broker | 15 | No |
| Broker with DLQ dispatch exclusion | 4 | Yes |
| Original Broker restored | 16 | No |

Each case included a 90-second observation after the successor's publication;
the callback totals also include the preceding failure/DLQ response phase. The
run checked eleven cases in total. In the restored case, the successor's physical
position was 705207, while the Lite queue slot instead pointed to DLQ position
699317. The diagnostic load marker was present only with the override installed.

Other controls were healthy: an ordinary FIFO Topic, explicit Lite subscription,
and disabled FIFO acceleration with a successful seed; a late successor also
worked when the failed message was at Lite offset one. Priming a different
LiteTopic under the same parent still reproduced the offset-zero fault, while
priming the failing LiteTopic itself avoided this specific zero-offset path.

This diagnosis applies to the tested 5.5.1 file-queue configuration. RocksDB
queues were not tested. The class override is a causal diagnostic experiment;
it is not a production release, an exhaustive Broker fix, or a repair of indexes
already corrupted by the original implementation.

## Run

Run from the repository root. Use a fresh namespace/output directory for a new
verification. The source namespace must contain the existing deployment names
used by `infra.py`. The kubeconfig stays outside the repository.

```bash
probe_dir=agentscope-service/tests/managed-mq-redis/suspend-isolation
probe_output=/absolute/path/to/verification-output
probe_namespace=agentscope-suspend-YYYYMMDD
probe_kubeconfig=/absolute/path/to/kubeconfig
probe_sdk=/absolute/path/to/rocketmq-client-java-5.2.2.jar

python3 "$probe_dir/infra.py" --kubeconfig "$probe_kubeconfig" \
  --namespace "$probe_namespace" --output "$probe_output"
mkdir -p "$probe_output/classes"
javac --release 17 -cp "$probe_sdk" -d "$probe_output/classes" \
  "$probe_dir/LiteSuspendProbe.java"
python3 "$probe_dir/infra.py" --kubeconfig "$probe_kubeconfig" \
  --namespace "$probe_namespace" --output "$probe_output" --action clients
python3 -u "$probe_dir/verify.py" --kubeconfig "$probe_kubeconfig" \
  --namespace "$probe_namespace" --output "$probe_output"
```

The runner writes `result.json`, `events.tsv`, the confirmed send ledger, resource
identity snapshots, command outputs and client logs. A successful result requires
every assertion; a timeout or ordering violation is a failure, not a skipped test.

After preserving evidence, remove only the namespace and StorageClass created by
this run. Check that its PVC/PV has been deleted; do not touch the source Broker.

```bash
kubectl --kubeconfig "$probe_kubeconfig" delete namespace "$probe_namespace"
kubectl --kubeconfig "$probe_kubeconfig" delete storageclass "$probe_namespace-disk"
```

## Dead-letter diagnostic tools

`DeadLetterProbe.java` is a separate SDK-only reproducer with no Redis or Service
dependency. It records the actual message Topic, logical offset, receipt handle
and message ID. `deadletter_matrix.py` provisions one consumer per case, confirms
send receipts, saves SDK logs and stops each consumer after observation.

The default cases use a successful seed before the failed message and publish
the successor together with it. The `late` case starts with a failed message at
Lite offset zero and publishes its successor only after the SDK reports a
successful DLQ response. `late-seeded` adds a successful message first;
`late-fifo` uses an ordinary FIFO Topic. `late-skew` and `late-skew-seeded` also
prime another LiteTopic under the same parent, distinguishing parent offsets
from the failing LiteTopic's own offsets.

Use a fresh isolated namespace/output directory for another full diagnostic run.
Compile `DeadLetterProbe.java` with `--release 17` and SDK 5.2.2 into
`$probe_output/classes`, then run `deadletter_matrix.py` with the same
`--kubeconfig`, `--namespace` and `--output` arguments as `infra.py`. Select cases
with `--cases baseline,accelerator-off,explicit,fifo` or
`--cases late,late-seeded,late-fifo,late-skew,late-skew-seeded`; the latter cases
were observed with `--observe-seconds 90`.

For the causal experiment only, `DlqDispatchPatch.java` uses ASM to add a DLQ
exclusion to `MultiDispatchUtils.isNeedHandleMultiDispatch` in the exact deployed
`rocketmq-store-5.5.1.jar`. It preserves the vendor class version and adds a load
marker. Compile this offline tool with Java 17 and ASM 9.9.1, then invoke it with
the original store JAR and `$probe_output/dispatch-patched-classes` as arguments.
`deadletter_ab.py --action install` mounts that one class ahead of the vendor JAR
in the disposable Broker. `--action remove` restores the original classpath.
Both actions require an owned isolated namespace and all probe replicas stopped.
Run fresh `patched` and `reverted` cases with a 90-second observation window
after the respective rollouts. These tools are diagnostic instrumentation, not a
production Broker release or a Service implementation change.

The explicit-subscription setup initially used `lite.sub.wildcard=false`. In
5.5.1 the presence of that attribute enables wildcard mode, regardless of its
value. The attribute was removed and the consumer recreated before that case
ran. The correction and the failed setup logs are retained in the evidence;
the original fault uses the supported `wildcard=true` configuration.
