# AutoContextMemory

AutoContextMemory is an intelligent context memory management system that automatically compresses, offloads, and summarizes conversation history to optimize LLM context window usage.

## Background & Problem

### Problem Background

When building intelligent Agent systems based on Large Language Models (LLMs), context window management is a critical challenge:

1. **Context Window Limitations**: Most LLM models have fixed context window size limits (e.g., 128K tokens). When conversation history exceeds this limit, the model cannot process the complete context.

2. **Cost Issues**: As conversation history grows, the number of tokens sent with each API call increases, leading to:
   - Linear growth in API call costs
   - Longer response times
   - Increased computational resource consumption

3. **Information Redundancy**: In long conversations, earlier content may no longer be relevant, but the system still needs to process this information, causing resource waste.

4. **Risk of Losing Critical Information**: Simple truncation strategies lose important information, affecting the Agent's decision quality and task completion capability.

### AutoContextMemory Solution

AutoContextMemory addresses these issues through intelligent compression and context management:

- **Automatic Compression**: Automatically triggers compression strategies when context exceeds thresholds, reducing token usage
- **Intelligent Summarization**: Uses LLM models to intelligently summarize historical conversations, retaining key information instead of simple truncation
- **Content Offloading**: Offloads large content to external storage, enabling on-demand reload via UUID
- **Progressive Strategies**: Employs 6 progressive compression strategies, from lightweight to heavyweight, ensuring maximum compression while retaining information
- **Full Traceability**: All original content is saved in original storage, supporting complete history tracing
- **Event Tracking**: Records detailed information about each compression operation for analysis and optimization

## Overview

AutoContextMemory implements the `Memory` interface, providing automated context management functionality. When conversation history exceeds configured thresholds, the system automatically applies multiple compression strategies to reduce context size while preserving important information as much as possible.

## Core Features

- **Automatic Compression**: Automatically triggers compression when message count or token count exceeds thresholds
- **Progressive Compression Strategies**: Uses 6 progressive compression strategies, from lightweight to heavyweight
- **Intelligent Summarization**: Uses LLM models for intelligent conversation summarization
- **Content Offloading**: Offloads large content to external storage, reducing memory usage
- **Tool Call Preservation**: Preserves tool call interface information (names, parameters) during compression
- **Dual Storage Mechanism**: Working storage (compressed) and original storage (complete history)

## Architecture Design

### Storage Architecture

AutoContextMemory uses a multi-storage mechanism:

1. **Working Memory Storage**: Stores compressed messages for actual conversations
2. **Original Memory Storage**: Stores complete, uncompressed message history (append-only mode)
3. **Offload Context Storage**: Uses `Map<String, List<Msg>>` to store offloaded message content, keyed by UUID
4. **Compression Events Storage**: Records detailed information about all compression operations, including event type, timestamp, message count, token consumption, etc.
5. **State Persistence**: All four storages support state serialization and deserialization through `StateModuleBase`, enabling context persistence when combined with `SessionManager`

### Compression Strategies

The system applies 6 compression strategies in the following order. Compression follows these core principles:

- **Current Round Priority**: Current round messages are more important than historical round messages; protect current round's complete information first
- **User Interaction Priority**: User input and Agent responses are more important than intermediate results from tool call inputs/outputs
- **Traceability**: All original compressed content can be traced back via UUID, ensuring no information is lost

The system applies 6 compression strategies in the following order:

#### Strategy 1: Compress Historical Tool Calls
- Finds consecutive tool call messages in historical conversations (exceeding `minConsecutiveToolMessages`, default: 6)
- Uses `lastKeep` parameter to protect the last N messages from compression (this parameter takes effect in this strategy)
- Uses LLM to intelligently compress tool call history
- Preserves tool names, parameters, and key results
- For plan-related tools, uses minimal compression (only retains brief descriptions)

#### Strategy 2: Offload Large Messages (with lastKeep protection)
- Finds large messages exceeding `largePayloadThreshold`
- Protects the latest assistant response and last N messages (`lastKeep` parameter takes effect in this strategy, shared with Strategy 1)
- Offloads original content and replaces with preview (first 200 characters) and UUID prompt

#### Strategy 3: Offload Large Messages (without protection)
- Similar to Strategy 2, but does not protect the last N messages (`lastKeep` parameter does not take effect in this strategy)
- Only protects the latest assistant response

#### Strategy 4: Summarize Historical Conversation Rounds
- Finds all user-assistant conversation pairs before the latest assistant response
- Summarizes each conversation round (including tool calls and assistant responses)
- Uses LLM to generate intelligent summaries, preserving key decisions and information

#### Strategy 5: Summarize Large Messages in Current Round
- Finds large messages exceeding threshold in current round (after latest user message)
- Uses LLM to generate summaries and offloads original content
- Replaces with summarized version

