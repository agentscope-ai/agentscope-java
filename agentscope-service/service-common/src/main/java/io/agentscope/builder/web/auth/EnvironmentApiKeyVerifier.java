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
package io.agentscope.builder.web.auth;

/**
 * Verifies an Environment Worker API key against control-plane state. Implementations must not
 * read control-plane tables from the data plane; call the CP internal API instead.
 */
@FunctionalInterface
public interface EnvironmentApiKeyVerifier {

    /**
     * @param environmentId environment id from the request path
     * @param plaintextKey value of {@code X-Builder-Environment-Key}
     * @return true when the key is valid for the environment
     */
    boolean matches(String environmentId, String plaintextKey);
}
