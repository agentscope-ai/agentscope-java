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
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.builder.web.managed.ManagedSessionDto;
import org.junit.jupiter.api.Test;

class HarnessAgentBuildServiceSessionLogAgentIdTest {

    private static ManagedSessionDto session(String ownerId) {
        return new ManagedSessionDto(
                "sess_01", ownerId, "agent-1", ownerId, 1, "latest", null, "env-1", null, null,
                null, null, "idle", null, 0L, 0L, null);
    }

    @Test
    void namespaceScopedOwnerIdProducesAValidAgentId() {
        String agentId =
                HarnessAgentBuildService.sessionLogAgentId(session("namespace:default:default"));

        assertThat(agentId).isEqualTo("dpa-namespace_default_default-agent-1-sess_01");
        assertThat(agentId).matches("[A-Za-z0-9._-]+");
    }

    @Test
    void personalOwnerIdIsNotRewritten() {
        assertThat(HarnessAgentBuildService.sessionLogAgentId(session("user-1")))
                .isEqualTo("dpa-user-1-agent-1-sess_01");
    }
}