#### Strategy 6: Compress Current Round Messages
- Triggers when historical messages are compressed but context still exceeds limit
- Compresses all messages in current round (typically tool calls and results)
- Merges multiple tool results, preserving key information
- Supports configurable compression ratio (`currentRoundCompressionRatio`, default 30%)
- Provides more concise summaries for plan-related tool calls, preserving task-related information

## Configuration Parameters

### AutoContextConfig

All configuration parameters can be set through `AutoContextConfig`:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `msgThreshold` | int | 100 | Message count threshold to trigger compression |
| `maxToken` | long | 128 * 1024 | Maximum token limit for context window |
| `tokenRatio` | double | 0.75 | Token ratio threshold to trigger compression (0.0-1.0) |
| `lastKeep` | int | 50 | Number of recent messages to keep uncompressed (only effective in Strategies 1 and 2) |
| `largePayloadThreshold` | long | 5 * 1024 | Large message threshold (character count) |
| `offloadSinglePreview` | int | 200 | Preview length for offloaded messages (character count) |
| `minConsecutiveToolMessages` | int | 6 | Minimum consecutive tool messages required for compression |
| `currentRoundCompressionRatio` | double | 0.3 | Compression ratio for current round messages (0.0-1.0), default 30% |

### Configuration Example

```java
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(50)
    .maxToken(64 * 1024)
    .tokenRatio(0.7)
    .lastKeep(20)
    .largePayloadThreshold(10 * 1024)
    .offloadSinglePreview(300)
    .minConsecutiveToolMessages(4)
    .currentRoundCompressionRatio(0.3)  // Compress current round to 30%
    .build();
```

## Usage

### Basic Usage

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.tool.Toolkit;

// Configuration
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)
    .lastKeep(10)
    .tokenRatio(0.3)
    .build();

// Create memory
AutoContextMemory memory = new AutoContextMemory(config, model);

// AutoContextMemory implements ContextOffLoader interface, can be used directly
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ContextOffloadTool(memory));

// Create Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .build();
```

## API Reference

### AutoContextMemory

#### Main Methods

- `void addMessage(Msg message)`: Add message to working storage and original storage
- `List<Msg> getMessages()`: Get message list (may trigger compression)
- `void deleteMessage(int index)`: Delete message at specified index from working storage
- `void clear()`: Clear all storages
- `List<Msg> getOriginalMemoryMsgs()`: Get complete message history from original memory storage
- `List<Msg> getInteractionMsgs()`: Get user-assistant interaction messages (filter tool calls)
- `Map<String, List<Msg>> getOffloadContext()`: Get offload context mapping
- `List<CompressionEvent> getCompressionEvents()`: Get list of all compression event records

#### ContextOffLoader Interface Methods

- `void offload(String uuid, List<Msg> messages)`: Offload messages to storage
- `List<Msg> reload(String uuid)`: Reload offloaded messages by UUID
- `void clear(String uuid)`: Clear offloaded content for specified UUID

### ContextOffloadTool

Provides `context_reload` tool, allowing Agent to reload previously offloaded context messages by UUID.

```java
@Tool(name = "context_reload", description = "...")
public List<Msg> reload(@ToolParam(name = "working_context_offload_uuid") String uuid)
```

## How It Works

### Compression Trigger Conditions

Compression is triggered under the following conditions:

1. **Message Count Threshold**: `currentMessages.size() >= msgThreshold`
2. **Token Count Threshold**: `calculateToken(currentMessages) >= maxToken * tokenRatio`

Compression triggers when either condition is met.

### Compression Flow

1. Check if compression threshold is reached
2. Try 6 compression strategies in order
3. Return compressed message list immediately after each strategy succeeds
4. If all strategies fail to meet requirements, log warning and return current working storage

### Message Protection Mechanism

- **lastKeep Protection**: Last N messages will not be compressed
- **Latest Assistant Response Protection**: Latest final assistant response and all messages after it will not be compressed
- **Current Round Protection**: Messages in current round (after latest user message) preferentially use lighter compression strategies

## Prompt System

AutoContextMemory uses predefined prompts to guide LLM compression and summarization. Prompts are organized according to the progressive order of compression strategies:

### Strategy 1: Tool Call Compression
- `TOOL_INVOCATION_COMPRESS_PROMPT_START/END`: Tool call compression prompts
  - Preserve tool names, parameters, and key results
  - Use minimal compression for plan-related tools

### Strategy 4: Historical Conversation Summarization
- `PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT_START/END`: Historical conversation round summarization prompts
  - Preserve key decisions and information
  - Summarize user-assistant conversation pairs

### Strategy 5: Current Round Large Message Summarization
- `CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT_START/END`: Current round large message summarization prompts
  - Generate summaries for individual large messages
  - Preserve key information

### Strategy 6: Current Round Message Compression
- `CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT_START/END`: Current round message compression prompts
  - Support configurable compression ratio (`currentRoundCompressionRatio`)
  - Explicitly specify target character count
  - Provide concise summaries for plan-related tool calls
  - Emphasize low compression rate, preserve task-related information

All prompts are designed to preserve key information while reducing token usage. Strategy 6 prompts are specially optimized with explicit target character counts and strict compression requirements to ensure compression results meet expectations.

## Compression Event Tracking

AutoContextMemory provides a complete compression event tracking system that records detailed information about each compression operation:

### CompressionEvent

Each compression event contains the following information:

- **eventType**: Compression strategy type (TOOL_INVOCATION_COMPRESS, LARGE_MESSAGE_OFFLOAD, PREVIOUS_ROUND_CONVERSATION_SUMMARY, etc.)
- **timestamp**: Event timestamp (milliseconds)
- **compressedMessageCount**: Number of compressed messages
- **previousMessageId**: ID of message before compression range
- **nextMessageId**: ID of message after compression range
- **compressedMessageId**: ID of compressed message (if applicable)
- **metadata**: Metadata containing:
  - `inputToken`: Input tokens consumed by compression operation (from `_chat_usage`)
  - `outputToken`: Output tokens consumed by compression operation (from `_chat_usage`)
  - `time`: Compression operation duration (seconds, from `_chat_usage`)
  - `tokenBefore`: Token count before compression (offload operations only)
  - `tokenAfter`: Token count after compression (offload operations only)

### Using Compression Events

```java
// Get all compression events
List<CompressionEvent> events = memory.getCompressionEvents();

