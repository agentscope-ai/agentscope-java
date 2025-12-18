# AutoContextMemory

AutoContextMemory is an intelligent context memory management system that automatically compresses, offloads, and summarizes conversation history to optimize LLM context window usage.

## Overview

AutoContextMemory implements the `Memory` interface and provides automated context management. When conversation history exceeds configured thresholds, the system automatically applies multiple compression strategies to reduce context size while preserving important information as much as possible.

## Key Features

- **Automatic Compression**: Automatically triggers compression when message count or token count exceeds thresholds
- **Multi-Strategy Compression**: Employs 5 progressive compression strategies, from lightweight to heavyweight
- **Intelligent Summarization**: Uses LLM models to intelligently summarize historical conversations
- **Content Offloading**: Offloads large content to external storage, reducing memory usage
- **Tool Call Preservation**: Preserves tool call interface information (name, parameters) during compression
- **Dual Storage Mechanism**: Working storage (compressed) and original storage (complete history)

## How It Works

### Storage Architecture

AutoContextMemory uses a dual storage mechanism, internally implemented with `ArrayList<Msg>`:

1. **Working Memory Storage**: Uses `ArrayList<Msg>` to store compressed messages for actual conversations
2. **Original Memory Storage**: Uses `ArrayList<Msg>` to store complete, uncompressed message history (append-only mode)
3. **Offload Context Storage**: Uses `Map<String, List<Msg>>` to store offloaded message content, keyed by UUID
4. **State Persistence**: All three storages support state serialization and deserialization through `StateModuleBase`

### Compression Strategies

The system applies 5 compression strategies in the following order:

#### Strategy 1: Compress Historical Tool Invocations
- Finds consecutive tool invocation messages in historical conversations (more than `minConsecutiveToolMessages`, default: 6)
- Uses LLM to compress these tool invocations while preserving key information
- Special handling for plan note related tools: uses minimal compression, only keeping a brief description
- Replaces original tool invocations with compressed content

#### Strategy 2: Offload Large Messages (Keep Last N)
- Finds large messages before the latest assistant message
- Offloads messages exceeding the threshold to external storage
- Replaces original messages with preview and offload hints

#### Strategy 3: Offload Large Messages (No Keep)
- If Strategy 2 doesn't take effect, tries a more aggressive offloading strategy
- Offloads all messages exceeding the threshold

#### Strategy 4: Summarize Historical Rounds
- Finds all conversation rounds before the latest assistant message
- Summarizes each round (user + tools + assistant)
- Replaces original conversation rounds with summary messages

#### Strategy 5: Compress Current Round
- If historical messages are already compressed but context still exceeds limits, compresses the current round
- Finds the latest user message and merges all messages after it (typically tool calls and results)
- Preserves tool call interfaces and compresses tool results

## Configuration

### AutoContextConfig

```java
// Create configuration using Builder pattern
AutoContextConfig config = AutoContextConfig.builder()
    // Message count threshold: compression triggered when exceeded
    .msgThreshold(100)
    // Token threshold: compression triggered when exceeded
    .maxToken(128 * 1024)
    // Token ratio: actual trigger threshold is maxToken * tokenRatio
    .tokenRatio(0.75)
    // Keep last N messages uncompressed
    .lastKeep(50)
    // Minimum consecutive tool messages for compression (default: 6)
    .minConsecutiveToolMessages(6)
    // Large message threshold (in characters)
    .largePayloadThreshold(5 * 1024)
    // Offload preview length
    .offloadSinglePreview(200)
    .build();
```

### Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|----------|-------------|
| `msgThreshold` | int | 100 | Message count threshold |
| `maxToken` | long | 128 * 1024 | Maximum token count |
| `tokenRatio` | double | 0.75 | Token trigger ratio |
| `lastKeep` | int | 50 | Number of recent messages to keep uncompressed |
| `minConsecutiveToolMessages` | int | 6 | Minimum consecutive tool messages required for compression |
| `largePayloadThreshold` | long | 5 * 1024 | Large message threshold (characters) |
| `offloadSinglePreview` | int | 200 | Offload preview length |

## Usage Examples

### Basic Usage

