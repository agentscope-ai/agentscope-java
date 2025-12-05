package io.agentscope.core.memory.autocontext;

public class AutoContextConfig {

    MemoryStorage contextStorage;

    MemoryStorage historyStorage;

    ContextOffLoader contextOffLoader;

    long largePayloadThreshold = 5 * 1024;

    long maxToken = 128 * 1024;

    double tokenRatio = 0.75;

    int offloadSignlePreview = 200;

    int msgThreshold = 100;

    int lastKeep = 50;

    int sessionId;

    public int getLastKeep() {
        return lastKeep;
    }

    public void setLastKeep(int lastKeep) {
        this.lastKeep = lastKeep;
    }

    public MemoryStorage getContextStorage() {
        return contextStorage;
    }

    public void setContextStorage(MemoryStorage contextStorage) {
        this.contextStorage = contextStorage;
    }

    public MemoryStorage getHistoryStorage() {
        return historyStorage;
    }

    public void setHistoryStorage(MemoryStorage historyStorage) {
        this.historyStorage = historyStorage;
    }

    public ContextOffLoader getContextOffLoader() {
        return contextOffLoader;
    }

    public void setContextOffLoader(ContextOffLoader contextOffLoader) {
        this.contextOffLoader = contextOffLoader;
    }

    public long getMaxToken() {
        return maxToken;
    }

    public void setMaxToken(long maxToken) {
        this.maxToken = maxToken;
    }

    public double getTokenRatio() {
        return tokenRatio;
    }

    public void setTokenRatio(double tokenRatio) {
        this.tokenRatio = tokenRatio;
    }

    public int getMsgThreshold() {
        return msgThreshold;
    }

    public void setMsgThreshold(int msgThreshold) {
        this.msgThreshold = msgThreshold;
    }

    public int getSessionId() {
        return sessionId;
    }

    public void setSessionId(int sessionId) {
        this.sessionId = sessionId;
    }
}
