# Issue 1852: DashScope Structured Output Fix

## Background

Issue: <https://github.com/agentscope-ai/agentscope-java/issues/1852>

DashScope structured output failed when callers used APIs such as
`ReActAgent.call(msgs, MyClass.class, ctx)` or the JSON Schema overload. The
returned `Msg` did not contain `MessageMetadataKeys.STRUCTURED_OUTPUT`, so
`Msg.hasStructuredData()` always returned `false`.

The same structured-output flow worked with `OpenAIChatModel`, which indicated
that the issue was specific to the DashScope provider integration.

## Root Cause

`ReActAgent` chooses one of two structured-output paths:

1. Native structured output, when `model.supportsNativeStructuredOutput()`
   returns `true`.
2. Fallback structured output, which exposes a synthetic `generate_response`
   tool and extracts the tool arguments as structured data.

Before this fix, `DashScopeChatModel.supportsNativeStructuredOutput()` returned
`true`. As a result, `ReActAgent` selected the native path and created a
`ResponseFormat.jsonSchema(...)` value in `GenerateOptions.responseFormat`.

However, the DashScope formatter did not map that option into the actual
DashScope request parameters. `DashScopeToolsHelper.applyOptions(...)` handled
options such as `temperature`, `topP`, `maxTokens`, `thinkingBudget`, `topK`,
`seed`, penalties, and `parallelToolCalls`, but it did not read
`GenerateOptions.getResponseFormat()`.

This meant `DashScopeParameters.responseFormat` remained `null`, and the
serialized request body did not include `response_format`. DashScope therefore
was not instructed to produce JSON. The model could return natural-language
text, and `ReActAgent.wrapNativeStructuredResult(...)` then failed to parse that
text as JSON. Because parsing failed, the `STRUCTURED_OUTPUT` metadata entry was
not written.

## Changes Made

### 1. Route DashScope structured output through fallback

File:

- `agentscope-core/src/main/java/io/agentscope/core/model/DashScopeChatModel.java`

Change:

```java
@Override
public boolean supportsNativeStructuredOutput() {
    return false;
}
```

This prevents `ReActAgent` from selecting the native `response_format` JSON
Schema path for DashScope. Structured-output calls now use the fallback
`generate_response` tool path, which is safer while DashScope native
`json_schema` support remains uncertain.

### 2. Preserve explicit responseFormat options for DashScope requests

File:

- `agentscope-core/src/main/java/io/agentscope/core/formatter/dashscope/DashScopeToolsHelper.java`

Change:

```java
ResponseFormat responseFormat =
        getOption(options, defaultOptions, GenerateOptions::getResponseFormat);
if (responseFormat != null) {
    params.setResponseFormat(responseFormat);
}
```

This fixes the missing option mapping. If callers explicitly set
`GenerateOptions.responseFormat`, the DashScope formatter now transfers it into
`DashScopeParameters.responseFormat`, which serializes as `response_format`.

## Tests Added

### DashScopeToolsHelperComprehensiveTest

File:

- `agentscope-core/src/test/java/io/agentscope/core/formatter/dashscope/DashScopeToolsHelperComprehensiveTest.java`

Added tests:

- `testApplyOptionsWithResponseFormat`
- `testApplyOptionsResponseFormatOptionsOverrideDefault`

These tests verify that:

- `GenerateOptions.responseFormat` is copied into
  `DashScopeParameters.responseFormat`.
- Per-call options override `defaultOptions` when both provide a response
  format.

### DashScopeChatModelTest

File:

- `agentscope-core/src/test/java/io/agentscope/core/model/DashScopeChatModelTest.java`

Added test:

- `testDoesNotSupportNativeStructuredOutput`

This test verifies that DashScope no longer advertises native structured output
support, so `ReActAgent` will use the fallback structured-output path.

## Verification

Run the focused test suite from the repository root:

```powershell
mvn -pl agentscope-core "-Dtest=DashScopeToolsHelperComprehensiveTest,DashScopeChatModelTest" test
```

Run formatting checks:

```powershell
mvn -pl agentscope-core spotless:check
```

Both commands passed after the change.

## Expected Behavior After Fix

When users call structured-output APIs with `DashScopeChatModel`, the agent no
longer relies on DashScope native JSON Schema response formatting. Instead, it
uses the fallback tool-based structured-output path.

Expected result:

- `Msg.hasStructuredData()` returns `true` when the model successfully invokes
  the structured-output tool.
- `Msg.getStructuredData(...)` can retrieve the parsed structured result.
- Explicit `GenerateOptions.responseFormat(...)` values are no longer silently
  dropped by the DashScope formatter.

## Notes

If DashScope later confirms full support for native `json_schema` response
format with strict schema enforcement across the target models, the model can be
updated to return `true` from `supportsNativeStructuredOutput()` again. The
formatter-side `responseFormat` mapping should remain in place either way.
