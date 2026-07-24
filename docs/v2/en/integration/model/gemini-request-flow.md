# Gemini End-to-End Request Flow

This diagram follows one streaming Gemini request from the user's question to the final answer. The tool-call and history-replay branches are part of the same request loop: when the model asks for a tool, the Agent executes it and calls the model again with the tool result.

```{mermaid}
flowchart TD
    U(["User question"]) --> APP["Application receives input"]
    APP --> AGENT["ReActAgent.call(...) / streamEvents(...)"]
    AGENT --> HISTORY["Load conversation history, tools, and GenerateOptions"]
    HISTORY --> MODEL["ChatModelBase.stream(...)<br/>-> GeminiChatModel.doStream(...)"]

    subgraph REQUEST["Request preparation"]
        MODEL --> BUILDER["Create GenerateContentConfig.Builder"]
        BUILDER --> FORMAT["formatter.format(messages)"]
        FORMAT --> CONVERT["GeminiMessageConverter.convertMessages(...)"]
        CONVERT --> CONTENT["Build Gemini Content / Part<br/>Text / Thinking / FunctionCall<br/>Restore thoughtSignature on assistant Parts"]
        CONTENT --> TOOLS["formatter.applyTools(...)<br/>Apply ToolChoice when configured"]
        TOOLS --> OPTIONS["GeminiChatFormatter.applyOptions(...)"]
        OPTIONS --> THINKING["Build ThinkingConfig by field<br/>thinkingBudget / thinkingLevel / includeThoughts"]
        THINKING --> CONFIG["configBuilder.build()<br/>Create GenerateContentConfig"]
    end

    CONFIG --> SDK["Google Gen AI Client<br/>generateContentStream(...)"]
    SDK --> API(["Gemini API"])

    subgraph RESPONSE["Response processing"]
        API --> STREAM["ResponseStream&lt;GenerateContentResponse&gt;"]
        STREAM --> PARSER["GeminiResponseParser.parseResponse(...)"]
        PARSER --> BLOCKS["ChatResponse content blocks<br/>ThinkingBlock / TextBlock / ToolUseBlock<br/>with thoughtSignature metadata"]
        BLOCKS --> ACC["ReasoningContext.processChunk(...)"]
        ACC --> LIVE["Live streaming events / UI output"]
        ACC --> FINAL["ReasoningContext.buildFinalMessage()<br/>Aggregate Thinking / Text / ToolUse"]
        FINAL --> STATE["Write this AssistantMessage to AgentState / context"]
        STATE --> ROUND{"Does this message contain ToolUseBlock?"}
    end

    ROUND -->|Yes| EXEC["Execute the ToolUseBlock tool"]
    EXEC --> RESULT["Create ToolResultBlock"]
    RESULT --> APPEND["Write the ToolResultBlock to AgentState / context"]
    APPEND -. Next model request .-> MODEL

    ROUND -->|No| RESULT_MSG["AgentResultEvent / final Msg"]
    RESULT_MSG --> SAVE["After doCall, saveStateToSession(...)"]
    SAVE --> PERSIST{"Is cross-process state persistence configured?"}
    PERSIST -->|No| ANSWER(["Return final answer to user"])
    PERSIST -->|Yes| JSON["Serialize Msg as JSON<br/>byte[] thoughtSignature -> Base64 String"]
    JSON --> ANSWER
    JSON --> RESTORE["Read and deserialize Msg on the next turn"]
    RESTORE -. Next request .-> MODEL

    classDef input fill:#e3f2fd,stroke:#1976d2,color:#0d47a1
    classDef core fill:#fff3e0,stroke:#ef6c00,color:#4e342e
    classDef gemini fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    classDef state fill:#f3e5f5,stroke:#7b1fa2,color:#4a148c
    classDef decision fill:#fffde7,stroke:#f9a825,color:#5d4037
    classDef terminal fill:#e0f2f1,stroke:#00796b,color:#004d40

    class U input
    class ANSWER terminal
    class APP,AGENT,HISTORY,MODEL,BUILDER,FORMAT,CONVERT,TOOLS,OPTIONS,THINKING,CONFIG core
    class CONTENT,SDK,API,STREAM,PARSER,BLOCKS gemini
    class ACC,LIVE,FINAL,STATE,EXEC,RESULT,APPEND,RESULT_MSG,SAVE,JSON,RESTORE state
    class ROUND,PERSIST decision
```

Legend: blue is user input, teal is the final output, orange is the Agent and request preparation, green is Gemini/SDK, purple is accumulated state, and yellow is a branch decision.

## Key Nodes and Source Code

| Flow node | Main implementation | Responsibility |
| --- | --- | --- |
| Model entry point | `ChatModelBase.stream` -> `GeminiChatModel.doStream` | The public `stream` entry reaches Gemini's implementation, which builds request configuration, formats messages, and calls the Google Gen AI SDK |
| Request option mapping | `GeminiChatFormatter.applyOptions` | Maps `GenerateOptions` to `GenerateContentConfig` |
| Message conversion | `GeminiMessageConverter.convertMessages` | Converts `Msg` objects to Gemini `Content` and `Part` objects |
| Response parsing | `GeminiResponseParser.parseResponse` | Converts each Gemini response chunk to `ChatResponse` |
| Streaming aggregation | `ReasoningContext.processChunk` | Aggregates text, thinking, and tool calls while emitting live messages |
| Signature restoration | `GeminiThoughtSignatureUtils` | Converts between in-memory `byte[]` and Base64 strings after JSON persistence |

## Three Completion Paths

1. **Ordinary answer**: When there is no `ToolUseBlock`, the Agent aggregates the final message and returns the text to the user.
2. **Tool call**: The model returns a `ToolUseBlock`; the Agent executes the tool, adds a `ToolResultBlock` to history, and starts another model turn.
3. **Persistent history**: After a `Msg` is serialized as JSON, the Gemini signature becomes a Base64 string. The next request restores it to the `byte[]` required by the SDK.

## Thinking and Signatures

- `thinkingBudget`, `thinkingLevel`, and `includeThoughts` enter `ThinkingConfig` during request preparation.
- Gemini thinking Parts become `ThinkingBlock` objects, ordinary text Parts become `TextBlock` objects, and function-call Parts become `ToolUseBlock` objects.
- `thoughtSignature` belongs to a Part, not only to tool calls; parsing, streaming aggregation, and JSON round trips must preserve it.
- Historical assistant `ThinkingBlock`, signed `TextBlock`, and signed `ToolUseBlock` objects are regenerated as their corresponding Gemini Parts on the next request.

The multi-agent formatter rewrites ordinary multi-agent history into tagged user text, so an original signature cannot be attached to rewritten content. Exact tool sequences still use the same Part replay path.
