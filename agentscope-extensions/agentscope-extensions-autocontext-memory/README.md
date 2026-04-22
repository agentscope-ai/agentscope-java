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
- **Dual Storage Mechanism**: Working storage (compressed) and original storage (complete history)
- **PlanNotebook Awareness**: Automatically integrates with PlanNotebook to adjust compression strategies based on current plan state, ensuring critical plan-related information is preserved during compression
- **Prompt Customization**: Supports customizing compression strategy prompts for specific scenarios and domains, enabling targeted compression optimization

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
| `customPrompt` | PromptConfig | null | Custom prompt configuration (optional, uses default prompts if not set) |

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

When using `AutoContextMemory` with `ReActAgent`, it's recommended to use `AutoContextHook` to automatically handle integration setup. The hook takes care of all necessary configuration.

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.tool.Toolkit;

// Configuration
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)
    .lastKeep(10)
    .tokenRatio(0.3)
    .build();

// Create memory
AutoContextMemory memory = new AutoContextMemory(config, model);

// Create Agent with AutoContextHook for automatic integration
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(new Toolkit())
    .enablePlan()  // Enable PlanNotebook support (optional, but recommended)
    .hook(new AutoContextHook())  // Automatically registers ContextOffloadTool and attaches PlanNotebook
    .build();
```

The `AutoContextHook` automatically:
- Registers `ContextOffloadTool` to the agent's toolkit (enables context reload functionality)
- Attaches the agent's `PlanNotebook` to `AutoContextMemory` for plan-aware compression (if PlanNotebook is enabled)
- Triggers memory compression before each LLM reasoning call via `PreReasoningEvent` (ensures compression happens at a deterministic point)
- Ensures proper integration between `AutoContextMemory` and `ReActAgent`

### Custom Context Compression Prompts

AutoContextMemory uses default general-purpose compression prompts internally, which are carefully designed to meet most business needs. However, customizing compression prompts based on actual scenarios and business characteristics may achieve better compression results. For example, for specific domain tool invocation interfaces, you can explicitly guide the system to preserve which key information and appropriately discard which redundant content, thereby achieving higher compression rates while ensuring information integrity.

AutoContextMemory supports customizing compression strategy prompts, allowing optimization for specific domains and scenarios.

#### PromptConfig

The `PromptConfig` class is used to configure custom prompts. All prompts are optional. If not specified, default prompts from the `Prompts` class will be used.

Configurable prompts:

| Field | Description | Strategy |
|-------|-------------|----------|
| `previousRoundToolCompressPrompt` | Prompt for compressing previous round tool invocations | Strategy 1 |
| `previousRoundSummaryPrompt` | Prompt for summarizing previous round conversations | Strategy 4 |
| `currentRoundLargeMessagePrompt` | Prompt for summarizing current round large messages | Strategy 5 |
| `currentRoundCompressPrompt` | Prompt for compressing current round messages | Strategy 6 |

#### Usage Examples

`customPrompt` is optional. You can omit it (uses default prompts), or set any subset of prompts (unset prompts will use default values).

```java
import io.agentscope.core.memory.autocontext.PromptConfig;

// Option 1: Don't set customPrompt, use default prompts (backward compatible)
AutoContextConfig config1 = AutoContextConfig.builder()
    .msgThreshold(50)
    .maxToken(64 * 1024)
    .build();

// Option 2: Set only some prompts, others will use default values
PromptConfig customPrompt2 = PromptConfig.builder()
    .previousRoundToolCompressPrompt("Custom Strategy 1 prompt...")
    // Other prompts not set, will use default values
    .build();
AutoContextConfig config2 = AutoContextConfig.builder()
    .msgThreshold(50)
    .customPrompt(customPrompt2)
    .build();

// Option 3: Set all prompts
PromptConfig customPrompt3 = PromptConfig.builder()
    .previousRoundToolCompressPrompt("Custom Strategy 1 prompt...")
    .previousRoundSummaryPrompt("Custom Strategy 4 prompt...")
    .currentRoundLargeMessagePrompt("Custom Strategy 5 prompt...")
    .currentRoundCompressPrompt("Custom Strategy 6 prompt...")
    .build();