```java
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.DashScopeChatModel;

// Create model
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey("your-api-key")
    .modelName("qwen-plus")
    .build();

// Configure AutoContextMemory (using Builder pattern)
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)
    .lastKeep(10)
    .build();

// Create AutoContextMemory
AutoContextMemory memory = new AutoContextMemory(config, model);

// Use in Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();
```

### Complete Example

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.tool.Toolkit;

// Configuration (using Builder pattern)
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)
    .lastKeep(10)
    .build();

// Create memory
AutoContextMemory memory = new AutoContextMemory(config, model);

// Register context reload tool (optional)
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

## Storage Implementations

### Internal Storage Mechanism

AutoContextMemory internally uses `ArrayList<Msg>` and `HashMap` as the storage implementation:
- **Working Memory**: Uses `ArrayList<Msg>` to store compressed messages for actual conversations
- **Original Memory**: Uses `ArrayList<Msg>` to store complete, uncompressed message history (append-only mode)
- **Offload Context**: Uses `Map<String, List<Msg>>` to store offloaded message content, keyed by UUID
- **State Persistence**: All three storages support state serialization and deserialization through `StateModuleBase`, allowing session state to be saved and restored

### ContextOffLoader

AutoContextMemory implements the `ContextOffLoader` interface with built-in context offloading functionality:
- Offloaded messages are stored in the internal `offloadContext` Map
- Each offloaded context has a unique UUID identifier
- Offloaded content can be reloaded via the `ContextOffloadTool`

## API Documentation

### Main Methods

#### `addMessage(Msg message)`
Adds a message to memory. The message is added to both working storage and original storage.

#### `getMessages()`
Gets the message list. If thresholds are exceeded, compression strategies are automatically triggered.

#### `deleteMessage(int index)`
Deletes the message at the specified index.

#### `clear()`
Clears all messages.

## Compression Strategy Details

### Strategy 1: Compress Tool Invocations

When more than `minConsecutiveToolMessages` (default: 6) consecutive tool invocation messages are detected in historical conversations:
1. Extract these tool invocation messages
2. Use LLM for intelligent compression
   - **Special handling for plan note tools**: Plan-related tools (create_plan, revise_current_plan, etc.) are compressed minimally, only keeping a brief description indicating that plan-related tool calls were made
3. Optionally offload original content to external storage
4. Replace original messages with compressed messages

### Strategy 2 & 3: Offload Large Messages

When message content exceeds `largePayloadThreshold`:
1. Find large messages (before the latest assistant)
2. Offload original content to external storage
3. Replace original messages with preview and offload hints
4. Users can reload content via `ContextOffloadTool`

### Strategy 4: Summarize Historical Rounds

For all conversation rounds before the latest assistant:
1. Identify each user-assistant pair
2. Summarize each round of conversation (including tool calls and assistant responses)
3. Replace original rounds with summary messages
4. Preserve offload UUID for subsequent reloading

### Strategy 5: Compress Current Round

When history is already compressed but context still exceeds limits:
1. Find the latest user message
2. Merge all messages after it (tool calls and results)
3. Preserve tool call interface information
4. Compress tool results while preserving key information

## Best Practices

1. **Set Reasonable Thresholds**: Adjust `msgThreshold` and `maxToken` according to your application scenario
2. **Register Reload Tool**: Register `ContextOffloadTool` so agents can reload offloaded content (AutoContextMemory implements ContextOffLoader interface and can be used directly)
3. **Monitor Logs**: Pay attention to compression strategy triggers and optimize configuration parameters
4. **Preserve Important Information**: Compression strategies preserve key information as much as possible, but it's recommended to manually save before important conversations
5. **State Persistence**: Utilize the state persistence functionality of `StateModuleBase` to save and restore session state

## Notes

- Compression operations use LLM models, which may incur additional API call costs
- Compressed messages may lose some details but will preserve key information
- Original messages are always stored in `originalMemoryStorage` (original storage) and will never be compressed or modified
- Messages in working storage may be compressed, but original storage always maintains complete history
- Offloaded content is stored in the internal `offloadContext` Map and can be reloaded via `ContextOffloadTool`
- Supports state persistence, allowing session state to be saved and restored through `StateModuleBase` (including working storage, original storage, and offload context)

## Dependencies

AutoContextMemory requires the following dependencies:

- `io.agentscope:agentscope-core`
- An LLM model implementing the `Model` interface (for compression and summarization)

## License

Apache License 2.0
