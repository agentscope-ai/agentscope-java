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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSandboxStateSerializationTest {
    @Test
    void roundTripsWorkspaceSandboxState(@TempDir Path workspace) throws Exception {
        SandboxState original = new WorkspaceSandbox("workspace-session", workspace).getState();
        ObjectMapper mapper = new ObjectMapper().registerModule(new HarnessSandboxJacksonModule());
        mapper.registerSubtypes(new NamedType(original.getClass(), "workspace"));

        SandboxState parsed =
                mapper.readValue(mapper.writeValueAsString(original), SandboxState.class);

        assertEquals(original.getClass(), parsed.getClass());
        assertEquals("workspace-session", parsed.getSessionId());
        assertNull(parsed.getWorkspaceRoot());
    }
}
