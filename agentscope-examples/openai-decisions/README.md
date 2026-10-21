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

When a chat model is `gpt-6-luna`, the example sets `reasoning_effort` to `none` on that model:
[Luna requires this for function tools in Chat Completions](https://developers.openai.com/api/docs/models/gpt-6-luna).
For example, `OPENAI_MODEL=gpt-6-luna` works with the `select` and `guard` scenarios without source
changes. This setting applies to chat requests, not Decisions, and other chat models keep their
default generation options. Choose models that support tools through Chat Completions.

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

## Connection troubleshooting

If the example fails with `HTTP connect timed out`, verify that the JVM can reach
`https://api.openai.com`. A browser's Windows proxy setting does not establish that the Java
clients use the same proxy. For a local HTTP proxy listening on `127.0.0.1:7890`, set the following
in the PowerShell session before running the Maven command above (adjust the host and port):

```powershell
$env:MAVEN_OPTS = "$env:MAVEN_OPTS -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890"
```

For IntelliJ IDEA, put those four `-D` properties in the main class's **VM options**.
Both the chat model's JDK transport and the Decisions SDK use these JVM proxy settings.
This keeps the official API endpoint; setting only `HTTP_PROXY` or `HTTPS_PROXY` does not configure
these clients. Keep `OPENAI_API_KEY` in the environment as described above.

`No SLF4J providers were found` means this minimal example has no logging backend; it does not
cause a connection timeout. A Decisions failure prints its sanitized error before applying the
scenario's fallback. An answer produced after that failure does not verify a successful decision.
The standalone main shuts down Reactor's shared schedulers on exit. With `exec:java`, the SDK's
OkHttp/Okio daemon threads can still cause Maven thread-cleanup warnings; running the main class
directly in IntelliJ IDEA avoids Maven's thread-group cleanup. Embedded applications should manage
their own scheduler lifecycle.

## Offline verification

```shell
mvn -B -T1C -pl agentscope-examples/openai-decisions -am test -Dtest=OpenAIDecisionsMiddlewareExampleTest "-Dsurefire.failIfNoSpecifiedTests=false"
```

These tests use stub models and decision results through the real ReAct loop. They check routing,
filtered model schemas, simulated execution, visible denial events, and invalid arguments without
credentials or API calls. They do not measure live model quality.

See the [English guide](../../docs/v2/en/integration/judge/openai-decisions.md) or
[Chinese guide](../../docs/v2/zh/integration/judge/openai-decisions.md) for configuration and fallback details.
