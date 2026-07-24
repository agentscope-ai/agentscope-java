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
package io.agentscope.extensions.sandbox.kubernetes.client;

import java.util.Collections;
import java.util.Map;

/**
 * Options for {@link SandboxClient#createSandbox(CreateSandboxOptions)}.
 *
 * <p>Corresponds to the keyword arguments of the Python SDK's {@code create_sandbox}.
 */
public final class CreateSandboxOptions {

    private final String warmPool;
    private final String namespace;
    private final String claimName;
    private final long sandboxReadyTimeoutSeconds;
    private final Long shutdownAfterSeconds;
    private final Map<String, String> labels;
    private final Map<String, String> podLabels;
    private final Map<String, String> podAnnotations;

    private CreateSandboxOptions(Builder builder) {
        if (builder.warmPool == null || builder.warmPool.isBlank()) {
            throw new IllegalArgumentException("Warmpool name cannot be empty");
        }
        this.warmPool = builder.warmPool;
        this.namespace = builder.namespace != null ? builder.namespace : "default";
        this.claimName = builder.claimName;
        this.sandboxReadyTimeoutSeconds =
                builder.sandboxReadyTimeoutSeconds > 0 ? builder.sandboxReadyTimeoutSeconds : 180;
        this.shutdownAfterSeconds = builder.shutdownAfterSeconds;
        this.labels =
                builder.labels != null
                        ? Collections.unmodifiableMap(builder.labels)
                        : Collections.emptyMap();
        this.podLabels =
                builder.podLabels != null
                        ? Collections.unmodifiableMap(builder.podLabels)
                        : Collections.emptyMap();
        this.podAnnotations =
                builder.podAnnotations != null
                        ? Collections.unmodifiableMap(builder.podAnnotations)
                        : Collections.emptyMap();
    }

    public static Builder builder(String warmPool) {
        return new Builder(warmPool);
    }

    public String warmPool() {
        return warmPool;
    }

    public String namespace() {
        return namespace;
    }

    /**
     * Optional explicit claim name. When null, a generated {@code sandbox-claim-*} name is used.
     *
     * @return claim name or null
     */
    public String claimName() {
        return claimName;
    }

    public long sandboxReadyTimeoutSeconds() {
        return sandboxReadyTimeoutSeconds;
    }

    public Long shutdownAfterSeconds() {
        return shutdownAfterSeconds;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public Map<String, String> podLabels() {
        return podLabels;
    }

    public Map<String, String> podAnnotations() {
        return podAnnotations;
    }

    /** Builder for {@link CreateSandboxOptions}. */
    public static final class Builder {
        private final String warmPool;
        private String namespace = "default";
        private String claimName;
        private long sandboxReadyTimeoutSeconds = 180;
        private Long shutdownAfterSeconds;
        private Map<String, String> labels;
        private Map<String, String> podLabels;
        private Map<String, String> podAnnotations;

        private Builder(String warmPool) {
            this.warmPool = warmPool;
        }

        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets an explicit SandboxClaim name (used by the harness adapter to encode session id).
         *
         * @param claimName claim resource name
         * @return this builder
         */
        public Builder claimName(String claimName) {
            this.claimName = claimName;
            return this;
        }

        public Builder sandboxReadyTimeoutSeconds(long sandboxReadyTimeoutSeconds) {
            this.sandboxReadyTimeoutSeconds = sandboxReadyTimeoutSeconds;
            return this;
        }

        public Builder shutdownAfterSeconds(Long shutdownAfterSeconds) {
            this.shutdownAfterSeconds = shutdownAfterSeconds;
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            this.labels = labels;
            return this;
        }

        public Builder podLabels(Map<String, String> podLabels) {
            this.podLabels = podLabels;
            return this;
        }

        public Builder podAnnotations(Map<String, String> podAnnotations) {
            this.podAnnotations = podAnnotations;
            return this;
        }

        public CreateSandboxOptions build() {
            return new CreateSandboxOptions(this);
        }
    }
}
