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
package io.agentscope.extensions.sandbox.dockerdaemon;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

/**
 * Builds docker-java {@link DockerClient} instances for a {@link DockerDaemonSandboxState}.
 *
 * <p>The client talks to the daemon over its Engine HTTP API via the zerodep transport
 * ({@code docker-java-transport-zerodep}), so no docker CLI or local socket is required.
 */
public final class DockerDaemonClientFactory {

    /** Fixed Engine API version to avoid daemon version drift. */
    static final String API_VERSION = "1.45";

    private DockerDaemonClientFactory() {}

    /**
     * Resolves the Docker daemon URL to use: explicit option wins, otherwise the
     * {@code DOCKER_HOST} environment variable; {@code null} when neither is set (docker-java
     * default host behavior).
     *
     * @param optionValue value from {@link DockerDaemonSandboxClientOptions#getDaemonUrl()}
     * @param envValue    value of the {@code DOCKER_HOST} environment variable
     * @return normalized daemon URL or {@code null}
     */
    static String resolveDaemonUrl(String optionValue, String envValue) {
        String normalized = normalize(optionValue);
        if (normalized != null) {
            return normalized;
        }
        return normalize(envValue);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the docker-java client configuration for the given state.
     *
     * <p>{@code DOCKER_TLS_VERIFY} / {@code DOCKER_CERT_PATH} environment variables are read by
     * {@link DefaultDockerClientConfig#createDefaultConfigBuilder()} itself, matching the CLI
     * version's env-inheritance behavior for {@code https://} daemon URLs.
     *
     * @param state sandbox state carrying the frozen daemon URL
     * @return docker-java client config
     */
    static DefaultDockerClientConfig buildConfig(DockerDaemonSandboxState state) {
        DefaultDockerClientConfig.Builder builder =
                DefaultDockerClientConfig.createDefaultConfigBuilder();
        if (state.getDaemonUrl() != null && !state.getDaemonUrl().isBlank()) {
            builder.withDockerHost(state.getDaemonUrl());
        }
        builder.withApiVersion(API_VERSION);
        return builder.build();
    }

    /**
     * Builds a {@link DockerClient} for the given state. No network I/O happens at
     * construction time; the connection is established lazily on the first command.
     *
     * @param state sandbox state
     * @return a new client instance
     */
    public static DockerClient buildClient(DockerDaemonSandboxState state) {
        DefaultDockerClientConfig config = buildConfig(state);
        DockerHttpClient httpClient =
                new ZerodepDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
