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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentVersionSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The control plane ({@code aistiod}) serializes the agent snapshot with envelope fields the
     * record does not model; deserialization must tolerate them.
     */
    @Test
    void parsesControlPlaneSnapshotEnvelope() throws Exception {
        String json =
                """
                {
                  "id": "ag_cecc0395e056",
                  "name": "demo",
                  "description": "d",
                  "system": "you are demo",
                  "model": "qwen-max",
                  "maxIters": 20,
                  "tools": [],
                  "mcpServers": [],
                  "skills": [],
                  "multiagent": null,
                  "scope": "user",
                  "ownerId": "u_1",
                  "workspacePath": "/data/ws",
                  "workspaceId": null,
                  "defaultEnvironmentId": null,
                  "defaultVaultIds": [],
                  "defaultMemoryStoreIds": [],
                  "version": 1,
                  "createdAt": 1754180000000,
                  "updatedAt": 1754180000000
                }
                """;

        AgentVersionSnapshot snapshot = mapper.readValue(json, AgentVersionSnapshot.class);

        assertThat(snapshot.name()).isEqualTo("demo");
        assertThat(snapshot.model()).isEqualTo("qwen-max");
        assertThat(snapshot.maxIters()).isEqualTo(20);
        assertThat(snapshot.tools()).isEmpty();
    }
}
