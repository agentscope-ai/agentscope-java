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
package io.agentscope.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ASK_USER event/result wire format: full-arg constructors, getters, and
 * Jackson round-trips (used by the harness remote-event codec), plus edge branches of the answer
 * formatter that the end-to-end tests do not reach.
 */
class AskUserEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ToolUseBlock toolUse() {
        return ToolUseBlock.builder()
                .id("call-1")
                .name("ask_user")
                .input(Map.of("questions", List.of("q")))
                .build();
    }

    @Test
    void requireUserAskEvent_fullConstructorAndGetters() {
        // Two-arg convenience constructor (used by the agent runtime).
        RequireUserAskEvent simple = new RequireUserAskEvent("r1", List.of(toolUse()));
        assertEquals(AgentEventType.REQUIRE_USER_ASK, simple.getType());
        assertEquals("r1", simple.getReplyId());
        assertEquals(1, simple.getToolCalls().size());
        assertEquals("call-1", simple.getToolCalls().get(0).getId());

        // Full Jackson constructor (used by the remote codec when decoding).
        RequireUserAskEvent full =
                new RequireUserAskEvent("id-1", "2026-01-01T00:00:00Z", "r2", List.of(toolUse()));
        assertEquals("id-1", full.getId());
        assertEquals("2026-01-01T00:00:00Z", full.getCreatedAt());
        assertEquals("r2", full.getReplyId());
        assertEquals(AgentEventType.REQUIRE_USER_ASK, full.getType());

        // Null-safe payload.
        RequireUserAskEvent empty = new RequireUserAskEvent("r3", null);
        assertNotNull(empty.getToolCalls());
        assertTrue(empty.getToolCalls().isEmpty());
    }

    @Test
    void requireUserAskEvent_jsonRoundTrip() throws Exception {
        RequireUserAskEvent original = new RequireUserAskEvent("r1", List.of(toolUse()));
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("REQUIRE_USER_ASK"), json);

        AgentEvent back = mapper.readValue(json, AgentEvent.class);
        assertInstanceOf(RequireUserAskEvent.class, back);
        RequireUserAskEvent decoded = (RequireUserAskEvent) back;
        assertEquals(AgentEventType.REQUIRE_USER_ASK, decoded.getType());
        assertEquals("r1", decoded.getReplyId());
        assertEquals(1, decoded.getToolCalls().size());
        assertEquals("call-1", decoded.getToolCalls().get(0).getId());
        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getCreatedAt(), decoded.getCreatedAt());
    }

    @Test
    void userAskResultEvent_fullConstructorAndGetters() {
        AskUserResult answer = new AskUserResult("call-1", Map.of("q_1", "premium"));

        UserAskResultEvent simple = new UserAskResultEvent("r1", List.of(answer));
        assertEquals(AgentEventType.USER_ASK_RESULT, simple.getType());
        assertEquals("r1", simple.getReplyId());
        assertEquals(1, simple.getAskUserResults().size());
        assertEquals("call-1", simple.getAskUserResults().get(0).getToolCallId());

        UserAskResultEvent full =
                new UserAskResultEvent("id-2", "2026-01-02T00:00:00Z", "r2", List.of(answer));
        assertEquals("id-2", full.getId());
        assertEquals("2026-01-02T00:00:00Z", full.getCreatedAt());
        assertEquals("r2", full.getReplyId());
        assertEquals(AgentEventType.USER_ASK_RESULT, full.getType());

        UserAskResultEvent empty = new UserAskResultEvent("r3", null);
        assertNotNull(empty.getAskUserResults());
        assertTrue(empty.getAskUserResults().isEmpty());
    }

    @Test
    void userAskResultEvent_jsonRoundTrip() throws Exception {
        AskUserResult answer = new AskUserResult("call-1", Map.of("q_1", "premium"));
        UserAskResultEvent original = new UserAskResultEvent("r1", List.of(answer));
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("USER_ASK_RESULT"), json);

        AgentEvent back = mapper.readValue(json, AgentEvent.class);
        assertInstanceOf(UserAskResultEvent.class, back);
        UserAskResultEvent decoded = (UserAskResultEvent) back;
        assertEquals(AgentEventType.USER_ASK_RESULT, decoded.getType());
        assertEquals("r1", decoded.getReplyId());
        assertEquals(1, decoded.getAskUserResults().size());
        assertEquals("call-1", decoded.getAskUserResults().get(0).getToolCallId());
        assertEquals("premium", decoded.getAskUserResults().get(0).getAnswers().get("q_1"));
    }

    @Test
    void askUserResult_jsonRoundTripAndGetters() throws Exception {
        AskUserResult original = new AskUserResult("call-1", Map.of("q_1", "premium"));
        assertEquals("call-1", original.getToolCallId());
        assertEquals("premium", original.getAnswers().get("q_1"));
        assertTrue(original.toString().contains("call-1"));

        String json = mapper.writeValueAsString(original);
        AskUserResult back = mapper.readValue(json, AskUserResult.class);
        assertEquals("call-1", back.getToolCallId());
        assertEquals("premium", back.getAnswers().get("q_1"));
    }

    @Test
    void askUserResult_rejectsEmptyToolCallId() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new AskUserResult("", Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new AskUserResult(null, Map.of()));
    }

    @Test
    void formatAnswers_edgeBranches() {
        // List entry that is itself empty.
        assertEquals("q_1: (no answer)", AskUserResult.formatAnswers(Map.of("q_1", List.of())));

        // Map entry with only selected labels (no text).
        assertEquals(
                "q_2: a; b",
                AskUserResult.formatAnswers(Map.of("q_2", Map.of("selected", List.of("a", "b")))));

        // Map entry with only free text (no selected).
        assertEquals(
                "q_3: custom",
                AskUserResult.formatAnswers(Map.of("q_3", Map.of("text", "custom"))));

        // Map entry skipped=false with no payload -> no answer.
        assertEquals(
                "q_4: (no answer)",
                AskUserResult.formatAnswers(
                        Map.of(
                                "q_4",
                                Map.of("selected", List.of(), "text", "", "skipped", false))));

        // Scalar non-string values render via toString.
        assertEquals("q_5: 42", AskUserResult.formatAnswers(Map.of("q_5", 42)));
        assertEquals("q_6: true", AskUserResult.formatAnswers(Map.of("q_6", true)));

        // Nested null-safe: a map whose "selected" holds non-list value degrades to parts text
        // only if present, otherwise "(no answer)".
        assertEquals(
                "q_7: (no answer)", AskUserResult.formatAnswers(Map.of("q_7", Map.of("x", 1))));

        // Multiple entries joined with a newline.
        java.util.Map<String, Object> multi = new java.util.LinkedHashMap<>();
        multi.put("q_a", "1");
        multi.put("q_b", "2");
        assertEquals("q_a: 1\nq_b: 2", AskUserResult.formatAnswers(multi));
    }
}
