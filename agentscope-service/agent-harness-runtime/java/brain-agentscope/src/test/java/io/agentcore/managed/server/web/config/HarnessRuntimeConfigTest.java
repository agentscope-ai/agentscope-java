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
package io.agentcore.managed.server.web.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.runtime.managed.RemoteWorkspaceStore;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HarnessRuntimeConfigTest {
    @Test
    void brainUsesSessionServiceForPrivateWorkspace() {
        var mapper = new ObjectMapper();
        var client =
                new SessionServiceClient(
                        URI.create("http://localhost:8084/internal/session-service/v0"),
                        "test",
                        mapper,
                        Duration.ofSeconds(1));
        assertInstanceOf(
                RemoteWorkspaceStore.class, new HarnessRuntimeConfig().baseStore(client, mapper));
    }
}
