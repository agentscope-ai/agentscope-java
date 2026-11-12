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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class HarnessAgentBuildServiceFilelessTest {
    @TempDir Path root;

    @Test
    void shortEvaluationPreservesLongToolHistoryAndDisablesAuxiliarySummaries() {
        var count = new AtomicInteger();
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        String original = "x".repeat(90_000);
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (count.getAndIncrement() == 0)
                                return Flux.just(
                                        new ChatResponse(
                                                "call",
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("call-1")
                                                                .name("fixture__lookup")
                                                                .input(Map.of())
                                                                .build()),
                                                null,
                                                Map.of(),
                                                "tool_use"));
                            List<Msg> messages = invocation.getArgument(0);
                            var results =
                                    messages.stream()
                                            .flatMap(m -> m.getContent().stream())
                                            .filter(ToolResultBlock.class::isInstance)
                                            .map(ToolResultBlock.class::cast)
                                            .flatMap(result -> result.getOutput().stream())
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .map(TextBlock::getText)
                                            .toList();
                            assertThat(results.contains(original))
                                    .as("Full tool history reaches the model")
                                    .isTrue();
                            return Flux.just(
                                    new ChatResponse(
                                            "reply",
                                            List.of(TextBlock.builder().text("done").build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });
        var tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn("fixture__lookup");
        when(tool.getDescription()).thenReturn("Lookup");
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        when(tool.callAsync(any()))
                .thenReturn(
                        Mono.just(
                                new ToolResultBlock(
                                        "call-1",
                                        "fixture__lookup",
                                        TextBlock.builder().text(original).build())));
        var toolkit = new Toolkit();
        toolkit.registration().agentTool(tool).apply();
        var builder =
                HarnessAgentBuildService.newAgentBuilder("short")
                        .name("short")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(root.resolve("short"));
        // Extension builders can re-enable these features through their public setters.
        builder.compaction(CompactionConfig.builder().triggerMessages(1).triggerTokens(1).build());
        builder.toolResultEviction(ToolResultEvictionConfig.builder().build());
        HarnessAgentBuildService.configureEvaluationContext(builder, "short");
        HarnessAgentBuildService.configureMcpOnlyEvaluation(builder, List.of("fixture__lookup"));
        try (var agent = builder.build()) {
            for (int turn = 0; turn < 2; turn++)
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(TextBlock.builder().text("hello").build())
                                        .build())
                        .block(Duration.ofSeconds(10));
        }
        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    void mcpEvaluationExcludesImplicitToolsEvenAfterSkillMiddlewareRuns() {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        when(model.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<ToolSchema> schemas = invocation.getArgument(1);
                            assertThat(schemas)
                                    .extracting(ToolSchema::getName)
                                    .containsExactly("fixture__lookup");
                            return Flux.just(
                                    new ChatResponse(
                                            "reply",
                                            List.of(TextBlock.builder().text("done").build()),
                                            null,
                                            Map.of(),
                                            "stop"));
                        });
        var tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn("fixture__lookup");
        when(tool.getDescription()).thenReturn("Lookup");
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        var toolkit = new Toolkit();
        toolkit.registration().agentTool(tool).apply();
        var builder =
                HarnessAgentBuildService.newAgentBuilder()
                        .name("evaluation")
                        .model(model)
                        .toolkit(toolkit)
                        .workspace(root.resolve("evaluation"));
        HarnessAgentBuildService.configureMcpOnlyEvaluation(builder, List.of("fixture__lookup"));
        try (var agent = builder.build()) {
            for (int turn = 0; turn < 2; turn++) {
                var response =
                        agent.call(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .content(TextBlock.builder().text("hello").build())
                                                .build())
                                .block(Duration.ofSeconds(10));
                assertThat(response.getTextContent()).contains("done");
                assertThat(agent.getToolkit().getToolNames()).containsExactly("fixture__lookup");
            }
        }
    }

    @Test
    void textTurnInitializesWithoutSandboxAndDoesNotExposeFileOrShellTools() {
        var store = new InMemoryStore();
        var scope = new ManagedScope("tenant", "namespace", "session");
        try (var agent = agent(store, scope, "first")) {
            assertThat(agent.getWorkspaceManager().getFilesystem())
                    .isInstanceOf(RemoteFilesystem.class);
            assertThat(agent.getToolkit().getToolSchemas())
                    .extracting(ToolSchema::getName)
                    .doesNotContain(
                            "read_file",
                            "write_file",
                            "edit_file",
                            "list_files",
                            "glob_files",
                            "grep_files",
                            "shell",
                            "exec",
                            "deliver_artifact");
            var result =
                    agent.call(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .content(TextBlock.builder().text("hello").build())
                                            .build(),
                                    RuntimeContext.builder()
                                            .sessionId("session")
                                            .userId("user")
                                            .build())
                            .block(Duration.ofSeconds(10));
            assertThat(result).isNotNull();
            assertThat(result.getTextContent()).contains("done");
        }
    }

    @Test
    void internalWorkspaceSurvivesReplicaReplacementAndKeepsScopeIsolation() {
        var store = new InMemoryStore();
        var scope = new ManagedScope("tenant", "namespace", "session");
        var context = RuntimeContext.empty();
        try (var first = agent(store, scope, "first")) {
            assertThat(
                            first.getWorkspaceManager()
                                    .getFilesystem()
                                    .write(context, "/MEMORY.md", "remembered"))
                    .isNotNull();
        }
        try (var second = agent(store, scope, "second");
                var other = agent(store, new ManagedScope("tenant", "other", "session"), "other")) {
            assertThat(
                            second.getWorkspaceManager()
                                    .getFilesystem()
                                    .read(context, "/MEMORY.md", 0, 100)
                                    .fileData()
                                    .content())
                    .isEqualTo("remembered");
            assertThat(
                            other.getWorkspaceManager()
                                    .getFilesystem()
                                    .read(context, "/MEMORY.md", 0, 100)
                                    .isSuccess())
                    .isFalse();
        }
    }

    private HarnessAgent agent(InMemoryStore store, ManagedScope scope, String replica) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("test-model");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "reply",
                                        List.of(TextBlock.builder().text("done").build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        var builder =
                HarnessAgentBuildService.newAgentBuilder()
                        .name("test-agent")
                        .model(model)
                        .workspace(root.resolve(replica))
                        .disableDefaultWorkspaceSkills();
        HarnessAgentBuildService.configureFilelessManagedAgent(builder, store, scope, Map.of());
        return builder.build();
    }
}
