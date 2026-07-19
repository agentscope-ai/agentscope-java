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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.example.support.InMemorySandboxClient;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for {@link HarnessAgent} workspace wiring: {@code AGENTS.md} context and subagent
 * discovery ({@code subagents/*.md}).
 */
class HarnessAgentTest {

    @TempDir Path workspace;

    @AfterEach
    void cleanupTempDir() {
        if (workspace != null && Files.exists(workspace)) {
            try (var files = Files.walk(workspace)) {
                files.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(workspace))
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    void workspaceAgentsMd_readableViaWorkspaceManager() throws Exception {
        Files.createDirectories(workspace);
        String marker = "persona-marker-unique-agents-md-42";
        Files.writeString(
                workspace.resolve(WorkspaceConstants.AGENTS_MD), "# Test\n" + marker + "\n");

        Model model = stubModel("ok");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        assertTrue(
                agent.getWorkspaceManager().readAgentsMd(RuntimeContext.empty()).contains(marker));
    }

    @Test
    void disableMemoryTools_omitsHarnessMemoryAndSessionTools() throws Exception {
        Files.createDirectories(workspace);
        Model model = stubModel("ok");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableMemoryTools()
                        .build();

        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertFalse(toolNames.contains("memory_search"));
        assertFalse(toolNames.contains("memory_get"));
        assertFalse(toolNames.contains("session_search"));
    }

    @Test
    void disableFilesystemTools_omitsFileTools() throws Exception {
        Files.createDirectories(workspace);
        Model model = stubModel("ok");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableFilesystemTools()
                        .build();

        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertFalse(toolNames.contains("read_file"));
        assertFalse(toolNames.contains("list_files"));
    }

    @Test
    void disableShellTool_omitsExecuteToolWhenShellBackend() throws Exception {
        Files.createDirectories(workspace);
        Model model = stubModel("ok");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .disableShellTool()
                        .build();

        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertFalse(toolNames.contains("execute"));
    }

    @Test
    void disableSubagents_omitsSpawnAndTaskTools() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# w\n");
        Model model = stubModel("ok");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("main")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableSubagents()
                        .build();

        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .toList();
        assertFalse(toolNames.contains("agent_spawn"));
        assertFalse(toolNames.contains("task_output"));
    }

    @Test
    void disableWorkspaceContext_modelStreamDoesNotIncludeAgentsMd() throws Exception {
        Files.createDirectories(workspace);
        String marker = "no-workspace-context-hook-xyz";
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), marker);

        Model model = stubModel("assistant-done");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableWorkspaceContext()
                        .build();

        agent.call(userText("hi"), RuntimeContext.builder().sessionId("s-no-ctx").build()).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String combined =
                captor.getAllValues().stream()
                        .map(HarnessAgentTest::joinAllText)
                        .collect(Collectors.joining("\n"));
        assertFalse(
                combined.contains(marker),
                "AGENTS.md should not be injected when context hook is disabled");
    }

    @Test
    void workspaceAgentsMd_injectedIntoMessagesSeenByModel() throws Exception {
        Files.createDirectories(workspace);
        String marker = "injected-via-workspace-context-99";
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), marker);

        Model model = stubModel("assistant-done");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        agent.call(userText("hi"), RuntimeContext.builder().sessionId("s1").build()).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String combined =
                captor.getAllValues().stream()
                        .map(HarnessAgentTest::joinAllText)
                        .filter(s -> s.contains("<agents_context>"))
                        .findFirst()
                        .orElse("");
        assertTrue(
                combined.contains("<agents_context>"),
                "expected workspace hook to wrap AGENTS.md in agents_context");
        assertTrue(
                combined.contains(marker), "model should see AGENTS.md body in injected context");
    }

    @Test
    void runtimeContextConcreteFilesystemSurvivesSessionDefaulting() throws Exception {
        Files.createDirectories(workspace);

        Model model = stubModel("assistant-done");
        LocalFilesystem agentFilesystem = new LocalFilesystem(workspace);
        LocalFilesystem customFilesystem = new LocalFilesystem(workspace);
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(agentFilesystem)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                                        seen.set(ctx);
                                        return Mono.just(currentPrompt);
                                    }
                                })
                        .build()) {
            agent.call(
                            userText("hi"),
                            RuntimeContext.builder()
                                    .put(LocalFilesystem.class, customFilesystem)
                                    .build())
                    .block();
        }

        RuntimeContext effective = seen.get();
        assertNotNull(effective);
        assertNotNull(effective.getSessionId());
        assertSame(customFilesystem, effective.get(AbstractFilesystem.class));
        assertSame(customFilesystem, effective.get(LocalFilesystem.class));
    }

    @Test
    void runtimeContextNullInputGetsDefaultSessionAndSandbox() throws Exception {
        Files.createDirectories(workspace);

        Model model = stubModel("assistant-done");
        LocalFilesystem agentFilesystem = new LocalFilesystem(workspace);
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(agentFilesystem)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                                        seen.set(ctx);
                                        return Mono.just(currentPrompt);
                                    }
                                })
                        .build()) {
            agent.call(userText("hi"), (RuntimeContext) null).block();
        }

        RuntimeContext effective = seen.get();
        assertNotNull(effective);
        assertEquals("t", effective.getSessionId());
        assertSame(agentFilesystem, effective.get(AbstractFilesystem.class));
        assertNull(effective.get(SandboxContext.class));
        assertNull(effective.getUserId());
    }

    @Test
    void runtimeContextAlreadyDefaultedReturnsSourceWhenNothingChanges() throws Exception {
        Files.createDirectories(workspace);

        Model model = stubModel("assistant-done");
        LocalFilesystem agentFilesystem = new LocalFilesystem(workspace);
        SandboxContext sandboxContext =
                SandboxContext.builder().isolationScope(IsolationScope.USER).build();
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();

        RuntimeContext source =
                RuntimeContext.builder()
                        .sessionId("t")
                        .put(SandboxContext.class, sandboxContext)
                        .put(AbstractFilesystem.class, agentFilesystem)
                        .build();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(agentFilesystem)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                                        seen.set(ctx);
                                        return Mono.just(currentPrompt);
                                    }
                                })
                        .build()) {
            agent.call(userText("hi"), source).block();
        }

        assertSame(source, seen.get());
    }

    @Test
    void runtimeContextSandboxSpecProvidesDefaultSandboxContext() throws Exception {
        Files.createDirectories(workspace);

        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec();
        spec.isolationScope(IsolationScope.SESSION);
        InMemorySandboxClient client = spec.getClient();
        client.resetCounts();
        Model model = stubModel("assistant-done");
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        String sessionId = "sandbox-" + UUID.randomUUID();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace.toAbsolutePath().normalize().toString())
                        .filesystem(spec)
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Mono<String> onSystemPrompt(
                                            Agent agent, RuntimeContext ctx, String currentPrompt) {
                                        seen.set(ctx);
                                        return Mono.just(currentPrompt);
                                    }
                                })
                        .build()) {
            agent.call(userText("hi"), RuntimeContext.builder().sessionId(sessionId).build())
                    .block();
        }

        RuntimeContext effective = seen.get();
        assertNotNull(effective);
        assertNotNull(effective.get(SandboxContext.class));
        assertEquals(1, client.getCreateCount());
    }

    @Test
    void subagentMarkdown_registersIdsAndSubagentTools() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# workspace\n");
        // The filename (without .md) becomes the subagent name
        String specId = "from-md";
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        Files.writeString(
                subagents.resolve(specId + ".md"),
                """
                ---
                description: From subagents/*.md for tests
                ---
                You only reply OK.
                """);

        Model model = stubModel("done");
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("main")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<String> toolNames =
                agent.getDelegate().getToolkit().getToolSchemas().stream()
                        .map(ToolSchema::getName)
                        .collect(Collectors.toList());
        assertTrue(
                toolNames.contains("agent_spawn"), "subagent support should register agent_spawn");
        assertTrue(
                toolNames.contains("task_output"),
                "subagent async path should register task_output");

        agent.call(userText("go"), RuntimeContext.builder().sessionId("s2").build()).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String combined =
                captor.getAllValues().stream()
                        .map(HarnessAgentTest::joinAllText)
                        .filter(s -> s.contains("## Subagents"))
                        .findFirst()
                        .orElse("");
        assertTrue(
                combined.contains("## Subagents"), "subagent hook should inject Subagents section");
        assertTrue(
                combined.contains("`" + specId + "`"),
                "Markdown subagent id (from filename) should appear in prompt");
        assertTrue(
                combined.contains("general-purpose"),
                "built-in general-purpose entry should be listed");
    }

    @Test
    void subagentsDir_loadsMarkdownDeclarations() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# w\n");
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        // Name is derived from the filename, not the front matter
        String expectedName = "helper";
        Files.writeString(
                subagents.resolve(expectedName + ".md"),
                """
                ---
                description: Loaded from subagents/*.md
                maxIters: 3
                ---

                You are a test subagent from markdown.
                """);

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("x"))
                        .workspace(workspace)
                        .buildSubagentEntries(workspace);

        List<String> names = entries.stream().map(SubagentEntry::name).collect(Collectors.toList());
        assertTrue(names.contains("general-purpose"));
        assertTrue(
                names.contains(expectedName),
                "subagents/*.md declaration should use filename as name");
    }

    @Test
    void parentMiddleware_propagatesToBuiltinAndMarkdownSubagents() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# workspace\n");
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        Files.writeString(
                subagents.resolve("helper.md"),
                """
                ---
                description: Markdown child used for middleware propagation regression
                ---
                You only reply OK.
                """);

        AtomicInteger systemPromptHits = new AtomicInteger();
        Model model = stubModel("done");
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("main")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .middleware(
                                new MiddlewareBase() {
                                    @Override
                                    public Flux<AgentEvent> onAgent(
                                            Agent agent,
                                            RuntimeContext ctx,
                                            io.agentscope.core.middleware.AgentInput input,
                                            java.util.function.Function<
                                                            io.agentscope.core.middleware
                                                                    .AgentInput,
                                                            Flux<AgentEvent>>
                                                    next) {
                                        systemPromptHits.incrementAndGet();
                                        return next.apply(input);
                                    }
                                });

        List<SubagentEntry> entries = builder.buildSubagentEntries(workspace);
        RuntimeContext parentContext =
                RuntimeContext.builder().userId("u").sessionId("parent").build();

        HarnessAgent generalPurpose =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "general-purpose".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(parentContext);
        generalPurpose
                .call(userText("hi"), RuntimeContext.builder().sessionId("gp").build())
                .block();
        assertEquals(
                1, systemPromptHits.get(), "general-purpose subagent should inherit middleware");

        HarnessAgent markdownChild =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "helper".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(parentContext);
        markdownChild
                .call(userText("hi"), RuntimeContext.builder().sessionId("md").build())
                .block();
        assertEquals(
                2,
                systemPromptHits.get(),
                "markdown-declared subagent should inherit parent middleware too");
    }

    @Test
    void fromAgent_filtersRuntimeMiddlewareAndStillPropagatesUserMiddleware() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# workspace\n");
        Path subagents = workspace.resolve("subagents");
        Files.createDirectories(subagents);
        Files.writeString(
                subagents.resolve("helper.md"),
                """
                ---
                description: Markdown child used for fromAgent regression
                ---
                You only reply OK.
                """);

        AtomicInteger userMiddlewareHits = new AtomicInteger();
        MiddlewareBase userMiddleware =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            io.agentscope.core.middleware.AgentInput input,
                            java.util.function.Function<
                                            io.agentscope.core.middleware.AgentInput,
                                            Flux<AgentEvent>>
                                    next) {
                        userMiddlewareHits.incrementAndGet();
                        return next.apply(input);
                    }
                };

        ReActAgent source =
                ReActAgent.builder()
                        .name("source")
                        .model(stubModel("done"))
                        .toolkit(new Toolkit())
                        .middlewares(List.of(userMiddleware, new AgentTraceMiddleware()))
                        .build();

        HarnessAgent.Builder builder =
                HarnessAgent.Builder.fromAgent(source)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace));

        List<SubagentEntry> entries = builder.buildSubagentEntries(workspace);
        HarnessAgent child = builder.build();

        long copiedUserMiddlewareCount =
                child.getDelegate().getMiddlewares().stream()
                        .filter(m -> m == userMiddleware)
                        .count();
        long agentTraceMiddlewareCount =
                child.getDelegate().getMiddlewares().stream()
                        .filter(m -> m instanceof AgentTraceMiddleware)
                        .count();
        long gracefulShutdownMiddlewareCount =
                child.getDelegate().getMiddlewares().stream()
                        .filter(m -> m instanceof GracefulShutdownMiddleware)
                        .count();
        assertEquals(1, copiedUserMiddlewareCount, "user middleware should copy once");
        assertEquals(
                1,
                agentTraceMiddlewareCount,
                "runtime AgentTraceMiddleware should not be duplicated when cloning");
        assertEquals(
                1,
                gracefulShutdownMiddlewareCount,
                "system GracefulShutdownMiddleware should not be duplicated when cloning");

        RuntimeContext parentContext = RuntimeContext.builder().sessionId("parent").build();
        HarnessAgent generalPurpose =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "general-purpose".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(parentContext);
        generalPurpose
                .call(userText("hi"), RuntimeContext.builder().sessionId("gp").build())
                .block();

        HarnessAgent markdownChild =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "helper".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(parentContext);
        markdownChild
                .call(userText("hi"), RuntimeContext.builder().sessionId("md").build())
                .block();

        assertEquals(
                2,
                userMiddlewareHits.get(),
                "fromAgent should still propagate user middleware into subagents");
    }

    @Test
    void remoteFilesystemSpec_sharesMemoryMdInNonsandboxMode() throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(WorkspaceConstants.AGENTS_MD), "# Test\n");
        InMemoryStore store = new InMemoryStore();

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("agent-a")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .filesystem(new RemoteFilesystemSpec(store))
                        .stateStore(mock(AgentStateStore.class))
                        .build()) {

            agent.getWorkspaceManager()
                    .writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "MEMORY.md", "shared-memory");

            assertTrue(
                    store.get(
                                    List.of("agents", "agent-a", "users", "_default", "root"),
                                    "/MEMORY.md")
                            != null);
        }
    }

    private static Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String joinAllText(List<Msg> msgs) {
        return msgs.stream().map(Msg::getTextContent).collect(Collectors.joining("\n"));
    }

    // =========================================================================
    // Decision table — five workspace/sysPrompt resolution paths
    // =========================================================================

    /** Row 1: isolated + workspace.path → runtime root = workspacePath. */
    @Test
    void decisionTable_row1_isolatedWithPath_runtimeRootIsWorkspacePath() throws Exception {
        Files.createDirectories(workspace);
        Path defWorkspace = workspace.resolve("defs/reviewer");
        Files.createDirectories(defWorkspace);
        Files.writeString(defWorkspace.resolve("AGENTS.md"), "reviewer-agents-md");

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("reviewer")
                        .description("code reviewer")
                        .workspace(defWorkspace)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .subagent(decl)
                        .buildSubagentEntries(workspace);

        SubagentEntry entry =
                entries.stream().filter(e -> "reviewer".equals(e.name())).findFirst().orElseThrow();
        HarnessAgent child = (HarnessAgent) entry.factory().create(RuntimeContext.empty());

        assertEquals(
                defWorkspace.normalize(),
                child.getWorkspaceManager().getWorkspace().normalize(),
                "isolated+path: runtime workspace must be the definition workspace");
    }

    /** Row 2: isolated + no path → runtime root is auto-created agents/&lt;name&gt;/workspace. */
    @Test
    void decisionTable_row2_isolatedNoPath_autoCreatesAgentSubdir() throws Exception {
        Files.createDirectories(workspace);

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("isolated-auto")
                        .description("auto-isolated subagent")
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .inlineAgentsBody("You are an isolated subagent.")
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .subagent(decl)
                        .buildSubagentEntries(workspace);

        SubagentEntry entry =
                entries.stream()
                        .filter(e -> "isolated-auto".equals(e.name()))
                        .findFirst()
                        .orElseThrow();
        HarnessAgent child = (HarnessAgent) entry.factory().create(RuntimeContext.empty());

        Path expected = workspace.resolve("agents/isolated-auto/workspace").normalize();
        assertEquals(
                expected,
                child.getWorkspaceManager().getWorkspace().normalize(),
                "isolated+no-path: runtime workspace must be auto agents/<name>/workspace");
        assertTrue(
                Files.isDirectory(expected),
                "isolated auto workspace directory should be created on the filesystem");
    }

    /** Row 3: shared + workspace.path → runtime root = mainWorkspace. */
    @Test
    void decisionTable_row3_sharedWithPath_runtimeRootIsMainWorkspace() throws Exception {
        Files.createDirectories(workspace);
        Path defWorkspace = workspace.resolve("defs/shared-def");
        Files.createDirectories(defWorkspace);
        Files.writeString(defWorkspace.resolve("AGENTS.md"), "shared-def-agents");

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("shared-ext")
                        .description("shared subagent with def workspace")
                        .workspace(defWorkspace)
                        .workspaceMode(WorkspaceMode.SHARED)
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .subagent(decl)
                        .buildSubagentEntries(workspace);

        SubagentEntry entry =
                entries.stream()
                        .filter(e -> "shared-ext".equals(e.name()))
                        .findFirst()
                        .orElseThrow();
        HarnessAgent child = (HarnessAgent) entry.factory().create(RuntimeContext.empty());

        assertEquals(
                workspace.normalize(),
                child.getWorkspaceManager().getWorkspace().normalize(),
                "shared+path: runtime workspace must be mainWorkspace, not the def path");
    }

    /** Row 4: shared + no path → runtime root = mainWorkspace. */
    @Test
    void decisionTable_row4_sharedNoPath_runtimeRootIsMainWorkspace() throws Exception {
        Files.createDirectories(workspace);

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("shared-inline")
                        .description("inline shared subagent")
                        .workspaceMode(WorkspaceMode.SHARED)
                        .inlineAgentsBody("You share the main workspace.")
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .subagent(decl)
                        .buildSubagentEntries(workspace);

        SubagentEntry entry =
                entries.stream()
                        .filter(e -> "shared-inline".equals(e.name()))
                        .findFirst()
                        .orElseThrow();
        HarnessAgent child = (HarnessAgent) entry.factory().create(RuntimeContext.empty());

        assertEquals(
                workspace.normalize(),
                child.getWorkspaceManager().getWorkspace().normalize(),
                "shared+no-path: runtime workspace must be mainWorkspace");
    }

    /** Row 5 (built-in general-purpose): runtime root = mainWorkspace. */
    @Test
    void decisionTable_row5_generalPurpose_runtimeRootIsMainWorkspace() throws Exception {
        Files.createDirectories(workspace);

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .buildSubagentEntries(workspace);

        SubagentEntry gp =
                entries.stream()
                        .filter(e -> "general-purpose".equals(e.name()))
                        .findFirst()
                        .orElseThrow();
        HarnessAgent child = (HarnessAgent) gp.factory().create(RuntimeContext.empty());

        assertEquals(
                workspace.normalize(),
                child.getWorkspaceManager().getWorkspace().normalize(),
                "general-purpose must share mainWorkspace");
    }

    // =========================================================================
    // declared subagent execution-config inheritance (issue #2230)
    // =========================================================================

    /**
     * A declared subagent must inherit the parent agent's {@code toolExecutionConfig} and {@code
     * modelExecutionConfig}. Before the fix, {@code buildDeclaredFactory} never propagated them, so
     * a declared subagent silently fell back to {@link ExecutionConfig} defaults (e.g. the
     * 5-minute tool timeout), ignoring a longer timeout configured on the main agent.
     */
    @Test
    void declaredSubagent_inheritsExecutionConfigsFromParent() throws Exception {
        Files.createDirectories(workspace);

        ExecutionConfig toolExec =
                ExecutionConfig.builder().timeout(Duration.ofHours(1)).maxAttempts(2).build();
        ExecutionConfig modelExec =
                ExecutionConfig.builder().timeout(Duration.ofMinutes(3)).maxAttempts(4).build();

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("declared worker")
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .inlineAgentsBody("You are a worker subagent.")
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .toolExecutionConfig(toolExec)
                        .modelExecutionConfig(modelExec)
                        .subagent(decl)
                        .buildSubagentEntries(workspace);

        SubagentEntry entry =
                entries.stream().filter(e -> "worker".equals(e.name())).findFirst().orElseThrow();
        HarnessAgent child = (HarnessAgent) entry.factory().create(RuntimeContext.empty());
        ReActAgent delegate = child.getDelegate();

        assertSame(
                toolExec,
                delegate.getToolExecutionConfig(),
                "declared subagent must inherit parent toolExecutionConfig");
        assertSame(
                modelExec,
                delegate.getModelExecutionConfig(),
                "declared subagent must inherit parent modelExecutionConfig");
    }

    // =========================================================================
    // general-purpose mirroring
    // =========================================================================

    @Test
    void generalPurpose_mirrorDisableFilesystemTools() throws Exception {
        Files.createDirectories(workspace);
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .disableFilesystemTools()
                        .buildSubagentEntries(workspace);

        HarnessAgent child =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "general-purpose".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(RuntimeContext.empty());
        List<String> toolNames =
                child.getToolkit().getToolSchemas().stream().map(ToolSchema::getName).toList();
        assertFalse(toolNames.contains("read_file"), "disableFilesystemTools should be mirrored");
    }

    @Test
    void generalPurpose_mirrorCompactionConfig() throws Exception {
        Files.createDirectories(workspace);
        CompactionConfig cfg = CompactionConfig.builder().triggerMessages(5).build();
        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .compaction(cfg)
                        .buildSubagentEntries(workspace);

        HarnessAgent child =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "general-purpose".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(RuntimeContext.empty());
        assertNotNull(child.getCompactionHook(), "CompactionHook should be mirrored to GP child");
    }

    // =========================================================================
    // Multiple declarations → same definition workspace
    // =========================================================================

    @Test
    void multipleDeclarations_sameDefinitionWorkspace_bothResolveSamePath() throws Exception {
        Files.createDirectories(workspace);
        Path defWorkspace = workspace.resolve("defs/shared-def");
        Files.createDirectories(defWorkspace);
        Files.writeString(defWorkspace.resolve("AGENTS.md"), "shared definition");

        SubagentDeclaration decl1 =
                SubagentDeclaration.builder()
                        .name("agent-a")
                        .description("first alias")
                        .workspace(defWorkspace)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build();
        SubagentDeclaration decl2 =
                SubagentDeclaration.builder()
                        .name("agent-b")
                        .description("second alias")
                        .workspace(defWorkspace)
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .subagent(decl1)
                        .subagent(decl2)
                        .buildSubagentEntries(workspace);

        HarnessAgent childA =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "agent-a".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(RuntimeContext.empty());
        HarnessAgent childB =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "agent-b".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(RuntimeContext.empty());

        assertEquals(
                defWorkspace.normalize(), childA.getWorkspaceManager().getWorkspace().normalize());
        assertEquals(
                defWorkspace.normalize(),
                childB.getWorkspaceManager().getWorkspace().normalize(),
                "Both declarations point to the same definition workspace");
    }

    // =========================================================================
    // Tools allowlist
    // =========================================================================

    @Test
    void toolsAllowlist_filtersInheritedParentTools_only() throws Exception {
        Files.createDirectories(workspace);

        Toolkit parentToolkit = new Toolkit();
        parentToolkit.registerAgentTool(mockAgentTool("parent_allowed"));
        parentToolkit.registerAgentTool(mockAgentTool("parent_denied"));

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("narrow")
                        .description("narrowed toolkit")
                        .inlineAgentsBody("Only read files.")
                        .tools(List.of("parent_allowed"))
                        .build();

        List<SubagentEntry> entries =
                HarnessAgent.builder()
                        .model(stubModel("ok"))
                        .toolkit(parentToolkit)
                        .workspace(workspace)
                        .subagent(decl)
                        .buildSubagentEntries(workspace);

        HarnessAgent child =
                (HarnessAgent)
                        entries.stream()
                                .filter(e -> "narrow".equals(e.name()))
                                .findFirst()
                                .orElseThrow()
                                .factory()
                                .create(RuntimeContext.empty());
        List<String> toolNames =
                child.getToolkit().getToolSchemas().stream().map(ToolSchema::getName).toList();
        assertTrue(
                toolNames.contains("parent_allowed"), "allowlisted inherited tool should remain");
        assertFalse(
                toolNames.contains("parent_denied"),
                "non-allowlisted inherited tool should be removed");
        assertTrue(
                toolNames.contains("read_file"),
                "child-local filesystem tools should not be filtered by inherited allowlist");
        assertTrue(
                toolNames.contains("memory_search"),
                "child-local memory tools should not be filtered by inherited allowlist");
    }

    // =========================================================================
    // Skills allowlist
    // =========================================================================

    @Test
    void skillsAllowlist_declarationWithSkills_setsSkillFilterOnChild() throws Exception {
        Files.createDirectories(workspace);

        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("narrow-skills")
                        .description("only allowed skills")
                        .inlineAgentsBody("Focus on allowed skills.")
                        .skills(List.of("allowed_skill"))
                        .build();

        assertEquals(List.of("allowed_skill"), decl.getSkills());

        SkillFilter filter = SkillFilter.only(decl.getSkills().toArray(new String[0]));
        assertTrue(filter.isAllowed("allowed_skill"), "allowlisted skill should pass");
        assertFalse(filter.isAllowed("denied_skill"), "non-allowlisted skill should be blocked");
    }

    @Test
    void skillsAllowlist_emptyList_defaultsToAll() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("all-skills")
                        .description("inherits all skills")
                        .inlineAgentsBody("Use any skill.")
                        .build();

        assertTrue(decl.getSkills().isEmpty(), "no skills declared should yield empty list");
    }

    @Test
    void skillsAllowlist_nullSkills_defaultsToEmptyList() {
        SubagentDeclaration decl =
                SubagentDeclaration.builder()
                        .name("null-skills")
                        .description("null skills")
                        .inlineAgentsBody("body")
                        .skills(null)
                        .build();

        assertTrue(decl.getSkills().isEmpty(), "null skills should yield empty list");
    }

    // =========================================================================
    // AgentSpecLoader — markdown declaration parsing
    // =========================================================================

    @Test
    void agentSpecLoader_markdownDeclaration_isolatedWithWorkspacePath() throws Exception {
        Files.createDirectories(workspace);
        Path defPath = workspace.resolve("defs/myagent");
        String markdown =
                """
                ---
                description: My agent description
                workspace:
                  mode: isolated
                  path: defs/myagent
                model: test-model
                maxIters: 7
                tools: [read_file, grep_files]
                ---
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(markdown, "my-agent", workspace);
        assertNotNull(decl);
        assertEquals("my-agent", decl.getName());
        assertEquals("My agent description", decl.getDescription());
        assertEquals(WorkspaceMode.ISOLATED, decl.getWorkspaceMode());
        assertEquals(defPath.normalize(), decl.getWorkspacePath().normalize());
        assertEquals("test-model", decl.getModel());
        assertEquals(7, decl.getMaxIters());
        assertEquals(List.of("read_file", "grep_files"), decl.getTools());
    }

    @Test
    void agentSpecLoader_markdownDeclaration_parsesSkillsAllowlist() throws Exception {
        Files.createDirectories(workspace);
        String markdown =
                """
                ---
                description: Agent with skills filter
                tools: [read_file]
                skills: [weather_lookup, code_review]
                ---
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(markdown, "filtered-agent", workspace);
        assertNotNull(decl);
        assertEquals(List.of("weather_lookup", "code_review"), decl.getSkills());
    }

    @Test
    void agentSpecLoader_markdownDeclaration_noSkills_returnsEmptyList() throws Exception {
        Files.createDirectories(workspace);
        String markdown =
                """
                ---
                description: Agent without skills
                ---
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(markdown, "no-skills", workspace);
        assertNotNull(decl);
        assertTrue(decl.getSkills().isEmpty(), "no skills declared should yield empty list");
    }

    @Test
    void agentSpecLoader_markdownDeclaration_sharedMode_noPath_inlineBody() throws Exception {
        Files.createDirectories(workspace);
        String markdown =
                """
                ---
                description: Inline shared agent
                workspace:
                  mode: shared
                ---

                You are the inline sysPrompt.
                """;
        SubagentDeclaration decl = AgentSpecLoader.parse(markdown, "inline-shared", null);
        assertNotNull(decl);
        assertEquals("inline-shared", decl.getName());
        assertEquals(WorkspaceMode.SHARED, decl.getWorkspaceMode());
        assertFalse(decl.hasDefinitionWorkspace());
        assertTrue(
                decl.getInlineAgentsBody().contains("inline sysPrompt"),
                "body should be inline agents body when no workspace.path");
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    private static AgentTool mockAgentTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn("mock tool " + name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object", "properties", Map.of()));
        return tool;
    }
}
