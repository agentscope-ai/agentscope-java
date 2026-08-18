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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link HarnessAgent.Builder#disableLocalWorkspace()}: a "no local workspace" build
 * must never materialise {@code .agentscope} under the working directory, and it must fail fast
 * with a descriptive error when the workspace-backed defaults (state store, task repository)
 * cannot be avoided.
 */
class HarnessAgentLocalWorkspaceDisabledTest {

    @TempDir Path workingDirectory;

    private String previousUserDir;
    private String previousWorkspaceProperty;

    @BeforeEach
    void pointWorkingDirectoryAtTempDir() {
        previousUserDir = System.getProperty("user.dir");
        previousWorkspaceProperty = System.getProperty(HarnessAgent.WORKSPACE_PROPERTY);
        // The default workspace would resolve against ${user.dir}; redirect it to a scratch dir
        // so the test can assert that NOTHING is created there in disabled mode.
        System.setProperty("user.dir", workingDirectory.toString());
        System.clearProperty(HarnessAgent.WORKSPACE_PROPERTY);
    }

    @AfterEach
    void restoreEnvironment() {
        System.setProperty("user.dir", previousUserDir);
        if (previousWorkspaceProperty != null) {
            System.setProperty(HarnessAgent.WORKSPACE_PROPERTY, previousWorkspaceProperty);
        } else {
            System.clearProperty(HarnessAgent.WORKSPACE_PROPERTY);
        }
    }

    @Test
    void disabledBuild_doesNotCreateDotAgentscopeUnderWorkingDirectory() {
        try (HarnessAgent agent = buildFullyDisabledAgent().build()) {
            assertFalse(
                    Files.exists(workingDirectory.resolve(".agentscope")),
                    "disableLocalWorkspace() must not create .agentscope under the working"
                            + " directory");
            assertFalse(
                    Files.exists(workingDirectory.resolve(".agentscope/workspace")),
                    "disableLocalWorkspace() must not create .agentscope/workspace under the"
                            + " working directory");
        }
    }

    @Test
    void disabledBuild_withoutExplicitStateStore_failsFast() {
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HarnessAgent.builder()
                                        .name("ws-disabled")
                                        .model(stubModel("ok"))
                                        .disableLocalWorkspace()
                                        .disableSubagents()
                                        .build());
        assertTrue(ex.getMessage().contains("disableLocalWorkspace()"));
        assertTrue(ex.getMessage().contains("AgentStateStore"));
    }

    @Test
    void disabledBuild_withSubagentsButNoTaskRepository_failsFast() {
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                HarnessAgent.builder()
                                        .name("ws-disabled")
                                        .model(stubModel("ok"))
                                        .disableLocalWorkspace()
                                        .stateStore(new InMemoryAgentStateStore())
                                        .disableFilesystemTools()
                                        .disableShellTool()
                                        .disableMemoryTools()
                                        .disableMemoryHooks()
                                        .disableDynamicSkills()
                                        .disableDefaultWorkspaceSkills()
                                        .disableWorkspaceContext()
                                        .disableTranscript()
                                        .build());
        assertTrue(ex.getMessage().contains("disableLocalWorkspace()"));
        assertTrue(ex.getMessage().contains("taskRepository"));
    }

    @Test
    void ephemeralWorkspace_resolvesOutsideWorkingDirectory() {
        Path ephemeral = HarnessAgent.resolveEphemeralWorkspace("my-agent");
        assertTrue(ephemeral.startsWith(Paths.get(System.getProperty("java.io.tmpdir"))));
        assertFalse(
                ephemeral.toAbsolutePath().toString().contains(workingDirectory.toString()),
                "ephemeral workspace must stay under the JVM temp dir, never the working"
                        + " directory");
        assertEquals(
                Paths.get(System.getProperty("java.io.tmpdir"), "agentscope-workspace", "my-agent"),
                ephemeral);
    }

    @Test
    void ephemeralWorkspace_sanitizesBlankAgentId() {
        assertEquals(
                Paths.get(
                        System.getProperty("java.io.tmpdir"), "agentscope-workspace", "ReActAgent"),
                HarnessAgent.resolveEphemeralWorkspace("   "));
        assertEquals(
                Paths.get(
                        System.getProperty("java.io.tmpdir"), "agentscope-workspace", "ReActAgent"),
                HarnessAgent.resolveEphemeralWorkspace(""));
    }

    private static HarnessAgent.Builder buildFullyDisabledAgent() {
        return HarnessAgent.builder()
                .name("ws-disabled")
                .model(stubModel("ok"))
                .disableLocalWorkspace()
                .stateStore(new InMemoryAgentStateStore())
                .disableSubagents()
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableTranscript()
                .disableCompaction()
                .disableToolResultEviction()
                .taskRepository(new NoopTaskRepository());
    }

    private static Model stubModel(String assistantText) {
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        return new StubModel(chunk);
    }

    /** Hand-written {@link Model} stub — avoids Mockito, which is JVM-version sensitive. */
    private static final class StubModel implements Model {

        private final ChatResponse response;

        StubModel(ChatResponse response) {
            this.response = response;
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(response);
        }
    }

    /** Minimal task repository that persists nothing. */
    private static final class NoopTaskRepository implements TaskRepository {

        @Override
        public BackgroundTask getTask(RuntimeContext rc, String sessionId, String taskId) {
            return null;
        }

        @Override
        public BackgroundTask putTask(
                RuntimeContext rc,
                String taskId,
                String subAgentId,
                String sessionId,
                TaskRunSpec spec) {
            return null;
        }

        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext rc, String sessionId, TaskStatus filter) {
            return List.of();
        }

        @Override
        public boolean cancelTask(RuntimeContext rc, String sessionId, String taskId) {
            return false;
        }
    }
}
