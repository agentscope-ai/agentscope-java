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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        var store = new WorkspaceSessionLogStore(manager);
        var log = store.open(new SessionKey(null, "agent", "session"), RuntimeContext.empty());
        var writer = log.acquire("keyword-fixture", Duration.ofMinutes(2));
        var events = new ArrayList<SessionEvent>();
        var contents = List.of(CONTENT, "only-first", "only-second");
        for (int index = 0; index < contents.size(); index++) {
            MsgRole role = index == 2 ? MsgRole.ASSISTANT : MsgRole.USER;
            Msg message =
                    Msg.builder()
                            .role(role)
                            .content(TextBlock.builder().text(contents.get(index)).build())
                            .build();
            events.add(
                    new SessionEvent(
                            1,
                            "keyword-event-" + index,
                            index + 1,
                            1,
                            "message/" + role.name().toLowerCase(Locale.ROOT),
                            "run",
                            "turn",
                            true,
                            JsonUtils.getJsonCodec().toJson(Map.of("message", message))));
        }
        log.commit(writer, "keyword-fixture", 0, events);
        log.release(writer);
    }

    private List<String> search(String query, String mode) {
        return List.of(
                text(memory.memorySearch(null, query, mode)),
                text(sessions.sessionSearch(null, query, "agent", 10, mode)));
    }

    @Test
    void oldJavaApiAndOmittedModeKeepPhraseBehavior() {
        assertEquals(
                text(memory.memorySearch(null, "部署 蓝鲸")),
                text(memory.memorySearch(null, "部署 蓝鲸", "phrase")));
        assertEquals(
                text(sessions.sessionSearch(null, "部署 蓝鲸", null, 10)),
                text(sessions.sessionSearch(null, "部署 蓝鲸", null, 10, "phrase")));
        search("部署 蓝鲸", null).forEach(result -> assertFalse(found(result), result));
        search("蓝鲸", null).forEach(result -> assertTrue(found(result), result));
        search(" 蓝鲸 ", "phrase").forEach(result -> assertFalse(found(result), result));
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
        search(query, mode).forEach(result -> assertEquals(found, found(result), result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "any"})
    void supportsWhitespaceAndDuplicateTerms(String mode) {
        search(" \t蓝鲸\n部署\u3000蓝鲸\u00a0 ", mode).forEach(result -> assertMatchCount(1, result));
    }

    @Test
    void allDoesNotCombineDifferentRecords() {
        search("only-first only-second", "all")
                .forEach(result -> assertFalse(found(result), result));
        search("only-first only-second", "any").forEach(result -> assertMatchCount(2, result));
        assertTrue(
                text(memory.memorySearch(null, "ledger-first ledger-second", "all"))
                        .contains("memory/2026-09-08.md#1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ALL", "unknown"})
    void invalidModeReturnsAnError(String mode) {
        assertEquals(ToolResultState.ERROR, memory.memorySearch(null, "蓝鲸", mode).getState());
        assertEquals(
                ToolResultState.ERROR,
                sessions.sessionSearch(null, "蓝鲸", "agent", 10, mode).getState());
        search("蓝鲸", mode)
                .forEach(result -> assertTrue(result.startsWith("Error: matchMode"), result));
    }

    @Test
    void emptyQueriesNeverMatchEverything() {
        for (String query : new String[] {null, "", " \t\n", "\u00a0"}) {
            search(query, "all").forEach(result -> assertFalse(found(result), result));
        }
    }

    @Test
    void sessionFilterAndLimitArePreserved() {
        assertMatchCount(
                1, text(sessions.sessionSearch(null, "only-first only-second", null, 1, "any")));
        assertEquals("[]", text(sessions.sessionSearch(null, "蓝鲸", "different-agent", 10, "all")));
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
            assertTrue(found(defaultResult), name + ": " + defaultResult);
            assertFalse(found(invoke(toolkit, name, Map.of("query", "部署 蓝鲸"))));
            assertTrue(found(invoke(toolkit, name, Map.of("query", "部署 蓝鲸", "matchMode", "all"))));
            assertTrue(
                    invoke(toolkit, name, Map.of("query", "蓝鲸", "matchMode", "invalid"))
                            .startsWith("Error:"));
        }
    }

    private boolean found(String result) {
        return result.startsWith("[")
                ? !JsonUtils.getJsonCodec().fromJson(result, List.class).isEmpty()
                : result.startsWith("Found");
    }

    private void assertMatchCount(int expected, String result) {
        if (result.startsWith("[")) {
            assertEquals(
                    expected, JsonUtils.getJsonCodec().fromJson(result, List.class).size(), result);
        } else {
            assertTrue(result.startsWith("Found " + expected + " matches"), result);
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
        assertEquals(
                "invalid".equals(input.get("matchMode"))
                        ? ToolResultState.ERROR
                        : ToolResultState.SUCCESS,
                result.getState());
        return text(result);
    }

    private static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
