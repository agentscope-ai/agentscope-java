/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.memory.autocontext;

/**
 * Configuration class for AutoContextMemory.
 *
 * <p>This class contains all configurable parameters for the AutoContextMemory system,
 * including storage backends, compression thresholds, and offloading settings.
 *
 * <p><b>Key Configuration Areas:</b>
 * <ul>
 *   <li><b>Storage:</b> Working storage and original history storage backends</li>
 *   <li><b>Compression Triggers:</b> Message count and token count thresholds</li>
 *   <li><b>Offloading:</b> Large payload thresholds and preview lengths</li>
 *   <li><b>Protection:</b> Number of recent messages to keep uncompressed</li>
 * </ul>
 *
 * <p>All fields have default values and can be customized via setters or builder pattern.
 */
public class AutoContextConfig {

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
    int minConsecutiveToolMessages = 6;

    public ContextOffLoader getContextOffLoader() {
        return contextOffLoader;
    }

    public void setContextOffLoader(ContextOffLoader contextOffLoader) {
        this.contextOffLoader = contextOffLoader;
    }

    public long getLargePayloadThreshold() {
        return largePayloadThreshold;
    }

    public void setLargePayloadThreshold(long largePayloadThreshold) {
        this.largePayloadThreshold = largePayloadThreshold;
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

    public int getOffloadSinglePreview() {
        return offloadSinglePreview;
    }

    public void setOffloadSinglePreview(int offloadSinglePreview) {
        this.offloadSinglePreview = offloadSinglePreview;
    }

    public int getMsgThreshold() {
        return msgThreshold;
    }

    public void setMsgThreshold(int msgThreshold) {
        this.msgThreshold = msgThreshold;
    }

    public int getLastKeep() {
        return lastKeep;
    }

    public void setLastKeep(int lastKeep) {
        this.lastKeep = lastKeep;
    }

    public int getMinConsecutiveToolMessages() {
        return minConsecutiveToolMessages;
    }

    public void setMinConsecutiveToolMessages(int minConsecutiveToolMessages) {
        this.minConsecutiveToolMessages = minConsecutiveToolMessages;
    }
}
