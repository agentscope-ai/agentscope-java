/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.durable.examples.queuedhands;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External configuration for the Queued Hands example. */
@ConfigurationProperties("durable.queued-hands")
public class QueuedHandsExampleProperties {
    /** Process role: the Brain submits a Turn, the Worker executes Hands commands. */
    public enum Role {
        BRAIN,
        WORKER
    }

    private Role role = Role.BRAIN;
    private String endpoint = "localhost:8081";
    private Path dataDirectory = Path.of("durable-queued-hands-data");
    private String namespace = "demo";
    private String sessionId = "conversation";
    private String requestId = "request-1";
    private Duration timeout = Duration.ofSeconds(15);

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    /** RocketMQ 5 gRPC proxy endpoint. */
    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
