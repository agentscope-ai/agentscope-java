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
package io.agentscope.extensions.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Options for the OpenSandbox-backed {@link SandboxClient}. */
public class OpenSandboxClientOptions extends SandboxClientOptions {

    private String endpoint = "http://localhost:8080";
    private String apiKey;
    private String image = "ubuntu:22.04";
    private List<String> entrypoint = List.of("tail", "-f", "/dev/null");
    private Map<String, String> resourceLimits = Map.of("cpu", "1", "memory", "2Gi");
    private int sandboxTimeoutSeconds = 600;
    private int readyTimeoutSeconds = 30;
    private int requestTimeoutSeconds = 30;
    private boolean useServerProxy;
    private boolean endpointSet;
    private boolean apiKeySet;
    private boolean imageSet;
    private boolean entrypointSet;
    private boolean resourceLimitsSet;
    private boolean sandboxTimeoutSecondsSet;
    private boolean readyTimeoutSecondsSet;
    private boolean requestTimeoutSecondsSet;
    private boolean useServerProxySet;

    @Override
    public String getType() {
        return "opensandbox";
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new OpenSandboxClient(this, null);
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        OpenSandboxEndpoint.parse(endpoint);
        this.endpoint = endpoint.trim();
        endpointSet = true;
    }

    public String getApiKey() {
        return apiKey;
    }

    /**
     * Sets the API key. A {@code null} value is treated as unset when call-level options are
     * merged with client defaults.
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        apiKeySet = apiKey != null;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        this.image = image;
        imageSet = true;
    }

    public List<String> getEntrypoint() {
        return List.copyOf(entrypoint);
    }

    public void setEntrypoint(List<String> entrypoint) {
        if (entrypoint == null
                || entrypoint.isEmpty()
                || entrypoint.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("entrypoint must contain at least one command");
        }
        this.entrypoint = List.copyOf(entrypoint);
        entrypointSet = true;
    }

    public Map<String, String> getResourceLimits() {
        return Map.copyOf(resourceLimits);
    }

    public void setResourceLimits(Map<String, String> resourceLimits) {
        if (resourceLimits == null) {
            throw new IllegalArgumentException("resourceLimits must not be null");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        resourceLimits.forEach(
                (key, value) -> {
                    if (key == null || key.isBlank() || value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                                "resourceLimits must contain nonblank entries");
                    }
                    copy.put(key, value);
                });
        this.resourceLimits = Map.copyOf(copy);
        resourceLimitsSet = true;
    }

    public int getSandboxTimeoutSeconds() {
        return sandboxTimeoutSeconds;
    }

    public void setSandboxTimeoutSeconds(int seconds) {
        requirePositive(seconds, "sandboxTimeoutSeconds");
        this.sandboxTimeoutSeconds = seconds;
        sandboxTimeoutSecondsSet = true;
    }

    public int getReadyTimeoutSeconds() {
        return readyTimeoutSeconds;
    }

    public void setReadyTimeoutSeconds(int seconds) {
        requirePositive(seconds, "readyTimeoutSeconds");
        this.readyTimeoutSeconds = seconds;
        readyTimeoutSecondsSet = true;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int seconds) {
        requirePositive(seconds, "requestTimeoutSeconds");
        this.requestTimeoutSeconds = seconds;
        requestTimeoutSecondsSet = true;
    }

    public boolean isUseServerProxy() {
        return useServerProxy;
    }

    public void setUseServerProxy(boolean useServerProxy) {
        this.useServerProxy = useServerProxy;
        useServerProxySet = true;
    }

    boolean isEndpointSet() {
        return endpointSet;
    }

    boolean isApiKeySet() {
        return apiKeySet;
    }

    boolean isImageSet() {
        return imageSet;
    }

    boolean isEntrypointSet() {
        return entrypointSet;
    }

    boolean isResourceLimitsSet() {
        return resourceLimitsSet;
    }

    boolean isSandboxTimeoutSecondsSet() {
        return sandboxTimeoutSecondsSet;
    }

    boolean isReadyTimeoutSecondsSet() {
        return readyTimeoutSecondsSet;
    }

    boolean isRequestTimeoutSecondsSet() {
        return requestTimeoutSecondsSet;
    }

    boolean isUseServerProxySet() {
        return useServerProxySet;
    }

    static OpenSandboxClientOptions copyOf(OpenSandboxClientOptions source) {
        OpenSandboxClientOptions copy = new OpenSandboxClientOptions();
        copy.endpoint = source.endpoint;
        copy.apiKey = source.apiKey;
        copy.image = source.image;
        copy.entrypoint = source.entrypoint;
        copy.resourceLimits = source.resourceLimits;
        copy.sandboxTimeoutSeconds = source.sandboxTimeoutSeconds;
        copy.readyTimeoutSeconds = source.readyTimeoutSeconds;
        copy.requestTimeoutSeconds = source.requestTimeoutSeconds;
        copy.useServerProxy = source.useServerProxy;
        copy.endpointSet = source.endpointSet;
        copy.apiKeySet = source.apiKeySet;
        copy.imageSet = source.imageSet;
        copy.entrypointSet = source.entrypointSet;
        copy.resourceLimitsSet = source.resourceLimitsSet;
        copy.sandboxTimeoutSecondsSet = source.sandboxTimeoutSecondsSet;
        copy.readyTimeoutSecondsSet = source.readyTimeoutSecondsSet;
        copy.requestTimeoutSecondsSet = source.requestTimeoutSecondsSet;
        copy.useServerProxySet = source.useServerProxySet;
        return copy;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
