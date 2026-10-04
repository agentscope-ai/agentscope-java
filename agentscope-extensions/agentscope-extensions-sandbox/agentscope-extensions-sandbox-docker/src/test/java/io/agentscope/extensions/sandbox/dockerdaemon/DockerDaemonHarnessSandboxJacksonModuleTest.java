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
package io.agentscope.extensions.sandbox.dockerdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerDaemonHarnessSandboxJacksonModuleTest {

    @Test
    void roundTripsDockerDaemonSandboxState() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new DockerDaemonHarnessSandboxJacksonModule());

        DockerDaemonSandboxState original = new DockerDaemonSandboxState();
        original.setSessionId("sess-1");
        original.setWorkspaceRootReady(true);
        original.setContainerId("abc123");
        original.setDaemonUrl("tcp://127.0.0.1:2375");
        original.setMounts(
                List.of(new MountSpec("bind", "/host/artifacts", "/app/artifacts", false)));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"docker-daemon\""), json);

        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertInstanceOf(DockerDaemonSandboxState.class, parsed);
        assertEquals("sess-1", parsed.getSessionId());
        assertEquals(true, parsed.isWorkspaceRootReady());
        assertEquals("abc123", ((DockerDaemonSandboxState) parsed).getContainerId());
        assertEquals("tcp://127.0.0.1:2375", ((DockerDaemonSandboxState) parsed).getDaemonUrl());
        assertEquals(
                List.of(new MountSpec("bind", "/host/artifacts", "/app/artifacts", false)),
                ((DockerDaemonSandboxState) parsed).getMounts());
    }

    @Test
    void deserializesStateWithoutMountsField() throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new DockerDaemonHarnessSandboxJacksonModule());

        // state persisted before the mounts feature existed — no "mounts" key at all
        String json =
                "{\"type\":\"docker-daemon\",\"sessionId\":\"old-sess\",\"containerId\":\"c-old\"}";

        SandboxState parsed = mapper.readValue(json, SandboxState.class);

        assertInstanceOf(DockerDaemonSandboxState.class, parsed);
        assertEquals("old-sess", parsed.getSessionId());
        assertEquals("c-old", ((DockerDaemonSandboxState) parsed).getContainerId());
        assertTrue(((DockerDaemonSandboxState) parsed).getMounts().isEmpty());
    }
}
