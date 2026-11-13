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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionRecorderCancellationTest {
    private SessionLog log;
    private SessionRecorder recorder;
    private AgentState state;

    @BeforeEach
    void setUp() {
        var context = RuntimeContext.builder().userId("u").sessionId("s").build();
        log = new InMemorySessionLogStore().open(new SessionKey("u", "agent", "s"), context);
        state =
                AgentState.builder()
                        .userId("u")
                        .sessionId("s")
                        .context(
                                List.of(
                                        AssistantMessage.builder()
                                                .content(List.of(call("fast"), call("slow")))
                                                .build()))
                        .build();
        recorder = new SessionRecorder(log, null);
        recorder.start(List.of(), state).block(Duration.ofSeconds(5));
        recorder.checkpointNow(state, "before_tool");
        recorder.recordNow("tool/dispatch", Map.of("toolCallId", "fast"));
        recorder.recordNow("tool/dispatch", Map.of("toolCallId", "slow"));
    }

    @AfterEach
    void tearDown() {
        recorder.close();
    }

    @ParameterizedTest
    @EnumSource(
            value = ToolResultState.class,
            names = {"SUCCESS", "ERROR", "DENIED"})
    void cancellationPreservesCompletedResultAndLeavesUnknownSiblingUnresolved(
            ToolResultState resultState) {
        var result =
                ToolResultBlock.builder()
                        .id("fast")
                        .name("fast")
                        .state(resultState)
                        .output(result("receipt").getOutput())
                        .metadata(Map.of("receipt", 42))
                        .build();
        complete(result);
        // A later state checkpoint need not contain the batch-local result yet.
        state.getToolContext().addDiscoveredTools(List.of("discovered"));
        recorder.checkpointNow(state, "before_tool");
        completeUnknownSibling();
        String liveState = state.toJson();

        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));

        var projection = SessionProjection.read(log);
        assertEquals(Set.of("slow"), projection.uncertainToolCalls());
        var results = results(projection.restore());
        assertEquals(1, results.size());
        assertEquals(resultState, results.get(0).getState());
        assertEquals(result.getOutput(), results.get(0).getOutput());
        assertEquals(result.getMetadata(), results.get(0).getMetadata());
        assertEquals("fast", results.get(0).getId());
        assertEquals(
                List.of("discovered"), projection.restore().getToolContext().getDiscoveredTools());
        assertEquals(liveState, state.toJson(), "only the recovery snapshot is supplemented");
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "cancelled"})
    void canonicalRewrittenResultWinsWithoutDuplication(String status) {
        complete(result("raw action output"));
        var rewritten = result("post-acting replacement").withState(ToolResultState.ERROR);
        state.contextMutable().add(new ToolResultMessage(rewritten));

        recorder.finish(state, status).block(Duration.ofSeconds(5));

        var results = results(SessionProjection.read(log).restore());
        assertEquals(1, results.size());
        assertEquals(rewritten.getOutput(), results.get(0).getOutput());
        assertEquals(ToolResultState.ERROR, results.get(0).getState());
    }

    @Test
    void cancellationDoesNotResurrectCallsRemovedFromContext() {
        complete(result("old output"));
        state.contextMutable().clear();
        state.contextMutable().add(new UserMessage("replacement after compaction"));
        recorder.checkpointNow(state, "compaction");

        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));

        var restored = SessionProjection.read(log).restore();
        assertTrue(results(restored).isEmpty());
        assertEquals("replacement after compaction", restored.getContext().get(0).getTextContent());
    }

    @Test
    void cancellationDoesNotCompleteAnUnrelatedPendingCall() {
        complete(result("old output"));
        state.contextMutable().clear();
        state.contextMutable()
                .add(AssistantMessage.builder().content(List.of(call("new"))).build());

        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));

        assertTrue(results(SessionProjection.read(log).restore()).isEmpty());
    }

    @Test
    void cancellationDoesNotTurnSuspensionIntoACompletedToolResult() {
        complete(ToolResultBlock.suspended(call("fast")));
        state.getToolContext().addDiscoveredTools(List.of("discovered"));

        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));

        assertTrue(results(SessionProjection.read(log).restore()).isEmpty());
    }

    @Test
    void cancellationWithNoCompletedActionKeepsUnknownToolsUnresolved() {
        completeUnknownSibling();
        state.getToolContext().addDiscoveredTools(List.of("discovered"));

        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));

        var projection = SessionProjection.read(log);
        assertEquals(Set.of("fast", "slow"), projection.uncertainToolCalls());
        assertTrue(results(projection.restore()).isEmpty());
    }

    @Test
    void cancellationAcceptsLegacyResultsWithoutPolymorphicDiscriminator() {
        var payload =
                new LinkedHashMap<Object, Object>(
                        JsonUtils.getJsonCodec()
                                .fromJson(
                                        JsonUtils.getJsonCodec().toJson(result("legacy")),
                                        Map.class));
        payload.remove("type");
        recorder.recordNow("action/end", Map.of("toolCallId", "fast", "result", payload));
        state.getToolContext().addDiscoveredTools(List.of("discovered"));

        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));

        var results = results(SessionProjection.read(log).restore());
        assertEquals(1, results.size());
        assertEquals(result("legacy").getOutput(), results.get(0).getOutput());
    }

    @Test
    void administrativeReplacementAfterCancellationDoesNotResurrectCompletedActions() {
        complete(result("old output"));
        // Here both calls are known, so an administrative replacement is allowed.
        recorder.recordNow(
                "action/end",
                Map.of(
                        "toolCallId",
                        "slow",
                        "result",
                        ToolResultBlock.success("done").withIdAndName("slow", "slow")));
        recorder.finish(state, "cancelled").block(Duration.ofSeconds(5));
        assertEquals(2, results(SessionProjection.read(log).restore()).size());

        SessionStateAccess.update(
                log,
                () -> state,
                "explicit rollback",
                restored -> restored.contextMutable().clear());
        var rolledBack = SessionProjection.read(log).restore();
        assertTrue(results(rolledBack).isEmpty());
        assertTrue(rolledBack.getContext().isEmpty());
        var later = new SessionRecorder(log, null);
        try {
            later.start(List.of(), rolledBack).block(Duration.ofSeconds(5));
            later.finish(rolledBack, "cancelled").block(Duration.ofSeconds(5));
            assertTrue(results(SessionProjection.read(log).restore()).isEmpty());
        } finally {
            later.close();
        }
    }

    private void complete(ToolResultBlock result) {
        recorder.recordNow("action/end", Map.of("toolCallId", "fast", "result", result));
    }

    private void completeUnknownSibling() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("toolCallId", "slow");
        payload.put("result", null);
        recorder.recordNow("action/end", payload);
    }

    private static ToolUseBlock call(String id) {
        return ToolUseBlock.builder().id(id).name(id).input(Map.of()).build();
    }

    private static ToolResultBlock result(String text) {
        return ToolResultBlock.success(text).withIdAndName("fast", "fast");
    }

    private static List<ToolResultBlock> results(AgentState state) {
        return state.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
    }
}
