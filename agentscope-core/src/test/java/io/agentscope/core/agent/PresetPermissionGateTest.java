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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that the agent permission gate evaluates the same effective input the execution layer
 * will see: preset parameters are merged (preset wins, mirroring {@code ToolExecutor}) before the
 * engine / tool self-check runs, so a preset-supplied path outside the working scope can never be
 * silently auto-allowed.
 */
class PresetPermissionGateTest {

    /** Copy tool whose {@code src}/{@code dst} are declared as file paths. */
    private static final class CopyTool extends ToolBase {
        CopyTool() {
            super(
                    ToolBase.builder()
                            .name("copy_tool")
                            .description("copy a file")
                            .inputSchema(schema())
                            .filePathParams(Set.of("src", "dst")));
        }

        private static Map<String, Object> schema() {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            props.put("src", Map.of("type", "string"));
            props.put("dst", Map.of("type", "string"));
            schema.put("properties", props);
            return schema;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("copied"));
        }
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final Flux<ChatResponse> script;

        ScriptedModel(Flux<ChatResponse> script) {
            this.script = script;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return script;
        }
    }

    private static ChatResponse toolUseResponse(String toolId, String toolName, String src) {
        Map<String, Object> input = new HashMap<>();
        input.put("src", src);
        return ChatResponse.builder()
                .content(
                        List.<ContentBlock>of(
                                ToolUseBlock.builder()
                                        .id(toolId)
                                        .name(toolName)
                                        .input(input)
                                        .build()))
                .build();
    }

    private static ReActAgent agentWith(
            ChatModelBase model, Toolkit toolkit, PermissionContextState context) {
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(context)
                .build();
    }

    private static PermissionContextState acceptEditsWithDir(String dir) {
        return PermissionContextState.builder()
                .mode(PermissionMode.ACCEPT_EDITS)
                .addWorkingDirectory(dir, new AdditionalWorkingDirectory(dir, "test"))
                .build();
    }

    @Test
    void presetDstOutsideWorkingScopeMustNotAutoAllow(@TempDir Path workDir) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new CopyTool());
        toolkit.updateToolPresetParameters("copy_tool", Map.of("dst", "/etc/outside.txt"));

        ReActAgent agent =
                agentWith(
                        new ScriptedModel(
                                Flux.just(
                                        toolUseResponse(
                                                "tc1",
                                                "copy_tool",
                                                workDir.resolve("a.txt").toString()))),
                        toolkit,
                        acceptEditsWithDir(workDir.toString()));

        Msg result = agent.call(List.of()).block();

        // The effective input contains dst=/etc/outside.txt, so the call must not be
        // auto-allowed and the agent pauses for permission.
        assertEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason());
    }

    @Test
    void presetDstInsideWorkingScopeExecutesNormally(@TempDir Path workDir) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new CopyTool());
        toolkit.updateToolPresetParameters(
                "copy_tool", Map.of("dst", workDir.resolve("b.txt").toString()));

        ReActAgent agent =
                agentWith(
                        new ScriptedModel(
                                Flux.just(
                                        toolUseResponse(
                                                "tc1",
                                                "copy_tool",
                                                workDir.resolve("a.txt").toString()))),
                        toolkit,
                        acceptEditsWithDir(workDir.toString()));

        Msg result = agent.call(List.of()).block();

        assertNotEquals(GenerateReason.PERMISSION_ASKING, result.getGenerateReason());
        boolean executed =
                agent.getAgentState().getContext().stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .anyMatch(
                                tr ->
                                        "tc1".equals(tr.getId())
                                                && tr.getState() == ToolResultState.SUCCESS);
        assertTrue(executed, "tool must have executed with the effective preset input");
    }

    @Test
    void effectiveInputMergesPresetsWithPresetPriority() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new CopyTool());
        toolkit.updateToolPresetParameters("copy_tool", Map.of("dst", "/tmp/out.txt"));

        Map<String, Object> effective =
                toolkit.effectiveInput("copy_tool", Map.of("src", "/tmp/in.txt"));

        assertEquals(
                Map.of("src", "/tmp/in.txt", "dst", "/tmp/out.txt"),
                effective,
                "preset parameters must be visible in the effective input");

        // Preset wins over the caller-supplied value, mirroring ToolExecutor.
        assertEquals(
                Map.of("dst", "/tmp/out.txt"),
                toolkit.effectiveInput("copy_tool", Map.of("dst", "/tmp/override.txt")));

        // Unknown tools contribute no preset parameters; a new map is returned every time.
        assertEquals(
                Map.of("src", "/tmp/x.txt"),
                toolkit.effectiveInput("unknown", Map.of("src", "/tmp/x.txt")));
        Map<String, Object> first = toolkit.effectiveInput("copy_tool", Map.of("src", "a"));
        first.put("src", "mutated");
        assertEquals(
                Map.of("src", "a", "dst", "/tmp/out.txt"),
                toolkit.effectiveInput("copy_tool", Map.of("src", "a")));
    }
}
