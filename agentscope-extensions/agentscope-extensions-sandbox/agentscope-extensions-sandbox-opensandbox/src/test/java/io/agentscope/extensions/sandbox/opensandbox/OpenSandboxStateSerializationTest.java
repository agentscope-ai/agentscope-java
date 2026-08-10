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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenSandboxStateSerializationTest {

    @Test
    void roundTripPreservesRecreationFieldsWithoutCredentials() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .registerModule(new OpenSandboxHarnessSandboxJacksonModule());
        OpenSandboxState state = new OpenSandboxState();
        state.setSessionId("session-1");
        state.setSandboxId("sandbox-1");
        state.setSandboxOwned(true);
        state.setImage("ubuntu:24.04");
        state.setEntrypoint(List.of("tail", "-f", "/dev/null"));
        state.setResourceLimits(Map.of("cpu", "2", "memory", "4Gi"));
        state.setSandboxTimeoutSeconds(900);
        WorkspaceSpec workspace = new WorkspaceSpec();
        workspace.setRoot("/workspace");
        state.setWorkspaceSpec(workspace);

        String json = mapper.writeValueAsString(state);
        SandboxState decoded = mapper.readValue(json, SandboxState.class);

        OpenSandboxState restored = assertInstanceOf(OpenSandboxState.class, decoded);
        assertEquals("sandbox-1", restored.getSandboxId());
        assertEquals("ubuntu:24.04", restored.getImage());
        assertEquals(List.of("tail", "-f", "/dev/null"), restored.getEntrypoint());
        assertEquals(Map.of("cpu", "2", "memory", "4Gi"), restored.getResourceLimits());
        assertEquals(900, restored.getSandboxTimeoutSeconds());
        assertTrue(restored.isSandboxOwned());
        assertTrue(json.contains("\"type\":\"opensandbox\""));
        assertFalse(json.toLowerCase().contains("apikey"));
    }
}
