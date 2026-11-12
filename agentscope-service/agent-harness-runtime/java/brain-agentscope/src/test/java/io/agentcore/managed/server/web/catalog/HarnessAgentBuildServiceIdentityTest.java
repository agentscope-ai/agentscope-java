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
package io.agentcore.managed.server.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import io.agentscope.core.agent.AgentBase;
import org.junit.jupiter.api.Test;

class HarnessAgentBuildServiceIdentityTest {

    @Test
    void namespaceOwnedSessionHasStableIdentityAcceptedByHarness() {
        var session = session("namespace:default:default", "agent", "session");
        String id = HarnessAgentBuildService.sessionLogAgentId(session);

        assertThat(AgentBase.normalizeAgentId(id)).isEqualTo(id);
        assertThat(id).isEqualTo(HarnessAgentBuildService.sessionLogAgentId(session));
        assertThat(id)
                .isNotEqualTo(identity("namespace:default:another", "agent", "session"))
                .isNotEqualTo(identity("namespace:default:default", "another", "session"))
                .isNotEqualTo(identity("namespace:default:default", "agent", "another"));
    }

    @Test
    void identityKeepsTupleBoundariesAndSupportsUnicodeAndLongOwners() {
        assertThat(identity("namespace:default", "agent-a", "session"))
                .isNotEqualTo(identity("namespace:default-agent", "a", "session"));
        String id = identity("租户/" + "a".repeat(300), "agent", "session");
        assertThat(AgentBase.normalizeAgentId(id)).isEqualTo(id);
        assertThat(id).hasSize(68);
    }

    @Test
    void everyOwnerUsesTheSameUnambiguousTupleIdentity() {
        assertThat(identity("owner-1", "agent-1", "session-1"))
                .hasSize(68)
                .isNotEqualTo(identity("owner", "1-agent-1", "session-1"));
    }

    private static String identity(String owner, String agent, String session) {
        return HarnessAgentBuildService.sessionLogAgentId(session(owner, agent, session));
    }

    private static ManagedSessionDto session(String owner, String agent, String session) {
        return new ManagedSessionDto(
                session, owner, agent, owner, 1, "latest", null, null, null, null, null, null,
                "idle", null, 0L, 0L, null);
    }
}
