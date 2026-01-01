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
package io.agentscope.core.studio;

import java.time.Duration;
import java.util.UUID;

/**
 * Configuration for AgentScope Studio integration.
 *
 * <p>This class holds all configuration parameters needed to connect to and communicate with
 * AgentScope Studio, including HTTP and WebSocket settings.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * StudioConfig config = StudioConfig.builder()
 *     .studioUrl("http://localhost:3000")
 *     .project("MyProject")
 *     .runName("experiment_001")
 *     .maxRetries(5)
 *     .build();
 * }</pre>
 *
 * @see StudioManager
 * @see StudioClient
 */
public class StudioConfig {
    private final String studioUrl;
    private final String tracingUrl;
    private final String project;
    private final String runName;
    private final String runId;
    private final int maxRetries;
    private final int reconnectAttempts;
    private final Duration reconnectDelay;
    private final Duration reconnectMaxDelay;

    private StudioConfig(Builder builder) {
        this.studioUrl = builder.studioUrl;
        this.tracingUrl = builder.tracingUrl;
        this.project = builder.project;
        this.runName = builder.runName;
        this.runId = builder.runId;
        this.maxRetries = builder.maxRetries;
        this.reconnectAttempts = builder.reconnectAttempts;
        this.reconnectDelay = builder.reconnectDelay;
        this.reconnectMaxDelay = builder.reconnectMaxDelay;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the Studio server base URL.
     *
     * @return the Studio URL (default: "http://localhost:3000")
     */
    public String getStudioUrl() {
        return studioUrl;
    }

    /**
     * Gets the tracing endpoint URL for pushing messages.
     *
     * @return the tracing URL (default: studioUrl + "/v1/traces")
     */
    public String getTracingUrl() {
        return tracingUrl;
    }

    /**
     * Gets the project name for this run.
     *
     * @return the project name (default: "UnnamedProject")
     */
    public String getProject() {
        return project;
    }

    /**
     * Gets the human-readable name for this run.
     *
     * @return the run name (default: "run_" + timestamp)
     */
    public String getRunName() {
        return runName;
    }

    /**
     * Gets the unique identifier for this run.
     *
     * @return the run ID (default: random UUID)
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Gets the maximum number of HTTP request retries.
     *
     * @return the max retries (default: 3)
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Gets the maximum number of WebSocket reconnection attempts.
     *
     * @return the reconnect attempts (default: 3)
     */
    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    /**
     * Gets the initial delay between reconnection attempts.
     *
     * @return the reconnect delay (default: 1 second)
     */
    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    /**
     * Gets the maximum delay between reconnection attempts.
     *
     * @return the maximum reconnect delay (default: 5 seconds)
     */
    public Duration getReconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    public static class Builder {
        private String studioUrl = "http://localhost:3000";
        private String tracingUrl;
        private String project = "UnnamedProject";
        private String runName = "run_" + System.currentTimeMillis();
        private String runId = UUID.randomUUID().toString();
        private int maxRetries = 3;
        private int reconnectAttempts = 3;
        private Duration reconnectDelay = Duration.ofSeconds(1);
        private Duration reconnectMaxDelay = Duration.ofSeconds(5);

        public Builder studioUrl(String studioUrl) {
            this.studioUrl = studioUrl;
            return this;
        }

        public Builder tracingUrl(String tracingUrl) {
            this.tracingUrl = tracingUrl;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder runName(String runName) {
            this.runName = runName;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder reconnectAttempts(int reconnectAttempts) {
            this.reconnectAttempts = reconnectAttempts;
            return this;
        }

        public Builder reconnectDelay(Duration reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
            return this;
        }

        public Builder reconnectMaxDelay(Duration reconnectMaxDelay) {
            this.reconnectMaxDelay = reconnectMaxDelay;
            return this;
        }

        public StudioConfig build() {
            if (tracingUrl == null) {
                tracingUrl = studioUrl + "/v1/traces";
            }
            return new StudioConfig(this);
        }
    }
}
