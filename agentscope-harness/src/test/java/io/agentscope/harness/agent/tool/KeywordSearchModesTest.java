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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KeywordSearchModesTest {
    @TempDir Path workspace;
    private MemorySearchTool memory;
    private SessionSearchTool sessions;
    private static final String CONTENT = "部署决定：使用蓝鲸方案，端口 9580。 Alpha middle BETA [x].* C++";

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(workspace);
        memory = new MemorySearchTool(manager);
        sessions = new SessionSearchTool(manager);
        Files.writeString(workspace.resolve("MEMORY.md"), CONTENT + "\nonly-first\nonly-second\n");
        Files.createDirectories(workspace.resolve("memory"));
        Files.writeString(
                workspace.resolve("memory/2026-09-08.md"), "ledger-first separate ledger-second\n");
        Path path = manager.resolveSessionContextFile(RuntimeContext.empty(), "agent", "session");
        Files.createDirectories(path.getParent());
        SessionTree tree = new SessionTree(path, workspace, null);
        tree.load();
        tree.append(new SessionEntry.MessageEntry(null, "user", CONTENT));
        tree.append(new SessionEntry.MessageEntry(null, "user", "only-first"));
        tree.append(new SessionEntry.MessageEntry(null, "assistant", "only-second"));
        tree.flush();
    }

    private List<String> search(String query, String mode) {
        return List.of(
                memory.memorySearch(null, query, mode),
                sessions.sessionSearch(null, query, "agent", 10, mode));
    }

    @Test
    void oldJavaApiAndOmittedModeKeepPhraseBehavior() {
        assertEquals(
                memory.memorySearch(null, "部署 蓝鲸"), memory.memorySearch(null, "部署 蓝鲸", "phrase"));
        assertEquals(
                sessions.sessionSearch(null, "部署 蓝鲸", null, 10),
                sessions.sessionSearch(null, "部署 蓝鲸", null, 10, "phrase"));
        search("部署 蓝鲸", null).forEach(result -> assertTrue(result.startsWith("No match")));
        search("蓝鲸", null).forEach(result -> assertTrue(result.startsWith("Found")));
        search(" 蓝鲸 ", "phrase").forEach(result -> assertTrue(result.startsWith("No match")));
    }

    @ParameterizedTest
    @CsvSource({
        "部署 蓝鲸,all,true",
        "蓝鲸 部署,all,true",
        "部署 不存在,all,false",
        "部署 不存在,any,true",
        "不存在 也不存在,any,false",
        "alpha beta,all,true",
        "[x].* C++,all,true",
        "[x].+ C++,all,false"
    })
    void matchesLiteralTermsWithinOneRecord(String query, String mode, boolean found) {
        search(query, mode)
                .forEach(result -> assertEquals(found, result.startsWith("Found"), result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "any"})
    void supportsWhitespaceAndDuplicateTerms(String mode) {
        search(" \t蓝鲸\n部署\u3000蓝鲸\u00a0 ", mode)
                .forEach(result -> assertTrue(result.startsWith("Found 1 matches"), result));
    }

    @Test
    void allDoesNotCombineDifferentRecords() {
        search("only-first only-second", "all")
                .forEach(result -> assertTrue(result.startsWith("No match"), result));
        search("only-first only-second", "any")
                .forEach(result -> assertTrue(result.startsWith("Found 2 matches"), result));
        assertTrue(
                memory.memorySearch(null, "ledger-first ledger-second", "all")
                        .contains("memory/2026-09-08.md#1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ALL", "unknown"})
    void invalidModeReturnsAnError(String mode) {
        search("蓝鲸", mode)
                .forEach(result -> assertTrue(result.startsWith("Error: matchMode"), result));
    }

    @Test
    void emptyQueriesNeverMatchEverything() {
        for (String query : new String[] {null, "", " \t\n", "\u00a0"}) {
            search(query, "all").forEach(result -> assertFalse(result.startsWith("Found"), result));
        }
    }

    @Test
    void sessionFilterAndLimitArePreserved() {
        assertTrue(
                sessions.sessionSearch(null, "only-first only-second", null, 1, "any")
                        .startsWith("Found 1 matches"));
        assertTrue(
                sessions.sessionSearch(null, "蓝鲸", "different-agent", 10, "all")
                        .startsWith("No matches"));
    }

    @Test
    void schemaAndReflectiveInvocationSupportBothOldAndNewInputs() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(memory);
        toolkit.registerTool(sessions);
        for (String name : List.of("memory_search", "session_search")) {
            List<ToolSchema> schemas =
                    toolkit.getToolSchemas().stream()
                            .filter(s -> s.getName().equals(name))
                            .toList();
            assertEquals(1, schemas.size());
            Map<String, Object> parameters = schemas.get(0).getParameters();
            assertTrue(((Map<?, ?>) parameters.get("properties")).containsKey("matchMode"));
            assertFalse(((List<?>) parameters.get("required")).contains("matchMode"));
            String defaultResult = invoke(toolkit, name, Map.of("query", "蓝鲸"));
            assertTrue(defaultResult.startsWith("Found"), name + ": " + defaultResult);
            assertTrue(invoke(toolkit, name, Map.of("query", "部署 蓝鲸")).startsWith("No match"));
            assertTrue(
                    invoke(toolkit, name, Map.of("query", "部署 蓝鲸", "matchMode", "all"))
                            .startsWith("Found"));
            assertTrue(
                    invoke(toolkit, name, Map.of("query", "蓝鲸", "matchMode", "invalid"))
                            .startsWith("Error:"));
        }
    }

    private String invoke(Toolkit toolkit, String name, Map<String, Object> input) {
        ToolResultBlock result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("test-call")
                                                        .name(name)
                                                        .input(input)
                                                        .content(
                                                                JsonUtils.getJsonCodec()
                                                                        .toJson(input))
                                                        .build())
                                        .input(input)
                                        .runtimeContext(RuntimeContext.empty())
                                        .build())
                        .block(Duration.ofSeconds(10));
        assertNotNull(result);
        String json =
                result.getOutput().stream()
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .reduce("", String::concat);
        // Reflective tools serialize String return values as JSON strings.
        return JsonUtils.getJsonCodec().fromJson(json, String.class);
    }
}
