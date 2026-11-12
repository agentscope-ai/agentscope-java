# Two-replica recovery acceptance: 2026-10-19

[中文](dual-replica-recovery.md)

The recovery contract is now implemented and [follow-up acceptance passed](recovery-v1-acceptance.en.md). The initial failure evidence below is preserved.

The baseline and Runtime Pod recovery passed. Automatic recovery after the Tool Worker dies during
an HTTP MCP call failed. No duplicate physical invocation or T2 overtaking was observed; this does
not establish automatic completion of every in-flight task.

## Environment and method

- Project: `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`.
- Namespace: `agencore-ma`; two replicas for each of the five application components and for the independent MCP server.
- Image version: `2.1.0-build.20261019-203221-6c6d5ae0`. No image or Deployment configuration changes were made in this run.
- Each case creates its own Session and uses a real model and HTTP MCP, without Sandbox or NAS.
- Fault cases first establish `T1 waiting_tool / T2 accepted` and a physical start record, identify the actual launch Pod from structured logs, and delete it with a one-second termination grace while retaining a healthy peer.
- Evidence includes public APIs, both final outputs, physical MCP start/completion logs and PostgreSQL task/Suspend/Resume records.

## Results

| Case | Result | MCP starts/completions | Final T1 / T2 states |
| --- | --- | --- | --- |
| Two-replica baseline, real 90-second tool | Passed | 1 / 1 | completed / completed |
| Original Runtime Pod deleted while T1 is suspended | Passed | 1 / 1 | completed / completed |
| Original Tool Worker Pod deleted during its MCP call | Failed | 1 / 1 | waiting_tool / accepted |
| New work in a separate Session after Worker recovery | Passed | 1 / 1 | completed / completed |

All three passing cases verified applied injected input, the same real call_id in both replies,
T1 completion before T2 execution, and resumed database suspension records. After deleting Runtime
Pod `ma-managed-agents-agent-harness-runtime-585f58f8f9-w5bwp`, Pod
`ma-managed-agents-agent-harness-runtime-585f58f8f9-ckxbz` recovered the same T1.

## Tool Worker failure

Fault Session: `ae0f0db1-1c84-55de-8fce-8a259ea618b2`. At 21:04:31 Asia/Shanghai, the actual launching
Pod `ma-managed-agents-tool-worker-6dd95558b9-rfgfd` was deleted. At 21:05:53 the MCP server completed
its sole physical call, `f0cb56ca-f538-4c21-a2df-946eb5af144c`. T1/T2 remained unfinished through the
full 90 + 240 second acceptance deadline.

The database retained the original task_id, execution_id and both admitted Turns. The execution
remained unknown without a captured result; injected input remained accepted. Records were retained,
T2 did not overtake, and the uncertain external operation was not replayed, but the original result
could not be recovered and the Session could not continue automatically.

The stateless HTTP fixture has no durable execution/result lookup. Once the original Worker dies
before capturing the response, a replacement can only preserve unknown. This matches the current
boundary against replaying uncertain external effects but fails automatic completion in this window.
Durable external execution identity and result lookup/confirmation are required; blindly retrying
an unknown call is not a valid repair.

## Evidence and final state

- [Summary JSON](../../release/dist/ha-recovery-20261019-2058/summary.json)
- [Baseline](../../release/dist/ha-recovery-20261019-2058/baseline/report.json), [Runtime restart](../../release/dist/ha-recovery-20261019-2058/runtime-restart/report.json), [Worker restart](../../release/dist/ha-recovery-20261019-2058/tool-worker-restart/report.json), [new work after restart](../../release/dist/ha-recovery-20261019-2058/post-worker-restart/report.json).
- Evidence is local and ignored, containing private API states, physical calls and database facts. Reproduce with [verify_recovery.py](verify_recovery.py) using the [README](README.md#two-replica-owner-pod-restart-acceptance).
- Final checks at 21:09:50 Asia/Shanghai: all five components at 2/2 Ready, public health checks passing, Deployment/StatefulSet configuration and images unchanged, other namespaces unchanged, and the prepared manual Session still empty.
- The failed Session is retained for diagnosis. No business results were fabricated, inserted or deleted.