AutoContextConfig config3 = AutoContextConfig.builder()
    .msgThreshold(50)
    .customPrompt(customPrompt3)
    .build();
```

**Domain-Specific Prompt Examples**

**E-commerce Order Processing Scenario (Specific Tool Invocation Interface Example)**

```java
// Custom prompt for e-commerce order processing scenario
// Assuming the system has the following tools: get_order_info, update_order_status, calculate_price, send_notification
PromptConfig ecommerceCustomPrompt = PromptConfig.builder()
    .previousRoundToolCompressPrompt(
        "You are an e-commerce order processing assistant. Please compress the following tool invocation history according to these rules:\n" +
        "\n" +
        "【Information to Preserve】\n" +
        "1. get_order_info tool calls: Preserve order number, order status, key product information (product ID, name, quantity, price)\n" +
        "2. update_order_status tool calls: Preserve order number, status changes (from X to Y), change timestamp\n" +
        "3. calculate_price tool calls: Preserve final calculated total price, discount amount, shipping fee\n" +
        "4. send_notification tool calls: Preserve notification type (SMS/email), recipient, notification content summary\n" +
        "\n" +
        "【Information to Discard】\n" +
        "1. Detailed product descriptions, image URLs, and other non-critical information\n" +
        "2. Repeated order information query results (only keep the key results from the last query)\n" +
        "3. Detailed steps of intermediate calculations (only keep final results)\n" +
        "4. Detailed logs and response content of notification sending (only keep sending status)\n" +
        "\n" +
        "Please merge multiple tool calls into a concise summary, highlighting key decision points and final status of order processing."
    )
    .currentRoundCompressPrompt(
        "Current round contains order processing related tool invocations. When compressing, follow these principles:\n" +
        "1. Preserve all key information about order status changes (order number, status transitions)\n" +
        "2. Preserve price calculation results (total price, discount, actual payment amount)\n" +
        "3. Preserve key information about notification sending (notification type, sending status)\n" +
        "4. You can simplify detailed parameters of tool calls, but preserve core business data\n" +
        "5. Merge multiple operations on the same order, only keep final status and key intermediate states"
    )
    .build();

AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(50)
    .customPrompt(ecommerceCustomPrompt)
    .build();
```

## API Reference

### AutoContextMemory

#### Main Methods

- `void addMessage(Msg message)`: Add message to working storage and original storage
- `List<Msg> getMessages()`: Get message list from working memory
- `boolean compressIfNeeded()`: Compress working memory if thresholds are reached (returns true if compression was performed)
- `void deleteMessage(int index)`: Delete message at specified index from working storage
- `void clear()`: Clear all storages
- `List<Msg> getOriginalMemoryMsgs()`: Get complete message history from original memory storage
- `List<Msg> getInteractionMsgs()`: Get user-assistant interaction messages (filter tool calls)
- `Map<String, List<Msg>> getOffloadContext()`: Get offload context mapping
- `List<CompressionEvent> getCompressionEvents()`: Get list of all compression event records
- `void attachPlanNote(PlanNotebook planNotebook)`: Attach PlanNotebook to enable plan-aware compression

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

### AutoContextHook

A `Hook` that handles both `PreCallEvent` and `PreReasoningEvent` to set up and manage `AutoContextMemory` integration with `ReActAgent`:

**PreCallEvent handling (executes once per agent, thread-safe):**
- Automatically registers `ContextOffloadTool` to the agent's toolkit
- Automatically attaches the agent's `PlanNotebook` to `AutoContextMemory` for plan-aware compression (if PlanNotebook is enabled)

**PreReasoningEvent handling (executes before each LLM reasoning call):**
- Triggers memory compression via `compressIfNeeded()` if thresholds are reached
- Updates input messages in the event to reflect compressed working memory
- Ensures compression happens at a deterministic point (before reasoning) and LLM receives compressed context

**Required**: Must be used when using `AutoContextMemory` with `ReActAgent` to ensure proper integration and automatic compression triggering.

Usage:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .enablePlan()  // Optional, but recommended
    .hook(new AutoContextHook())  // Required: Automatic setup
    .build();
```

## How It Works

### Compression Trigger Conditions

Compression is triggered automatically by `AutoContextHook` via `PreReasoningEvent` (before each LLM reasoning call) when the following conditions are met:

