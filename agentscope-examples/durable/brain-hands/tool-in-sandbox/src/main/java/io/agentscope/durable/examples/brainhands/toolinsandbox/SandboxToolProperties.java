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
package io.agentscope.durable.examples.brainhands.toolinsandbox;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate process identity and storage, with a shared RocketMQ deployment namespace. */
@ConfigurationProperties("durable.brain-hands-tool-in-sandbox")
public class SandboxToolProperties {
    private String endpoint = "localhost:8081";
    private Path dataDirectory;
    private String namespace = "demo";
    private String sessionId = "conversation";
    private String requestId = "request-1";
    private Duration timeout = Duration.ofSeconds(60);
    private String mqNamespace = "durable-sandbox";

    public void validate() {
        if (blank(endpoint)
                || blank(namespace)
                || blank(sessionId)
                || blank(requestId)
                || blank(mqNamespace)
                || !mqNamespace.matches("[A-Za-z0-9_-]+")
                || timeout == null
                || timeout.toMillis() < 1)
            throw new IllegalArgumentException(
                    "Complete RocketMQ and session configuration is required");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** RocketMQ 5 gRPC proxy endpoint, not the NameServer address. */
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

    public String getMqNamespace() {
        return mqNamespace;
    }

    public void setMqNamespace(String mqNamespace) {
        this.mqNamespace = mqNamespace;
    }
}
