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
package io.agentscope.harness.agent.sandbox.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import org.junit.jupiter.api.Test;

class HarnessSandboxJacksonModuleTest {

    @Test
    void roundTripsDockerSandboxState() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());

        DockerSandboxState original = new DockerSandboxState();
        original.setSessionId("sess-1");
        original.setWorkspaceRootReady(true);

        String json = mapper.writeValueAsString(original);
        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertInstanceOf(DockerSandboxState.class, parsed);
        assertEquals("sess-1", parsed.getSessionId());
        assertEquals(true, parsed.isWorkspaceRootReady());
    }
}