1. **Message Count Threshold**: `currentMessages.size() >= msgThreshold`
2. **Token Count Threshold**: `calculateToken(currentMessages) >= maxToken * tokenRatio`

Compression triggers when either condition is met. The trigger point is deterministic: always before LLM reasoning, ensuring compressed context is used for reasoning.

### Compression Flow

1. `AutoContextHook` intercepts `PreReasoningEvent` before LLM reasoning
2. Calls `compressIfNeeded()` to check if compression threshold is reached
3. If threshold is reached, tries 6 compression strategies in order
4. Updates `PreReasoningEvent` input messages to reflect compressed working memory
5. LLM reasoning proceeds with compressed context
6. If all strategies fail to meet requirements, log warning and continue with current working storage

### Message Protection Mechanism

- **lastKeep Protection**: Last N messages will not be compressed
- **Latest Assistant Response Protection**: Latest final assistant response and all messages after it will not be compressed
- **Current Round Protection**: Messages in current round (after latest user message) preferentially use lighter compression strategies

## Prompt System

AutoContextMemory uses predefined prompts to guide LLM compression and summarization. Prompts are organized according to the progressive order of compression strategies:

### Strategy 1: Previous Round Tool Invocation Compression
- `PREVIOUS_ROUND_TOOL_INVOCATION_COMPRESS_PROMPT`: Previous round tool invocation compression prompt
  - Used for independently compressing tool invocations from previous rounds
  - Preserve tool names, parameters, and key results
  - Use minimal compression for plan-related tools
- `COMPRESSION_MESSAGE_LIST_END`: Generic scope marker indicating the message list above needs to be compressed

### Strategy 4: Historical Conversation Summarization
- `PREVIOUS_ROUND_CONVERSATION_SUMMARY_PROMPT`: Historical conversation round summarization prompt
  - Preserve key decisions and information
  - Summarize user-assistant conversation pairs

### Strategy 5: Current Round Large Message Summarization
- `CURRENT_ROUND_LARGE_MESSAGE_SUMMARY_PROMPT`: Current round large message summarization prompt
  - Generate summaries for individual large messages
  - Preserve key information

### Strategy 6: Current Round Message Compression
- `CURRENT_ROUND_MESSAGE_COMPRESS_PROMPT`: Current round message compression prompt
  - Support configurable compression ratio (`currentRoundCompressionRatio`)
  - Explicitly specify target character count
  - Provide concise summaries for plan-related tool calls
  - Emphasize low compression rate, preserve task-related information

All prompts are designed to preserve key information while reducing token usage. Strategy 6 prompts are specially optimized with explicit target character counts and strict compression requirements to ensure compression results meet expectations.

### PlanNotebook-Aware Compression

When `PlanNotebook` is attached to `AutoContextMemory`, the compression process becomes plan-aware:

- **Plan Context Integration**: During compression, the system automatically includes the current plan's state (plan name, description, subtasks, and their states) as a hint message
- **Intelligent Information Retention**: The compression prompts are enhanced with plan context, guiding the LLM to preserve information relevant to the current plan and active subtasks
- **Strategic Compression**: Information related to in-progress or pending subtasks is given higher priority during compression, ensuring critical plan-related context is retained

The plan-aware hint message is automatically inserted into the compression prompt (before the final instruction) to leverage the model's attention mechanism, ensuring the compression guidelines are fresh in the model's context during generation.

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

1. **Use AutoContextHook**: When using `AutoContextMemory` with `ReActAgent`, it's recommended to use `AutoContextHook` to automatically handle integration setup, ensuring `ContextOffloadTool` and `PlanNotebook` are properly configured.
2. **Set Thresholds Appropriately**: Adjust `maxToken` and `tokenRatio` based on model context window size and actual usage scenarios
3. **Protect Important Messages**: Use `lastKeep` to ensure recent conversations are not compressed
4. **Enable PlanNotebook Integration**: When using `ReActAgent` with plans, enable `PlanNotebook` support (`.enablePlan()`) to benefit from plan-aware compression
5. **Monitor Compression Logs**: Pay attention to log output to understand compression strategy application
6. **Choose Appropriate Model**: Model used for compression should have good summarization capabilities

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

