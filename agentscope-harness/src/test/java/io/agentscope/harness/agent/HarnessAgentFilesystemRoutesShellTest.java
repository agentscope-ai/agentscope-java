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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import io.agentscope.harness.agent.tool.ShellExecuteTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * The workspace capability probes must see through the routing wrappers the builder adds: a
 * routed workspace that can execute commands must still report a shell.
 */
class HarnessAgentFilesystemRoutesShellTest {

    @TempDir Path workspace;

    @Test
    void withFilesystemRoutes_keepsTheSandboxShell() throws Exception {
        SandboxBackedFilesystem sandbox = new SandboxBackedFilesystem();
        Path store = Files.createDirectories(workspace.resolve("stores"));

        AbstractFilesystem routed =
                HarnessAgent.withFilesystemRoutes(
                        sandbox, Map.of("memory-stores", new LocalFilesystem(store)));

        RoutedSandboxFilesystem wrapper = assertInstanceOf(RoutedSandboxFilesystem.class, routed);
        assertSame(sandbox, wrapper.primary());
        assertInstanceOf(AbstractSandboxFilesystem.class, routed);
    }

    @Test
    void withFilesystemRoutes_usesCompositeForOtherPrimaries() throws Exception {
        Path store = Files.createDirectories(workspace.resolve("stores"));
        AbstractFilesystem primary = new LocalFilesystem(workspace);

        AbstractFilesystem routed =
                HarnessAgent.withFilesystemRoutes(
                        primary, Map.of("memory-stores", new LocalFilesystem(store)));

        assertInstanceOf(CompositeFilesystem.class, routed);
    }

    @Test
    void withFilesystemRoutes_keepsHostShellOverlaysUnwrapped() throws Exception {
        Path store = Files.createDirectories(workspace.resolve("stores"));
        AbstractFilesystem overlay =
                OverlayFilesystem.of(
                        new LocalFilesystemWithShell(workspace), new LocalFilesystem(store));

        AbstractFilesystem routed =
                HarnessAgent.withFilesystemRoutes(
                        overlay, Map.of("memory-stores", new LocalFilesystem(store)));

        // Host shell behind a sandbox marker: routing it would read host paths as /workspace ones.
        assertInstanceOf(CompositeFilesystem.class, routed);
        assertEquals(
                ShellPathPolicy.Mode.LOCAL_WITH_SHELL,
                HarnessAgent.skillShellPolicy(routed, null, workspace).mode());
    }

    @Test
    void filesystemRoutes_keepTheShellToolRegistered() throws Exception {
        Files.createDirectories(workspace);
        Path store = Files.createDirectories(workspace.resolve("stores"));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystemWithShell(workspace))
                        .filesystemRoute("memory-stores", new LocalFilesystem(store))
                        .build();

        assertTrue(
                toolNames(agent).contains(ShellExecuteTool.NAME),
                "a routed workspace over a shell-capable primary must keep its shell tool,"
                        + " otherwise every skill silently loses code execution");
    }

    @Test
    void filesystemRoutes_keepTheShellToolRegisteredForSandboxPrimary() throws Exception {
        Path store = Files.createDirectories(workspace.resolve("stores"));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel())
                        .workspace(workspace)
                        .abstractFilesystem(new SandboxBackedFilesystem())
                        .filesystemRoute("memory-stores", new LocalFilesystem(store))
                        .build();

        assertTrue(toolNames(agent).contains(ShellExecuteTool.NAME));
    }

    @Test
    void filesystemRoutes_keepTheLocalShellSkillPolicy() throws Exception {
        Files.createDirectories(workspace);
        Path store = Files.createDirectories(workspace.resolve("stores"));
        AbstractFilesystem routed =
                HarnessAgent.withFilesystemRoutes(
                        new LocalFilesystemWithShell(workspace),
                        Map.of("memory-stores", new LocalFilesystem(store)));

        assertEquals(
                ShellPathPolicy.Mode.LOCAL_WITH_SHELL,
                HarnessAgent.skillShellPolicy(routed, null, workspace).mode());
    }

    @Test
    void skillShellPolicy_looksThroughRoutingWrappers() throws Exception {
        Path store = Files.createDirectories(workspace.resolve("stores"));
        Map<String, AbstractFilesystem> routes =
                Map.of("memory-stores", new LocalFilesystem(store));

        assertEquals(
                ShellPathPolicy.Mode.SANDBOX,
                HarnessAgent.skillShellPolicy(
                                new CompositeFilesystem(new SandboxBackedFilesystem(), routes),
                                null,
                                workspace)
                        .mode());
        assertEquals(
                ShellPathPolicy.Mode.SANDBOX,
                HarnessAgent.skillShellPolicy(
                                new RoutedSandboxFilesystem(new SandboxBackedFilesystem(), routes),
                                null,
                                workspace)
                        .mode());
        assertEquals(
                ShellPathPolicy.Mode.LOCAL_WITH_SHELL,
                HarnessAgent.skillShellPolicy(
                                new CompositeFilesystem(
                                        new LocalFilesystemWithShell(workspace), routes),
                                null,
                                workspace)
                        .mode());
        assertEquals(
                ShellPathPolicy.Mode.NO_SHELL,
                HarnessAgent.skillShellPolicy(
                                new CompositeFilesystem(new LocalFilesystem(workspace), routes),
                                null,
                                workspace)
                        .mode());
    }

    @Test
    void skillShellPolicy_matchesUnwrappedFilesystems() throws Exception {
        Files.createDirectories(workspace);

        // Wrappers add transparency only; they must not change the decision.
        assertEquals(
                ShellPathPolicy.Mode.LOCAL_WITH_SHELL,
                HarnessAgent.skillShellPolicy(
                                new LocalFilesystemWithShell(workspace), null, workspace)
                        .mode());
        assertEquals(
                ShellPathPolicy.Mode.SANDBOX,
                HarnessAgent.skillShellPolicy(new SandboxBackedFilesystem(), null, workspace)
                        .mode());
        assertEquals(
                ShellPathPolicy.Mode.NO_SHELL,
                HarnessAgent.skillShellPolicy(new LocalFilesystem(workspace), null, workspace)
                        .mode());
    }

    private static List<String> toolNames(HarnessAgent agent) {
        return agent.getDelegate().getToolkit().getToolSchemas().stream()
                .map(schema -> schema.getName())
                .toList();
    }

    private static Model stubModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "stub-id",
                                        List.of(TextBlock.builder().text("ok").build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        return model;
    }
}
