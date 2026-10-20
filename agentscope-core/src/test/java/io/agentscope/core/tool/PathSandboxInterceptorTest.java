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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * Behavior test: a per-user path sandbox interceptor guarding a custom {@code write_text_file}
 * tool (no built-in baseDir semantics), proving denial without side effects, in-sandbox
 * normalization, and fail-closed identity handling.
 *
 * @author shanhongyu
 */
@Tag("unit")
@DisplayName("PathSandboxInterceptor Behavior Tests")
class PathSandboxInterceptorTest {

    @TempDir Path sandboxRoot;

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Minimal file-writing tool recording the path it actually received. */
    private static final class WriteFileTool implements AgentTool {
        final AtomicReference<String> writtenPath = new AtomicReference<>();

        @Override
        public String getName() {
            return "write_text_file";
        }

        @Override
        public String getDescription() {
            return "Writes text to a file";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                            "file_path", Map.of("type", "string"),
                            "content", Map.of("type", "string")));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(
                    () -> {
                        Path target = Path.of((String) param.getInput().get("file_path"));
                        Files.createDirectories(
                                target.getParent() != null ? target.getParent() : Path.of("."));
                        Files.writeString(target, (String) param.getInput().get("content"));
                        writtenPath.set(target.toString());
                        return ToolResultBlock.text("wrote " + target.getFileName());
                    });
        }
    }

    private Toolkit toolkit(WriteFileTool tool) {
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(new PathSandboxInterceptor(sandboxRoot::resolve))
                                .build());
        toolkit.registerTool(tool);
        return toolkit;
    }

    private ToolResultBlock write(Toolkit toolkit, String filePath, RuntimeContext context) {
        Map<String, Object> input = Map.of("file_path", filePath, "content", "data");
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-write")
                        .name("write_text_file")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolCallParam.Builder param = ToolCallParam.builder().toolUseBlock(toolCall);
        if (context != null) {
            param.runtimeContext(context);
        }
        return toolkit.callTool(param.build()).block(TIMEOUT);
    }

    private RuntimeContext userContext(String userId) {
        return RuntimeContext.builder().userId(userId).sessionId("s1").build();
    }

    private static String textOf(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    @DisplayName("Relative paths are normalized into the sandbox; untargeted tools pass through")
    void normalizesRelativePathsAndPassesThroughUntargetedTools() {
        WriteFileTool tool = new WriteFileTool();
        ToolResultBlock result = write(toolkit(tool), "notes/a.txt", userContext("alice"));

        assertTrue(
                result.getState() != ToolResultState.DENIED
                        && result.getState() != ToolResultState.ERROR,
                "Tool should execute normally: " + textOf(result));
        Path expected = sandboxRoot.resolve("alice").resolve("notes").resolve("a.txt").normalize();
        assertEquals(
                expected.toString(), tool.writtenPath.get(), "Tool must receive the resolved path");
        assertTrue(Files.exists(expected), "Side effect must land inside the sandbox");

        // Untargeted tool names bypass the guard entirely
        AtomicReference<String> passthrough = new AtomicReference<>("not-called");
        Toolkit toolkit = toolkit(tool);
        toolkit.registerTool(
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "status_probe";
                    }

                    @Override
                    public String getDescription() {
                        return "Unrelated tool";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        passthrough.set("called");
                        return Mono.just(ToolResultBlock.text("ok"));
                    }
                });
        Map<String, Object> input = Map.of();
        ToolUseBlock probe =
                ToolUseBlock.builder()
                        .id("call-probe")
                        .name("status_probe")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolResultBlock probeResult =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(probe).build())
                        .block(TIMEOUT);
        assertEquals("ok", textOf(probeResult));
        assertEquals("called", passthrough.get(), "Untargeted tool must run without guard logic");
    }

    @Test
    @DisplayName("Escape attempts (parent traversal, absolute, symlinked directory) are denied")
    void escapeAttemptsAreDenied() throws IOException {
        // Parent-directory traversal
        WriteFileTool tool = new WriteFileTool();
        ToolResultBlock escaped = write(toolkit(tool), "../../evil.txt", userContext("alice"));
        assertEquals(ToolResultState.DENIED, escaped.getState());
        assertTrue(
                textOf(escaped).startsWith("PathSandbox:"),
                "Message should carry the interceptor identity");
        assertNull(tool.writtenPath.get(), "Tool must not run");
        assertFalse(
                Files.exists(sandboxRoot.resolve("alice").resolve("../../evil.txt").normalize()),
                "No file may be created outside the sandbox");

        // Absolute path outside the sandbox
        String outside = sandboxRoot.getParent().resolve("absolute-evil.txt").toString();
        ToolResultBlock absolute = write(toolkit(tool), outside, userContext("alice"));
        assertEquals(ToolResultState.DENIED, absolute.getState());
        assertNull(tool.writtenPath.get());
        assertFalse(Files.exists(Path.of(outside)), "No file may be created at the absolute path");

        // New-file write through a symlinked directory (real-path re-check on ancestors)
        Path outsideDir = sandboxRoot.resolveSibling("outside");
        Path link = sandboxRoot.resolve("alice").resolve("shared");
        Files.createDirectories(outsideDir);
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Symlink creation needs privileges on some platforms; capability, not flake
            Assumptions.abort("symbolic links not supported here: " + unsupported.getMessage());
        }
        ToolResultBlock viaSymlink = write(toolkit(tool), "shared/new.txt", userContext("alice"));
        assertEquals(ToolResultState.DENIED, viaSymlink.getState());
        assertNull(tool.writtenPath.get());
        assertFalse(Files.exists(outsideDir.resolve("new.txt")), "No file may escape via symlink");
    }

    @Test
    @DisplayName("Missing, blank, and path-shaped identities all fail closed")
    void malformedIdentityFailsClosed() {
        // No RuntimeContext at all
        WriteFileTool tool = new WriteFileTool();
        ToolResultBlock noContext = write(toolkit(tool), "a.txt", null);
        assertEquals(ToolResultState.DENIED, noContext.getState());
        assertTrue(textOf(noContext).contains("no authenticated user context"));

        // Blank userId
        ToolResultBlock blank = write(toolkit(tool), "a.txt", userContext("  "));
        assertEquals(ToolResultState.DENIED, blank.getState());

        // Path-shaped userId must not relocate the sandbox root
        ToolResultBlock attacker = write(toolkit(tool), "a.txt", userContext("../other-tenant"));
        assertEquals(ToolResultState.DENIED, attacker.getState());
        assertNull(tool.writtenPath.get());
        assertFalse(
                Files.exists(sandboxRoot.resolveSibling("other-tenant").resolve("a.txt")),
                "No file may be created in another tenant's root");
    }
}
