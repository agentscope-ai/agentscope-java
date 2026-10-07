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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Builder wiring for {@code permissionEscalation(true)}: the flag travels onto the permission
 * context (preserving user-supplied rules/modes), the shell tool's schema advertises the
 * escalation arguments only when enabled, and the default build observes no change at all.
 */
class HarnessAgentPermissionEscalationWiringTest {

    @TempDir Path tmp;

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text("ok").build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private HarnessAgent.Builder baseBuilder() {
        return HarnessAgent.builder()
                .name("escalation-wiring-test")
                .model(stubModel())
                .workspace(tmp)
                .abstractFilesystem(new LocalFilesystemWithShell(tmp));
    }

    @Test
    void defaultBuild_escalationDisabledAndPlainShellSchema() {
        HarnessAgent agent = baseBuilder().build();
        PermissionContextState ctx = agent.getDelegate().getPermissionContext();
        assertNotNull(ctx);
        assertFalse(ctx.isEscalationEnabled());
        AgentTool execute = agent.getToolkit().getTool("execute");
        assertNotNull(execute);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) execute.getParameters().get("properties");
        assertFalse(properties.containsKey("sandbox_permissions"), String.valueOf(properties));
        assertFalse(properties.containsKey("justification"), String.valueOf(properties));
    }

    @Test
    void escalationEnabled_flagTravelsOntoContextAndSchemaAdvertisesArgs() {
        HarnessAgent agent = baseBuilder().permissionEscalation(true).build();
        PermissionContextState ctx = agent.getDelegate().getPermissionContext();
        assertNotNull(ctx);
        assertTrue(ctx.isEscalationEnabled());
        AgentTool execute = agent.getToolkit().getTool("execute");
        assertNotNull(execute);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) execute.getParameters().get("properties");
        assertTrue(properties.containsKey("sandbox_permissions"), String.valueOf(properties));
        assertTrue(properties.containsKey("justification"), String.valueOf(properties));
    }

    @Test
    void builderReuse_trueThenFalse_yieldsEscalationOff() {
        HarnessAgent.Builder builder = baseBuilder();
        HarnessAgent on = builder.permissionEscalation(true).build();
        assertTrue(on.getDelegate().getPermissionContext().isEscalationEnabled());

        // Reusing the SAME builder with the flag turned off: the second agent must have
        // escalation fully off (no engine-on / schema-off split leaked from build #1).
        HarnessAgent off = builder.permissionEscalation(false).build();
        assertFalse(off.getDelegate().getPermissionContext().isEscalationEnabled());
        AgentTool execute = off.getToolkit().getTool("execute");
        assertNotNull(execute);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) execute.getParameters().get("properties");
        assertFalse(properties.containsKey("sandbox_permissions"), String.valueOf(properties));
    }

    @Test
    void userSuppliedContextIsPreservedWhenMergingTheFlag() {
        // Call order reversed: permissionContext first, then the flag — the merge must keep the
        // user's mode and rules.
        PermissionContextState userCtx =
                PermissionContextState.builder()
                        .mode(PermissionMode.EXPLORE)
                        .addAllowRule(
                                "read_file",
                                new io.agentscope.core.permission.PermissionRule(
                                        "read_file",
                                        null,
                                        io.agentscope.core.permission.PermissionBehavior.ALLOW,
                                        "test"))
                        .build();
        HarnessAgent agent =
                baseBuilder().permissionContext(userCtx).permissionEscalation(true).build();
        PermissionContextState ctx = agent.getDelegate().getPermissionContext();
        assertTrue(ctx.isEscalationEnabled());
        assertEquals(PermissionMode.EXPLORE, ctx.getMode());
        assertTrue(ctx.getAllowRules().containsKey("read_file"));
    }
}
