# RuntimeContext DashScope Model Name Fix

## Problem

`RuntimeContextExample` constructs `DashScopeChatModel` directly but passes the registry-style
identifier `dashscope:qwen-plus` to `modelName`. The direct model sends that value unchanged to
DashScope, which rejects it with HTTP 400 and `InvalidParameter: Model not exist.`

## Chosen Design

Change the example's direct model name to `qwen-plus`. Keep provider-prefixed identifiers such as
`dashscope:qwen-plus` limited to `ReActAgent.Builder.model(String)`, where `ModelRegistry` resolves
the provider and strips the prefix before constructing the DashScope model.

This is intentionally an example-only correction. The DashScope builder will not silently normalize
provider-prefixed identifiers because doing so would blur the distinction between registry IDs and
provider API model names.

## Alternatives Considered

- Normalize `dashscope:` inside `DashScopeChatModel.Builder`: rejected because it changes public API
  behavior and can hide caller configuration mistakes.
- Replace the explicit model construction with `.model("dashscope:qwen-plus")`: valid, but it would
  remove the example's explicit formatter and non-streaming configuration, making the change broader
  than necessary.

## Testing

Add a focused source-level example test that verifies `RuntimeContextExample` uses
`.modelName("qwen-plus")` and does not pass the registry-prefixed identifier to the direct builder.
Run that test before and after the source correction to demonstrate the regression test's red/green
behavior, then run the documentation example module's test suite and formatting checks.

## Scope

- Update one model name in `RuntimeContextExample`.
- Add one regression test in the documentation examples module.
- Do not change runtime model resolution, persistence behavior, or database code.
