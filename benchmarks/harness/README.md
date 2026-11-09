# Harness evaluations and benchmarks

Core evaluation tooling for AgentScope Java Harness, Codex and Pi: native adapters, frozen tasks,
protocol checks, deterministic scoring and offline release comparisons.
中文：[中文说明](README_zh.md)。

This directory does not include cluster submission, cloud storage or deployment operations. The
standalone Java CLI can run locally; Harbor adapters require a separately supplied execution
environment. There is no complete local suite orchestrator in this branch. Business tasks additionally
require isolated official tau-bench/AppWorld environments and their data; task wrappers alone cannot
execute or score them.

## Layout

| Path | Responsibility |
| --- | --- |
| `eval.py`, `release_cli.py` | Protocol checks, task export, contract tests and offline release decisions |
| `adapters/agentscope-java/` | Native Java CLI and business-tool HTTP runner |
| `runtime/`, `schemas/` | Native event conversion, scenario drivers, scoring and scheduling primitives |
| `configs/suites/` | Task selection, agent/model profiles, budgets and scoring policies |
| `datasets/` | Directly maintained Harness and capability tasks |
| `external/` | External source locks, task selections, templates and adaptation overlays |
| `environments/` | Portable sandbox and task image recipes; no deployment credentials |
| `contracts/` | Production framework test selection |
| `reports/`, `report.py` | Offline reports and comparisons; `report.html` is a source template |
| `worker.py`, `runtime/business_server.py` | Official business-environment adapters and authenticated tool/scorer boundary |
| `snapshot.py` | Candidate source snapshots |

- [Dataset organization](datasets/README.md)
- [Release regression and comparisons](docs/release-regression.md)
- [Event protocol](docs/agent-protocol.md)

## Local use

Run from this directory with Python 3.12+ and a virtual environment:

```sh
python -m pip install -r requirements-protocol.txt
python eval.py protocol-smoke
python eval.py release validate --definitions-only
python eval.py prepare-tasks configs/suites/full.json
python eval.py release validate --config configs/suites/full.json
python eval.py export-tasks configs/suites/full.json --output artifacts/export-full
```

Preparation fetches selected external sources at locked commits and verifies SHA256 hashes.
Use `prepare-tasks ... --offline` to reuse verified downloads. Owned tasks need no downloads.
Export copies prepared tasks and a provenance manifest into a self-contained directory. It does
not start agents, install official business worlds or provision environments. Existing output directories are
rejected. Profiles retain explicit model defaults for reproducibility; change them deliberately and
establish a new comparison baseline. Provider aliases are not immutable model snapshots.

Install `requirements-evals.txt` for Harbor adapter tests. From the repository root:

```sh
PYTHONPATH=benchmarks/harness python -m unittest discover -s benchmarks/harness/tests -p 'test_*.py'
PYTHONPATH=benchmarks/harness python -m unittest test_worker test_snapshot test_runner_integration test_prepare_appworld test_report test_compare_reports test_combine_reports
mvn -B -T1 -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -DskipTests -Dmaven.javadoc.skip=true -Dgpg.skip=true
mvn -B -T1 -f benchmarks/harness/adapters/agentscope-java/pom.xml package dependency:copy-dependencies spotless:check -DincludeScope=runtime -Dcandidate.version=2.1.0-SNAPSHOT
```

Use the candidate's actual Maven version. `HARNESS_EVAL_CLASSPATH` must include the adapter's absolute
`target/classes` and `target/dependency/*` paths to enable Java loopback integration tests.
`HARNESS_EVAL_MAVEN_TEST=1` enables snapshot Maven packaging checks. Tests use local fixtures and mocks.

Keep downloads, traces, generated reports and investigation notes under ignored `artifacts/`.
Do not commit credentials, virtual environments, build outputs or campaign progress records.
