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

    private RuntimeContext userContext() {
        return RuntimeContext.builder().userId("alice").sessionId("s1").build();
    }

    private static String textOf(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    @DisplayName("Relative path is normalized into the sandbox and executed")
    void relativePathIsNormalizedIntoSandbox() {
        WriteFileTool tool = new WriteFileTool();
        ToolResultBlock result = write(toolkit(tool), "notes/a.txt", userContext());

        assertTrue(
                result.getState() != ToolResultState.DENIED
                        && result.getState() != ToolResultState.ERROR,
                "Tool should execute normally: " + textOf(result));
        Path expected = sandboxRoot.resolve("alice").resolve("notes").resolve("a.txt").normalize();
        assertEquals(
                expected.toString(), tool.writtenPath.get(), "Tool must receive the resolved path");
        assertTrue(Files.exists(expected), "Side effect must land inside the sandbox");
    }

    @Test
    @DisplayName("Parent-directory escape is denied without side effects")
    void escapeAttemptIsDenied() {
        WriteFileTool tool = new WriteFileTool();
        ToolResultBlock result = write(toolkit(tool), "../../evil.txt", userContext());

        assertEquals(ToolResultState.DENIED, result.getState());
        assertTrue(
                textOf(result).startsWith("PathSandbox:"),
                "Message should carry the interceptor identity");
        assertNull(tool.writtenPath.get(), "Tool must not run");
        Path escaped = sandboxRoot.resolve("alice").resolve("../../evil.txt").normalize();
        assertFalse(Files.exists(escaped), "No file may be created outside the sandbox");
    }

    @Test
    @DisplayName("Absolute path outside the sandbox is denied")
    void absolutePathOutsideSandboxIsDenied() {
        WriteFileTool tool = new WriteFileTool();
        String outside = sandboxRoot.getParent().resolve("absolute-evil.txt").toString();
        ToolResultBlock result = write(toolkit(tool), outside, userContext());

        assertEquals(ToolResultState.DENIED, result.getState());
        assertNull(tool.writtenPath.get());
        assertFalse(Files.exists(Path.of(outside)), "No file may be created at the absolute path");
    }

    @Test
    @DisplayName("Missing identity fails closed")
    void missingIdentityFailsClosed() {
        WriteFileTool tool = new WriteFileTool();
        // No RuntimeContext at all
        ToolResultBlock result = write(toolkit(tool), "a.txt", null);

        assertEquals(ToolResultState.DENIED, result.getState());
        assertTrue(textOf(result).contains("no authenticated user context"));
        assertNull(tool.writtenPath.get());
    }

    @Test
    @DisplayName("Blank userId fails closed")
    void blankUserIdFailsClosed() {
        WriteFileTool tool = new WriteFileTool();
        RuntimeContext blank = RuntimeContext.builder().userId("  ").sessionId("s1").build();
        ToolResultBlock result = write(toolkit(tool), "a.txt", blank);

        assertEquals(ToolResultState.DENIED, result.getState());
        assertNull(tool.writtenPath.get());
    }

    @Test
    @DisplayName("Path-shaped userId cannot relocate the sandbox root")
    void pathShapedUserIdIsDenied() {
        WriteFileTool tool = new WriteFileTool();
        RuntimeContext attacker =
                RuntimeContext.builder().userId("../other-tenant").sessionId("s1").build();
        ToolResultBlock result = write(toolkit(tool), "a.txt", attacker);

        assertEquals(ToolResultState.DENIED, result.getState());
        assertNull(tool.writtenPath.get());
        assertFalse(Files.exists(sandboxRoot.resolveSibling("other-tenant").resolve("a.txt")));
    }

    @Test
    @DisplayName("New-file write through a symlinked directory cannot escape")
    void symlinkedDirectoryEscapeIsDenied() throws IOException {
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

        WriteFileTool tool = new WriteFileTool();
        // Lexically inside the sandbox, but "shared" resolves outside: the new-file target's
        // nearest existing ancestor (the link itself) must fail the real-path re-check
        ToolResultBlock result = write(toolkit(tool), "shared/new.txt", userContext());

        assertEquals(ToolResultState.DENIED, result.getState());
        assertNull(tool.writtenPath.get());
        assertFalse(Files.exists(outsideDir.resolve("new.txt")), "No file may escape via symlink");
    }

    @Test
    @DisplayName("Untargeted tools pass through untouched")
    void untargetedToolsPassThrough() {
        AtomicReference<String> passthrough = new AtomicReference<>("not-called");
        Toolkit toolkit =
                new Toolkit(
                        ToolkitConfig.builder()
                                .parallel(false)
                                .interceptor(new PathSandboxInterceptor(sandboxRoot::resolve))
                                .build());
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
        ToolUseBlock toolCall =
                ToolUseBlock.builder()
                        .id("call-probe")
                        .name("status_probe")
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build();
        ToolResultBlock result =
                toolkit.callTool(ToolCallParam.builder().toolUseBlock(toolCall).build())
                        .block(TIMEOUT);

        assertEquals("ok", textOf(result));
        assertEquals("called", passthrough.get(), "Untargeted tool must run without guard logic");
    }
}
