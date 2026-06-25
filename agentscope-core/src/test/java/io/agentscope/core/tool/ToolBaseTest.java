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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ToolBaseTest {

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    private static class PassthroughTool extends ToolBase {
        PassthroughTool() {
            super(
                    ToolBase.builder()
                            .name("passthrough_tool")
                            .description("Tool that always defers to the engine")
                            .inputSchema(emptySchema())
                            .readOnly(true)
                            .concurrencySafe(true));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.passthrough("defer"));
        }
    }

    private static class ExternalTool extends ToolBase {
        ExternalTool() {
            super(
                    ToolBase.builder()
                            .name("external_tool")
                            .description("External-only tool")
                            .inputSchema(emptySchema())
                            .externalTool(true)
                            .concurrencySafe(false));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.allow("external ok"));
        }
    }

    private static class DangerousPathProbe extends PassthroughTool {
        boolean check(String path) {
            return isDangerousPath(path);
        }
    }

    @Test
    void agentToolFieldsArePropagated() {
        ToolBase tool = new PassthroughTool();
        assertEquals("passthrough_tool", tool.getName());
        assertEquals("Tool that always defers to the engine", tool.getDescription());
        assertSame(tool.getParameters(), tool.getParameters());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertFalse(tool.isStateInjected());
        assertFalse(tool.isMcp());
        assertNull(tool.getMcpName());
    }

    @Test
    void mcpFlagRequiresMcpName() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ToolBase(
                                ToolBase.builder()
                                        .name("mcp_tool")
                                        .description("mcp")
                                        .inputSchema(emptySchema())
                                        .mcp("")) {
                            @Override
                            public Mono<PermissionDecision> checkPermissions(
                                    Map<String, Object> toolInput, PermissionContextState context) {
                                return Mono.empty();
                            }
                        });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ToolBase(
                                ToolBase.builder()
                                        .name("mcp_tool")
                                        .description("mcp")
                                        .inputSchema(emptySchema())
                                        .mcp(null)) {
                            @Override
                            public Mono<PermissionDecision> checkPermissions(
                                    Map<String, Object> toolInput, PermissionContextState context) {
                                return Mono.empty();
                            }
                        });
    }

    @Test
    void mcpFlagAcceptsValidMcpName() {
        ToolBase mcpTool =
                new ToolBase(
                        ToolBase.builder()
                                .name("mcp_tool")
                                .description("mcp")
                                .inputSchema(emptySchema())
                                .mcp("weather-server")) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState context) {
                        return Mono.empty();
                    }
                };
        assertTrue(mcpTool.isMcp());
        assertEquals("weather-server", mcpTool.getMcpName());
    }

    @Test
    void defaultMatchRuleMatchesOnlyNullContent() {
        ToolBase tool = new PassthroughTool();
        assertTrue(tool.matchRule(null, Map.of()));
        assertFalse(tool.matchRule("any-pattern", Map.of()));
        assertFalse(tool.matchRule("", Map.of()));
    }

    @Test
    void defaultGenerateSuggestionsReturnsToolNameLevelAllow() {
        ToolBase tool = new PassthroughTool();
        List<PermissionRule> suggestions = tool.generateSuggestions(Map.of());
        assertEquals(1, suggestions.size());
        PermissionRule rule = suggestions.get(0);
        assertEquals("passthrough_tool", rule.toolName());
        assertNull(rule.ruleContent());
        assertEquals(PermissionBehavior.ALLOW, rule.behavior());
        assertEquals("suggested", rule.source());
    }

    @Test
    void defaultCallAsyncOnPassthroughThrows() {
        ToolBase tool = new PassthroughTool();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("id", "passthrough_tool", Map.of()))
                        .input(Map.of())
                        .build();
        StepVerifier.create(tool.callAsync(param))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }

    @Test
    void defaultCallAsyncOnExternalToolThrows() {
        ToolBase tool = new ExternalTool();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("id", "external_tool", Map.of()))
                        .input(Map.of())
                        .build();
        StepVerifier.create(tool.callAsync(param))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void overriddenCallAsyncIsRespected() {
        ToolBase tool =
                new PassthroughTool() {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(
                                ToolResultBlock.of(
                                        "id",
                                        "passthrough_tool",
                                        TextBlock.builder().text("result").build()));
                    }
                };
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("id", "passthrough_tool", Map.of()))
                        .input(Map.of())
                        .build();
        StepVerifier.create(tool.callAsync(param))
                .expectNextMatches(result -> "id".equals(result.getId()))
                .verifyComplete();
    }

    @Test
    void isDangerousPathDetectsRcFile() {
        DangerousPathProbe probe = new DangerousPathProbe();
        assertTrue(probe.check("/home/user/.bashrc"));
        assertTrue(probe.check("/Users/ken/.zshrc"));
        assertTrue(probe.check("/home/user/.gitconfig"));
    }

    @Test
    void isDangerousPathDetectsSensitiveDirectorySegment() {
        DangerousPathProbe probe = new DangerousPathProbe();
        assertTrue(probe.check("/home/user/project/.git/config"));
        assertTrue(probe.check("/home/user/.ssh/known_hosts"));
        assertTrue(probe.check("/work/.idea/workspace.xml"));
    }

    @Test
    void isDangerousPathSkipsOrdinaryFiles() {
        DangerousPathProbe probe = new DangerousPathProbe();
        assertFalse(probe.check("/home/user/project/main.java"));
        assertFalse(probe.check("/tmp/foo.txt"));
    }

    @Test
    void isDangerousPathIsCaseInsensitive() {
        DangerousPathProbe probe = new DangerousPathProbe();
        assertTrue(probe.check("/home/user/.BASHRC"));
        assertTrue(probe.check("/home/user/.GIT/config"));
    }

    @Test
    void isDangerousPathHandlesTildeExpansion() {
        DangerousPathProbe probe = new DangerousPathProbe();
        assertTrue(probe.check("~/.gitconfig"));
    }

    @Test
    void isDangerousPathHandlesNullAndBlank() {
        DangerousPathProbe probe = new DangerousPathProbe();
        assertFalse(probe.check(null));
        assertFalse(probe.check(""));
        assertFalse(probe.check("   "));
    }

    // --- Title-related tests ---

    @Test
    @DisplayName("Builder should accept and return custom title")
    void testBuilderWithTitle() {
        ToolBase tool =
                new ToolBase(
                        ToolBase.builder()
                                .name("titled_tool")
                                .title("My Custom Title")
                                .description("desc")
                                .inputSchema(emptySchema())) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState context) {
                        return Mono.just(PermissionDecision.allow(""));
                    }
                };

        assertEquals("titled_tool", tool.getName());
        assertEquals("My Custom Title", tool.getTitle());
    }

    @Test
    @DisplayName("Builder should handle null title")
    void testBuilderWithNullTitle() {
        ToolBase tool =
                new ToolBase(
                        ToolBase.builder()
                                .name("no_title_tool")
                                .description("desc")
                                .inputSchema(emptySchema())) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState context) {
                        return Mono.just(PermissionDecision.allow(""));
                    }
                };

        assertNull(tool.getTitle());
    }

    @Test
    @DisplayName("getTitle returns builder-set title (final getter)")
    void testGetTitleReturnsBuilderTitle() {
        ToolBase tool =
                new ToolBase(
                        ToolBase.builder()
                                .name("fallback_tool")
                                .title("Custom Fallback")
                                .description("desc")
                                .inputSchema(emptySchema())) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> toolInput, PermissionContextState context) {
                        return Mono.just(PermissionDecision.allow(""));
                    }
                };

        assertEquals("Custom Fallback", tool.getTitle());
    }
}
