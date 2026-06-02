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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.gateway.HarnessGateway;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.auth.UserStore.UserRecord;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.template.TemplateRegistry;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies the chat-time filesystem user-id resolver — the mechanism that pins shared-agent
 * (SCOPE_USER) chat reads to the owner's filesystem namespace, so the namespace the controller
 * writes into via {@code resolveOwner} matches what the running {@link HarnessAgent} reads from
 * via {@code DynamicSkillHook} / {@code DynamicSubagentsHook}.
 *
 * <p>The resolver is the contract that keeps the {@code skills}/{@code subagents} management UI
 * consistent with chat behaviour across owner and shared-in editor users; see the controller
 * javadocs on {@code AgentSkillsController} and {@code AgentWorkspaceController} for the
 * end-to-end story.
 */
class AgentCatalogServiceFilesystemUserIdTest {

    private static final String OWNER = "alice";
    private static final String VIEWER = "bob";
    private static final String AGENT_ID = "my-bot";

    @Test
    void resolveFilesystemUserId_returnsCallerForGlobalAgents() {
        AgentCatalogService svc =
                newService(Map.of("default", mock(HarnessAgent.class)), List.of(), null);
        // Globals are owned by no single user; per-caller overlays are the right default.
        assertThat(svc.resolveFilesystemUserId(VIEWER, "default")).isEqualTo(VIEWER);
        assertThat(svc.resolveFilesystemUserId(OWNER, "default")).isEqualTo(OWNER);
    }

    @Test
    void resolveFilesystemUserId_returnsCallerForUnknownAgentIds() {
        AgentCatalogService svc = newService(Map.of(), List.of(), null);
        // No registration / no owner inference possible: degrade gracefully to caller's id rather
        // than blow up the chat path.
        assertThat(svc.resolveFilesystemUserId(VIEWER, "uca-someone-else-foo")).isEqualTo(VIEWER);
    }

    @Test
    void resolveFilesystemUserId_returnsOwnerForRegisteredUca() {
        UserAgentDefinitionStore store = mock(UserAgentDefinitionStore.class);
        when(store.findById(OWNER, AGENT_ID))
                .thenReturn(Optional.of(mock(UserAgentDefinitionStore.StoredEntry.class)));
        AgentCatalogService svc =
                newService(
                        Map.of(), List.of(new UserRecord(OWNER, OWNER, "hash", List.of())), store);
        // peek populates gatewayIdToOwner without needing a real HarnessAgent build.
        String gatewayId = svc.peekGatewayAgentId(VIEWER, AGENT_ID);
        assertThat(gatewayId).isEqualTo("uca-" + OWNER + "-" + AGENT_ID);

        // Both the owner and shared-in viewer should resolve to the owner so the chat path reads
        // the same namespace the controller writes to.
        assertThat(svc.resolveFilesystemUserId(VIEWER, gatewayId)).isEqualTo(OWNER);
        assertThat(svc.resolveFilesystemUserId(OWNER, gatewayId)).isEqualTo(OWNER);
    }

    @Test
    void resolveFilesystemUserId_returnsCallerWhenInputsAreBlankOrNull() {
        AgentCatalogService svc = newService(Map.of(), List.of(), null);
        assertThat(svc.resolveFilesystemUserId(null, "uca-x-y")).isNull();
        assertThat(svc.resolveFilesystemUserId("", "uca-x-y")).isEqualTo("");
        assertThat(svc.resolveFilesystemUserId(VIEWER, null)).isEqualTo(VIEWER);
        assertThat(svc.resolveFilesystemUserId(VIEWER, "")).isEqualTo(VIEWER);
    }

    @Test
    void constructor_installsResolverOnGateway() {
        HarnessGateway gateway = mock(HarnessGateway.class);
        BuilderBootstrap bootstrap = mock(BuilderBootstrap.class);
        when(bootstrap.gateway()).thenReturn(gateway);
        when(bootstrap.agents()).thenReturn(Map.of());

        new AgentCatalogService(
                bootstrap,
                mock(UserAgentDefinitionStore.class),
                Optional.empty(),
                mock(ToolEventBus.class),
                mock(TemplateRegistry.class),
                mock(SharedWorkspacePaths.class),
                mock(UserStore.class),
                mock(AgentAclService.class));

        // Verifies the wiring done in the constructor — chat-time RC.userId override is only
        // active when the gateway has a resolver, so failing this verification would silently
        // regress to the pre-fix caller-pinned behaviour described in the analysis writeup.
        verify(gateway).setFilesystemUserIdResolver(any());
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static AgentCatalogService newService(
            Map<String, HarnessAgent> globals,
            List<UserRecord> users,
            UserAgentDefinitionStore storeOverride) {
        HarnessGateway gateway = mock(HarnessGateway.class);
        BuilderBootstrap bootstrap = mock(BuilderBootstrap.class);
        when(bootstrap.gateway()).thenReturn(gateway);
        when(bootstrap.agents()).thenReturn(globals);

        UserStore userStore = mock(UserStore.class);
        when(userStore.listAll()).thenReturn(users);

        UserAgentDefinitionStore store =
                storeOverride != null ? storeOverride : mock(UserAgentDefinitionStore.class);

        return new AgentCatalogService(
                bootstrap,
                store,
                Optional.empty(),
                mock(ToolEventBus.class),
                mock(TemplateRegistry.class),
                mock(SharedWorkspacePaths.class),
                userStore,
                mock(AgentAclService.class));
    }
}
