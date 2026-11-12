# Terminal/SWE sandbox adapter preparation

[中文](README_zh.md) · [Acceptance and selected tasks](../../../../docs/acceptance/native-context-eval-20261103/README.en.md)

This package adapts deployed Managed Agents Sessions to isolated Harbor task Pods.
It does not replace any Harness kernel. All 15 Terminal smoke first attempts are terminal and archived, with scorer-only recoveries separate. Three controllers now run the first five accepted formal Terminal tasks. Remaining Terminal and SWE environments require acceptance.

- `harbor_environment.py` uses Harbor 0.25's ACK environment with digest-pinned
  single-container CPU images. Task Pods have no service-account token, privileged
  mode, host volumes or imported controller credentials. An existing Pod causes
  inspection instead of Harbor's default delete/recreate behavior.
- `exec_transport.py` requires an actual Kubernetes execution exit receipt.
  Disconnections, absent receipts and oversized transport output stay unknown.
- `terminal_process.py` runs inside the task Pod using Python's standard library.
  It supports commands, persistent interactive PTYs, stdin, cancellation, real exit
  codes and paged stdout/stderr. Stable operation IDs retain command/input receipts;
  interrupted daemon state never permits implicit re-execution. Process logs remain
  inside the task until the controller exports them. Shell commands provide file
  reading, writing and editing capabilities in the same filesystem.
- `terminal_bridge.py` supplies four equivalent tools to all three Harnesses:
  start, poll, input and cancel. It records RPC requests/results outside the task Pod.
- `terminal_mcp.py` checks the tool key and exact trial header from the audit
  gateway. An unknown command outcome closes that trial's terminal access.

The audit gateway's optional `--terminal` backend is disabled by default. New
source code is not deployed over active AppWorld runs. A terminal backend needs a
fixed cluster Service address, independent trial binding and the existing model
audit routing. Keep one active trial per Harness until audit routing supports more.

Install `requirements.txt` in a Python 3.12+ virtual environment on the controller.
Keep its credentials, dataset solutions and test files outside task containers
during agent execution. Oracle checks use separate disposable Pods. Do not expose
the preparation PVC or Kubernetes controller credentials to the tested model.

Run the parent directory's `test_terminal_process.py`, `test_sandbox_exec_transport.py`,
`test_terminal_mcp.py` and evaluation-gateway tests. MCP tests also run in the pinned
cluster environment. Synthetic cluster acceptance has checked exit code 7, stderr,
file persistence, PTY state and repeated-input receipt recovery. These are transport
checks, not benchmark scores or evidence of context compaction.

`session_driver.py` and `harbor_agent.py` delegate to the deployed Session API; `run_terminal.py` admits only a frozen, hash-verified task plan with reference-solution acceptance. Official verifier files arrive after model execution. Unsettled usage prevents grading; failures retain their Pod/lease for inspection. Per-task official timeouts are honored; only reviewed unattempted continuations may proceed.

`run_swe.py` uses the same Session controller with a SWE-only accepted plan and
budget profile. It verifies the clean base commit, exports a full prediction patch
using a separate Git index, and applies it to a different clean grading Pod before
loading official tests. `grading.py` requires actual CTRF/SWE parser evidence;
missing test dependencies are unscored, even if a shell script wrote raw reward 0.
`preparation.py` records declared APT/PyPI distribution routing and the optional
pre-release planarity 0.6 compatibility constraint; existing running batches keep
their immutable code and plans. SWE reference/clean-grader acceptance passed; Session evaluation is in progress.


`artifact_transfer.py` reads immutable snapshots in 8 KiB chunks and verifies their
complete SHA256 before patch application or extraction. Failed transfers preserve
partial files and source snapshots. `swe_patch.prepare_repository` accepts only
clean image setup commits with identical paths and blob contents before restoring
the declared base commit and modes. This preparation runs before any model action
and also in the separate grader. Runtime preparation uses a separate Python 3.13.9
parser interpreter and uv 0.7.13 without changing conda/testbed Python. Official
GitHub dependency access has passed reference acceptance through the restricted local relay; Session trials are active.

SWE images may carry an upstream setup commit that changes dependency metadata. An explicitly pinned `setup_commit` is preserved in both the model Pod and clean grader (Astropy pins setuptools 68.0.0); model diffs use that same setup commit, while the official `base_commit` remains recorded separately. Unapproved content changes and dirty fresh images still fail validation. Minimal Ubuntu images without a trust store use a separately declared, signature-verified APT bootstrap for CA certificates and curl before restoring HTTPS. These preparations are checked on new reference Pods; active v13 model batches keep their original code.


Five SWE reference/clean-grader checks passed and development Session trials are active. The local restricted GitHub CONNECT relay preserves TLS and must stay available. Pod readiness now uses the official environment preparation budget, separately from model/verifier deadlines. Future Terminal admission uses the declared Debian distribution proxy profile only for pMARS; other profiles and already-frozen controllers are unchanged.
