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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.Tool;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamConflictException;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamTask;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

class TeamToolTest {
    private final TeamClient client = mock(TeamClient.class, invocation -> Mono.empty());
    private final TeamTool tool = new TeamTool(client, context(List.of()));

    private static TeamContext context(List<String> allowed) {
        return new TeamContext("team", "ns", "objective", "lead", true, List.of(), allowed);
    }

    static Stream<Method> aliases() {
        return Arrays.stream(TeamTool.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class) && !m.getName().equals("team"));
    }

    private static Object[] arguments(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .map(
                        type -> {
                            if (type == Long.class) return (Object) 1L;
                            if (type == Integer.class) return (Object) 10;
                            return (Object) "value";
                        })
                .toArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aliases")
    void everyAliasReportsSuccess(Method method) throws Exception {
        assertText(
                (ToolResultBlock) method.invoke(tool, arguments(method)), ToolResultState.SUCCESS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aliases")
    void everyAliasPreservesPermissionFailure(Method method) throws Exception {
        TeamTool restricted = new TeamTool(client, context(List.of("unavailable")));
        assertTrue(
                assertText(
                                (ToolResultBlock) method.invoke(restricted, arguments(method)),
                                ToolResultState.ERROR)
                        .contains("not allowed"));
        verifyNoInteractions(client);
    }

    @Test
    void failedTaskIsSuccessfulBoardData() {
        TeamTask failed =
                new TeamTask(
                        "id",
                        "team",
                        "ns",
                        "Error: example",
                        "",
                        TeamTask.FAILED,
                        "worker",
                        List.of(),
                        "[ERROR] task failed",
                        1L);
        when(client.listTasks("ns", "team")).thenReturn(Mono.just(List.of(failed)));
        String text = assertText(tool.listTasks(), ToolResultState.SUCCESS);
        assertTrue(text.startsWith("[{"));
        assertTrue(text.contains("[ERROR] task failed"));
    }

    @Test
    void conflictIsAnErrorAndKeepsJsonPayload() {
        when(client.claimTask("ns", "team", "id", "lead", 1L))
                .thenReturn(Mono.error(new TeamConflictException("stale version")));
        assertEquals(
                "{\"error\":\"conflict: stale version\"}",
                assertText(tool.claimTask("id", 1L), ToolResultState.ERROR));
    }

    @Test
    void serializationFailureIsAnError() {
        TeamTask broken = mock(TeamTask.class);
        when(broken.subject()).thenThrow(new IllegalStateException("cannot read subject"));
        when(client.listTasks("ns", "team")).thenReturn(Mono.just(List.of(broken)));
        assertEquals(
                "{\"error\":\"json encode failed\"}",
                assertText(tool.listTasks(), ToolResultState.ERROR));
    }

    @Test
    void clientFailureAndMissingArgumentsAreErrors() {
        when(client.listTasks("ns", "team"))
                .thenReturn(Mono.error(new IllegalStateException("offline")));
        assertEquals(
                "{\"error\":\"offline\"}", assertText(tool.listTasks(), ToolResultState.ERROR));
        assertTrue(
                assertText(tool.assignTask(null, "worker", 1L), ToolResultState.ERROR)
                        .contains("task_id is required"));
    }

    @Test
    void missingAndUnknownActionsAreErrors() {
        for (String action : Arrays.asList(null, "", "unknown")) {
            assertText(
                    tool.team(
                            action, null, null, null, null, null, null, null, null, null, null,
                            null, null, null),
                    ToolResultState.ERROR);
        }
        verifyNoInteractions(client);
    }
}
