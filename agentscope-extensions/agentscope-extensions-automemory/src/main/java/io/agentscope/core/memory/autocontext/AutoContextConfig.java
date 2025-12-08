package io.agentscope.core.memory.autocontext;

import lombok.Data;

/**
 * Configuration for AutoContextMemory.
 */
@Data
public class AutoContextConfig {

    /** Working memory storage for compressed messages. */
    MemoryStorage contextStorage;

    /** Original memory storage for complete, uncompressed message history. */
    MemoryStorage historyStorage;

    /** Context offloader for storing large content externally. */
    ContextOffLoader contextOffLoader;

    /** Threshold (in characters) for large payload messages to be offloaded. */
    long largePayloadThreshold = 5 * 1024;

    /** Maximum token limit for context window. */
    long maxToken = 128 * 1024;

    /** Token ratio threshold (0.0-1.0) to trigger compression. */
    double tokenRatio = 0.75;

    /** Preview length (in characters) for offloaded messages. */
    int offloadSinglePreview = 200;

    /** Message count threshold to trigger compression. */
    int msgThreshold = 100;

    /** Number of recent messages to keep uncompressed. */
    int lastKeep = 50;

    /** Minimum number of consecutive tool messages required for compression. */
    int minConsecutiveToolMessages = 4;

    /** Session identifier. */
    String sessionId;
}
