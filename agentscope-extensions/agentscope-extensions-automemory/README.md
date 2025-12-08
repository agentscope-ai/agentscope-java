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

AutoContextMemory uses a dual storage mechanism:

1. **Working Memory Storage**: Stores compressed messages for actual conversations
2. **Original Memory Storage**: Stores complete, uncompressed message history

### Compression Strategies

The system applies 5 compression strategies in the following order:

#### Strategy 1: Compress Historical Tool Invocations
- Finds consecutive tool invocation messages in historical conversations (more than 4)
- Uses LLM to compress these tool invocations while preserving key information
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
AutoContextConfig config = new AutoContextConfig();

// Message count threshold: compression triggered when exceeded
config.setMsgThreshold(100);

// Token threshold: compression triggered when exceeded
config.setMaxToken(128 * 1024);

// Token ratio: actual trigger threshold is maxToken * tokenRatio
config.setTokenRatio(0.75);

// Keep last N messages uncompressed
config.setLastKeep(100);

// Large message threshold (in characters)
config.setLargePayloadThreshold(5 * 1024);

// Offload preview length
config.setOffloadSinglePreview(200);

// Context offloader (optional)
config.setContextOffLoader(new LocalFileContextOffLoader("/path/to/storage"));

// Working storage (optional, defaults to InMemoryStorage)
config.setContextStorage(new InMemoryStorage(sessionId));

// History storage (optional, defaults to InMemoryStorage)
config.setHistoryStorage(new InMemoryStorage(sessionId));
```

### Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|----------|-------------|
| `msgThreshold` | int | 100 | Message count threshold |
| `maxToken` | long | 128 * 1024 | Maximum token count |
| `tokenRatio` | double | 0.75 | Token trigger ratio |
| `lastKeep` | int | 100 | Number of recent messages to keep uncompressed |
| `largePayloadThreshold` | long | 5 * 1024 | Large message threshold (characters) |
| `offloadSinglePreview` | int | 200 | Offload preview length |
| `contextOffLoader` | ContextOffLoader | null | Context offloader |
| `contextStorage` | MemoryStorage | null | Working storage |
| `historyStorage` | MemoryStorage | null | History storage |

## Usage Examples

### Basic Usage

```java
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.LocalFileContextOffLoader;
import io.agentscope.core.model.DashScopeChatModel;

// Create model
DashScopeChatModel model = DashScopeChatModel.builder()
    .apiKey("your-api-key")
    .modelName("qwen-plus")
    .build();

// Configure AutoContextMemory
AutoContextConfig config = new AutoContextConfig();
config.setMsgThreshold(30);
config.setLastKeep(10);
config.setContextOffLoader(new LocalFileContextOffLoader("/tmp/context"));

// Create AutoContextMemory
String sessionId = UUID.randomUUID().toString();
Memory memory = new AutoContextMemory(config, sessionId, model);

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
import io.agentscope.core.memory.autocontext.LocalFileContextOffLoader;
import io.agentscope.core.tool.Toolkit;

// Configuration
AutoContextConfig config = new AutoContextConfig();
config.setContextOffLoader(new LocalFileContextOffLoader("/tmp/context"));
config.setMsgThreshold(30);
config.setLastKeep(10);

// Create memory
String sessionId = UUID.randomUUID().toString();
Memory memory = new AutoContextMemory(config, sessionId, model);

// Register context reload tool (optional)
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ContextOffloadTool(config.getContextOffLoader()));

// Create Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .toolkit(toolkit)
    .build();
```

## Storage Implementations

### MemoryStorage

AutoContextMemory supports custom storage implementations:

- **InMemoryStorage**: In-memory storage (default)
- **FileSysMemoryStorage**: File system storage

### ContextOffLoader

Supports custom context offloaders:

- **InMemoryContextOffLoader**: In-memory offloader (default)
- **LocalFileContextOffLoader**: Local file offloader

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

When more than 4 consecutive tool invocation messages are detected in historical conversations:
1. Extract these tool invocation messages
2. Use LLM for intelligent compression
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
2. **Use External Storage**: For production environments, it's recommended to use `LocalFileContextOffLoader` or custom offloaders
3. **Register Reload Tool**: Register `ContextOffloadTool` so agents can reload offloaded content
4. **Monitor Logs**: Pay attention to compression strategy triggers and optimize configuration parameters
5. **Preserve Important Information**: Compression strategies preserve key information as much as possible, but it's recommended to manually save before important conversations

## Notes

- Compression operations use LLM models, which may incur additional API call costs
- Compressed messages may lose some details but will preserve key information
- Original messages are always stored in `originalMemoryStorage`
- Offloaded content can be reloaded via `ContextOffloadTool`

## Dependencies

AutoContextMemory requires the following dependencies:

- `io.agentscope:agentscope-core`
- An LLM model implementing the `Model` interface (for compression and summarization)

## License

Apache License 2.0
