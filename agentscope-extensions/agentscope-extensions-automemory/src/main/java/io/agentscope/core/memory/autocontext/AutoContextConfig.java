package io.agentscope.core.memory.autocontext;

import lombok.Data;

@Data
public class AutoContextConfig {

    MemoryStorage contextStorage;

    MemoryStorage historyStorage;

    ContextOffLoader contextOffLoader;

    long largePayloadThreshold = 5 * 1024;

    long maxToken = 128 * 1024;

    double tokenRatio = 0.75;

    int offloadSinglePreview = 200;

    int msgThreshold = 100;

    int lastKeep = 50;

    String sessionId;
}
