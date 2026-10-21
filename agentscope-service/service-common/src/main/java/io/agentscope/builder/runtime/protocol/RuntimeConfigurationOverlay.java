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
package io.agentscope.builder.runtime.protocol;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resource configuration for the next activation; Agent and Workspace definitions stay frozen. */
public record RuntimeConfigurationOverlay(
        String scopeId, long revision, Map<String, Object> configuration) {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final Set<String> FIELDS =
            Set.of("environment", "vaultCredentials", "memoryMounts");

    public RuntimeConfigurationOverlay {
        if (scopeId == null || !scopeId.matches("[0-9a-f]{64}") || revision < 1)
            throw new IllegalArgumentException("Invalid configuration overlay identity");
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.keySet().equals(FIELDS)
                || configuration.get("environment") != null
                        && !(configuration.get("environment") instanceof Map<?, ?>)
                || !(configuration.get("vaultCredentials") instanceof List<?>)
                || !(configuration.get("memoryMounts") instanceof List<?>))
            throw new IllegalArgumentException("Invalid configuration overlay fields");
        configuration = RuntimeSessionBootstrap.freeze(configuration, 0);
    }
}
