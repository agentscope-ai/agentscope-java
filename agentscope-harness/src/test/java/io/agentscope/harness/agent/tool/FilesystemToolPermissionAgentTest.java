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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspacePathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class FilesystemToolPermissionAgentTest {

    @TempDir Path workspace;
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("alice").sessionId("session").build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniesDefinitionWritesWhileAllowingReadsAndOrdinaryWrites(boolean streaming)
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "original");
        LocalFilesystem filesystem = spy(new LocalFilesystem(workspace));
        Toolkit toolkit = toolkit(filesystem);
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                use(
                                        "write",
                                        "write_file",
                                        Map.of(
                                                "path",
                                                "/workspace/alice/session/AGENTS.md",
                                                "content",
                                                "changed")),
                                use(
                                        "edit",
                                        "edit_file",
                                        Map.of(
                                                "path",
                                                "AGENTS.md",
                                                "old_string",
                                                "original",
                                                "new_string",
                                                "changed")),
                                use(
                                        "report",
                                        "write_file",
                                        Map.of("path", "report.txt", "content", "report")),
                                use("read", "read_file", Map.of("path", "AGENTS.md"))));
        run(model, toolkit, streaming);
        assertEquals("original", Files.readString(workspace.resolve("AGENTS.md")));
        assertTrue(
                Files.exists(workspace.resolve("report.txt")),
                () -> JsonUtils.getJsonCodec().toJson(model.lastMessages));
        assertEquals("report", Files.readString(workspace.resolve("report.txt")));
        verify(filesystem, never()).write(any(), eq("AGENTS.md"), anyString());
        verify(filesystem, never())
                .edit(any(), eq("AGENTS.md"), anyString(), anyString(), anyBoolean());
        verify(filesystem).read(any(), eq("AGENTS.md"), eq(0), eq(0));
        assertEquals(2, model.calls.get());
        assertEquals(
                Map.of(
                        "write",
                        ToolResultState.DENIED,
                        "edit",
                        ToolResultState.DENIED,
                        "report",
                        ToolResultState.SUCCESS,
                        "read",
                        ToolResultState.SUCCESS),
                model.resultStates());
        // Framework writes use the filesystem directly and are unaffected by tool rules.
        assertTrue(
                filesystem
                        .edit(CONTEXT, "AGENTS.md", "original", "framework update", false)
                        .isSuccess());
        assertEquals("framework update", Files.readString(workspace.resolve("AGENTS.md")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniesThePresetPathBeforeTheToolExecutes(boolean streaming) throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "original");
        LocalFilesystem filesystem = spy(new LocalFilesystem(workspace));
        Toolkit toolkit = new Toolkit();
        toolkit.registration()
                .tool(new FilesystemTool(filesystem))
                .presetParameters(Map.of("write_file", Map.of("path", "AGENTS.md")))
                .apply();
        ScriptedModel model =
                new ScriptedModel(
                        List.of(use("write", "write_file", Map.of("content", "changed"))));
        run(model, toolkit, streaming);
        assertEquals("original", Files.readString(workspace.resolve("AGENTS.md")));
        verify(filesystem, never()).write(any(), anyString(), anyString());
        assertEquals(Map.of("write", ToolResultState.DENIED), model.resultStates());
        assertEquals(2, model.calls.get());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void rejectsAmbiguousRelativeAliasBeforeItCanEditTheDefinition(
            boolean streaming, boolean driveRelative) throws Exception {
        assumeTrue(!driveRelative || java.io.File.separatorChar == '\\');
        Files.writeString(workspace.resolve("AGENTS.md"), "original");
        LocalFilesystem filesystem = spy(new LocalFilesystem(workspace));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FilesystemTool(filesystem));
        String alias =
                driveRelative
                        ? workspace.getRoot().toString().substring(0, 2) + "AGENTS.md"
                        : "../" + workspace.getFileName() + "/AGENTS.md";
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                use(
                                        "edit",
                                        "edit_file",
                                        Map.of(
                                                "path",
                                                alias,
                                                "old_string",
                                                "original",
                                                "new_string",
                                                "changed"))));
        assertThrows(IllegalArgumentException.class, () -> run(model, toolkit, streaming));
        assertEquals("original", Files.readString(workspace.resolve("AGENTS.md")));
        verify(filesystem, never())
                .edit(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    private Toolkit toolkit(LocalFilesystem filesystem) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(
                new FilesystemTool(
                        filesystem,
                        WorkspacePathNormalizer.of(
                                "/workspace", rc -> List.of(rc.getUserId(), rc.getSessionId()))));
        return toolkit;
    }

    private void run(ScriptedModel model, Toolkit toolkit, boolean streaming) {
        PermissionContextState.Builder permissions =
                PermissionContextState.builder().mode(PermissionMode.BYPASS);
        for (String name : List.of("write_file", "edit_file")) {
            permissions.addDenyRule(
                    name, new PermissionRule(name, "AGENTS.md", PermissionBehavior.DENY, "test"));
        }
        ReActAgent agent =
                ReActAgent.builder()
                        .name("filesystem-test")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(permissions.build())
                        .maxIters(3)
                        .build();
        Msg input = Msg.builder().role(MsgRole.USER).textContent("Run tools").build();
        try {
            if (streaming) {
                assertNotNull(
                        agent.streamEvents(List.of(input), CONTEXT)
                                .collectList()
                                .block(Duration.ofSeconds(10)));
            } else {
                assertNotNull(agent.call(List.of(input), CONTEXT).block(Duration.ofSeconds(10)));
            }
        } finally {
            agent.close();
        }
    }

    private static ToolUseBlock use(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(input)
                .content(JsonUtils.getJsonCodec().toJson(input))
                .build();
    }

    private static class ScriptedModel extends ChatModelBase {
        private final List<ToolUseBlock> tools;
        private final AtomicInteger calls = new AtomicInteger();
        private List<Msg> lastMessages = List.of();

        ScriptedModel(List<ToolUseBlock> tools) {
            this.tools = tools;
        }

        Map<String, ToolResultState> resultStates() {
            return lastMessages.stream()
                    .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .collect(Collectors.toMap(ToolResultBlock::getId, ToolResultBlock::getState));
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
            lastMessages = List.copyOf(messages);
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    calls.getAndIncrement() == 0
                                            ? List.copyOf(tools)
                                            : List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }
}
