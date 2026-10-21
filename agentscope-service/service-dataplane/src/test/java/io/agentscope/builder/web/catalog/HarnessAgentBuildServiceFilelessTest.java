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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class HarnessAgentBuildServiceFilelessTest {
    @TempDir Path root;

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
                HarnessAgent.builder()
                        .name("test-agent")
                        .model(model)
                        .workspace(root.resolve(replica))
                        .disableDefaultWorkspaceSkills();
        HarnessAgentBuildService.configureFilelessManagedAgent(builder, store, scope, Map.of());
        return builder.build();
    }
}
