/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the AgentScope task HTTP protocol endpoints. */
@ConfigurationProperties(prefix = "agentscope.agent-protocol")
public class AgentProtocolProperties {

    /** When {@code true}, registers {@code /tasks} REST endpoints. */
    private boolean enabled = false;

    /** When {@code true}, servers push AgentEvents over SSE {@code /tasks/{id}/events}. */
    private boolean streamingEnabled = true;

    /** When {@code true}, pause remote tasks for tool confirmation (HITL). */
    private boolean hitlEnabled = true;

    /** Replay buffer size per task for late SSE subscribers. */
    private int sseReplayBufferSize = 256;

    /** Client idle timeout hint for SSE (ms); not enforced server-side by default. */
    private long sseTimeoutMs = 10_800_000L;

    /**
     * Dedicated control-plane directory for protocol task-record persistence. Independent of any
     * execution agent's workspace. When null/blank, defaults to {@code
     * ${user.dir}/.agentscope/agent-protocol}.
     */
    private String taskStorePath;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    public boolean isHitlEnabled() {
        return hitlEnabled;
    }

    public void setHitlEnabled(boolean hitlEnabled) {
        this.hitlEnabled = hitlEnabled;
    }

    public int getSseReplayBufferSize() {
        return sseReplayBufferSize;
    }

    public void setSseReplayBufferSize(int sseReplayBufferSize) {
        this.sseReplayBufferSize = sseReplayBufferSize;
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    /**
     * Resolved control-plane path. Never blank after this getter — falls back to {@code
     * ${user.dir}/.agentscope/agent-protocol}.
     */
    public String getTaskStorePath() {
        if (taskStorePath == null || taskStorePath.isBlank()) {
            return Path.of(System.getProperty("user.dir"), ".agentscope", "agent-protocol")
                    .toString();
        }
        return taskStorePath;
    }

    public void setTaskStorePath(String taskStorePath) {
        this.taskStorePath = taskStorePath;
    }
}