// Analyze compression effectiveness
for (CompressionEvent event : events) {
    System.out.println("Event Type: " + event.getEventType());
    System.out.println("Timestamp: " + new Date(event.getTimestamp()));
    System.out.println("Compressed Messages: " + event.getCompressedMessageCount());
    System.out.println("Input Tokens: " + event.getCompressInputToken());
    System.out.println("Output Tokens: " + event.getCompressOutputToken());
    System.out.println("Time: " + event.getMetadata().get("time") + " seconds");
    System.out.println("Token Reduction: " + event.getTokenReduction());
}
```

## Message Metadata

Compressed messages contain the following metadata information:

### _compress_meta

Contains compression-related meta information:

- `offloaduuid`: UUID of offloaded message (if message was offloaded)

### _chat_usage

Contains LLM call usage information (if LLM was used for compression):

- `inputTokens`: Input token count
- `outputTokens`: Output token count
- `time`: Execution time (seconds)

This information can be obtained directly from message metadata:

```java
Msg compressedMsg = ...;
Map<String, Object> metadata = compressedMsg.getMetadata();
if (metadata != null) {
    // Get compression meta information
    Map<String, Object> compressMeta = (Map<String, Object>) metadata.get("_compress_meta");
    if (compressMeta != null) {
        String uuid = (String) compressMeta.get("offloaduuid");
    }
    
    // Get LLM usage information
    ChatUsage chatUsage = (ChatUsage) metadata.get(MessageMetadataKeys.CHAT_USAGE);
    if (chatUsage != null) {
        int inputTokens = chatUsage.getInputTokens();
        int outputTokens = chatUsage.getOutputTokens();
        double time = chatUsage.getTime();
    }
}
```

## State Persistence

AutoContextMemory inherits from `StateModuleBase` and supports state serialization and deserialization:

- `workingMemoryStorage`: Working memory storage state
- `originalMemoryStorage`: Original memory storage state
- `offloadContext`: Offload context state
- `compressionEvents`: Compression event records state

This enables saving and restoring memory state between sessions. Combined with `SessionManager`, context information can be persisted to databases or other persistent storage, enabling cross-session context management.

## Best Practices

1. **Set Thresholds Appropriately**: Adjust `maxToken` and `tokenRatio` based on model context window size and actual usage scenarios
2. **Protect Important Messages**: Use `lastKeep` to ensure recent conversations are not compressed
3. **Register Reload Tool**: Register `ContextOffloadTool` so Agent can access offloaded detailed content when needed
4. **Monitor Compression Logs**: Pay attention to log output to understand compression strategy application
5. **Choose Appropriate Model**: Model used for compression should have good summarization capabilities

## Notes

1. **LLM Calls**: Compression process requires LLM model calls, incurring additional API call costs. Use `CompressionEvent` to track token consumption for each compression
2. **Synchronous Processing**: Compression is synchronous and blocking, which may affect response time. Monitor compression duration via `CompressionEvent`'s `time` field
3. **Information Loss**: Compression may lose some detailed information, although the system tries to preserve key information. All original content is saved in `originalMemoryStorage` and can be traced back via UUID
4. **Memory Usage**: Original storage, offload context, and compression event records occupy additional memory
5. **Compression Event Persistence**: Compression event records are saved with state persistence. Long-running applications may accumulate many event records; consider periodic cleanup or archiving

## Dependencies

- `agentscope-core`: Core functionality dependency

## License

Apache License 2.0

## Related Documentation

- [AgentScope Java Documentation](https://agentscope.readthedocs.io/)

