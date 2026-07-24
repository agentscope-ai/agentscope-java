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
package io.agentscope.extensions.sandbox.kubernetes.client.config;

import io.agentscope.extensions.sandbox.kubernetes.client.Constants;

/** Configuration for connecting directly to a known sandbox-router URL. */
public final class DirectConnectionConfig implements SandboxConnectionConfig {

    private final String apiUrl;
    private final int serverPort;

    public DirectConnectionConfig(String apiUrl) {
        this(apiUrl, Constants.DEFAULT_SERVER_PORT);
    }

    public DirectConnectionConfig(String apiUrl, int serverPort) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("API URL must not be blank");
        }
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.serverPort = serverPort > 0 ? serverPort : Constants.DEFAULT_SERVER_PORT;
    }

    public String apiUrl() {
        return apiUrl;
    }

    @Override
    public int serverPort() {
        return serverPort;
    }
}
