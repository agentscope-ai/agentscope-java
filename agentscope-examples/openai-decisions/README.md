# OpenAI Decisions middleware examples

Run the three middleware scenarios independently and inspect the decision answers, actual model,
tool schemas, and tool-result states. Business tools return simulated strings: `delete_file` never
changes the filesystem. The runnable main uses real OpenAI Decisions and Chat Completions APIs.

## Build and run

From the repository root, using Java 17 or later:

```shell
mvn -B -T1C -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/openai-decisions -am install -DskipTests
```

Set `OPENAI_API_KEY` to a key with access to Decisions and Chat Completions. Set `OPENAI_MODEL` to
a Chat Completions model that supports tools, and `OPENAI_FAST_MODEL` to a second, faster model for
the routing scenario. Use model names without the `openai:` prefix. Both clients use the official
`https://api.openai.com/v1` endpoint in this example.

For example, in PowerShell:

```powershell
$env:OPENAI_API_KEY = '<your-key>'
$env:OPENAI_MODEL = '<capable-model-name>'
$env:OPENAI_FAST_MODEL = '<fast-model-name>'
```

Or in a POSIX shell:

```bash
export OPENAI_API_KEY='<your-key>'
export OPENAI_MODEL='<capable-model-name>'
export OPENAI_FAST_MODEL='<fast-model-name>'
```

Run one scenario; replace `route` with `select` or `guard` as needed:

```shell
mvn -B -T1C -pl agentscope-examples/openai-decisions org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=io.agentscope.examples.decisions.OpenAIDecisionsMiddlewareExample" "-Dexec.args=route"
```

| Argument | Default task | What to inspect |
| --- | --- | --- |
| `route` | Explain ReAct in one sentence | Choice probabilities, route, and the actual model receiving the request. This scenario has no tools, matching the router's default compatibility policy. |
| `select` | Look up Beijing weather | Predicate probabilities and actual model tool schemas. At most one optional tool is selected. |
| `guard` | Delete a production backup without checking for another backup | P(safe), tool-result text, and `DENIED` or the simulated tool result. Only `delete_file` is guarded. |

Append your own prompt with, for example, `"-Dexec.args=route Design a distributed task scheduler"`.
Routing retains the original model on low confidence; selection retains the original tool set on
ambiguous probabilities. Both retain the original configuration on request failure or refusal,
while the guard denies. Real decisions are probabilistic, so the default
prompts do not guarantee a particular route or denial. The guard applies when the chat model
actually requests `delete_file`; it does not force a tool call. Passing the check still goes through
core permissions. All middleware is explicitly enabled by this example; the library defaults to off.

## Offline verification

```shell
mvn -B -T1C -pl agentscope-examples/openai-decisions -am test -Dtest=OpenAIDecisionsMiddlewareExampleTest "-Dsurefire.failIfNoSpecifiedTests=false"
```

These tests use stub models and decision results through the real ReAct loop. They check routing,
filtered model schemas, simulated execution, visible denial events, and invalid arguments without
credentials or API calls. They do not measure live model quality.

See the [English guide](../../docs/v2/en/integration/judge/openai-decisions.md) or
[Chinese guide](../../docs/v2/zh/integration/judge/openai-decisions.md) for configuration and fallback details.
