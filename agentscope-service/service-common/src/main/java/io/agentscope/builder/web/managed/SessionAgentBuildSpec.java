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
package io.agentscope.builder.web.managed;

import java.util.List;
import java.util.Map;

/**
 * Runtime build parameters for a managed-session {@link io.agentscope.harness.agent.HarnessAgent}.
 *
 * <p>Cache key is {@code (owner, agent, version, environmentId)}; overrides / memory / vaults /
 * resources are applied at build time so distinct mount sets get distinct agent instances.
 */
public record SessionAgentBuildSpec(
        Integer version,
        String environmentId,
        EnvironmentDto environment,
        String overridesJson,
        List<String> memoryStoreIds,
        List<String> vaultIds,
        List<Map<String, Object>> resources) {

    public SessionAgentBuildSpec(
            Integer version,
            String environmentId,
            EnvironmentDto environment,
            String overridesJson,
            List<String> memoryStoreIds,
            List<String> vaultIds) {
        this(version, environmentId, environment, overridesJson, memoryStoreIds, vaultIds, null);
    }

    /** Builds a cache/gateway suffix from the stable identity fields. */
    public String cacheSuffix() {
        String ver = version == null ? "head" : String.valueOf(version);
        String env = environmentId == null || environmentId.isBlank() ? "default" : environmentId;
        String mem =
                memoryStoreIds == null || memoryStoreIds.isEmpty()
                        ? "-"
                        : String.join(",", memoryStoreIds);
        String vault = vaultIds == null || vaultIds.isEmpty() ? "-" : String.join(",", vaultIds);
        int ovrHash = overridesJson == null ? 0 : overridesJson.hashCode();
        int resHash = resources == null ? 0 : resources.hashCode();
        return "v"
                + ver
                + "/e"
                + env
                + "/m"
                + mem.hashCode()
                + "/vt"
                + vault.hashCode()
                + "/o"
                + ovrHash
                + "/r"
                + resHash;
    }
}
