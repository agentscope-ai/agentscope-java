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
package io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy;

/**
 * Strategy for discovering the HTTP base URL used to reach a sandbox.
 * Mirrors the Python SDK's {@code ConnectionStrategy}.
 */
public interface ConnectionStrategy {

    /**
     * Discover and return the base URL.
     *
     * @return the base URL (e.g. {@code http://127.0.0.1:12345})
     * @throws Exception if discovery fails
     */
    String connect() throws Exception;

    /** Release any resources held by this strategy (e.g. port-forward tunnels). */
    default void close() {
        // no-op by default
    }

    /**
     * Whether {@code X-Sandbox-*} router headers should be injected into requests.
     *
     * @return true for router-based strategies; false for in-cluster direct
     */
    default boolean shouldInjectRouterHeaders() {
        return true;
    }
}
