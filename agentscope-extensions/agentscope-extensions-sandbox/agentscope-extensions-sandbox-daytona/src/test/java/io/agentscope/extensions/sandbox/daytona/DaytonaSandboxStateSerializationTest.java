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
package io.agentscope.extensions.sandbox.daytona;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import org.junit.jupiter.api.Test;

class DaytonaSandboxStateSerializationTest {
    @Test
    void roundTripsConfiguredWorkspaceRoot() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new HarnessSandboxJacksonModule())
                        .registerModule(new DaytonaHarnessSandboxJacksonModule());
        DaytonaSandboxState original = new DaytonaSandboxState();
        original.setWorkspaceRoot("/custom/daytona-root");

        String json = mapper.writeValueAsString(original);
        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertEquals("/custom/daytona-root", mapper.readTree(json).get("workspaceRoot").asText());
        assertEquals("/custom/daytona-root", parsed.getWorkspaceRoot());
    }
}
