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
package io.agentscope.extensions.aistio.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextTrackerTest {

    private static final String SESSION = "s-1";

    private static SessionEvent message(String role, String content) {
        return SessionEvent.builder(SESSION, SessionEvent.MESSAGE)
                .role(role)
                .content(content)
                .build();
    }

    @Test
    void messagesAccumulateAndMoveTheHash() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");
        String empty = tracker.getContextHash();

        assertTrue(tracker.onEvent(message(SessionEvent.ROLE_USER, "hello")));
        assertNotEquals(empty, tracker.getContextHash());
        assertEquals(1, tracker.getMessageCount());
        assertEquals(1, tracker.getEffectiveMessageCount());

        String afterFirst = tracker.getContextHash();
        assertTrue(tracker.onEvent(message(SessionEvent.ROLE_ASSISTANT, "hi")));
        assertNotEquals(afterFirst, tracker.getContextHash());
        assertEquals(2, tracker.getMessageCount());
    }

    @Test
    void lifecycleEventsLeaveTheContextUntouched() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");
        String before = tracker.getContextHash();

        assertFalse(
                tracker.onEvent(SessionEvent.builder(SESSION, SessionEvent.SESSION_START).build()));

        assertEquals(before, tracker.getContextHash());
        assertEquals(0, tracker.getEffectiveMessageCount());
    }

    @Test
    void eventsForOtherSessionsAreIgnored() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");

        assertFalse(
                tracker.onEvent(
                        SessionEvent.builder("other", SessionEvent.MESSAGE)
                                .role(SessionEvent.ROLE_USER)
                                .content("not mine")
                                .build()));

        assertEquals(0, tracker.getMessageCount());
    }

    @Test
    void toolCallAndResultEnterTheEffectiveContext() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");

        tracker.onEvent(
                SessionEvent.builder(SESSION, SessionEvent.TOOL_CALL)
                        .role(SessionEvent.ROLE_ASSISTANT)
                        .toolName("search")
                        .build());
        tracker.onEvent(
                SessionEvent.builder(SESSION, SessionEvent.TOOL_RESULT)
                        .role(SessionEvent.ROLE_TOOL)
                        .toolName("search")
                        .toolOutput("42 results")
                        .build());

        List<ContextSnapshot.ContextMessage> messages = tracker.snapshot().getMessages();
        assertEquals(2, messages.size());
        assertEquals("search", messages.get(0).content());
        assertEquals(SessionEvent.ROLE_TOOL, messages.get(1).role());
        assertEquals("42 results", messages.get(1).content());
        // Tool traffic is context, not conversation turns.
        assertEquals(0, tracker.getMessageCount());
    }

    @Test
    void compactionCollapsesTheViewToItsSummary() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");
        tracker.onEvent(message(SessionEvent.ROLE_USER, "one"));
        tracker.onEvent(message(SessionEvent.ROLE_ASSISTANT, "two"));
        tracker.onEvent(message(SessionEvent.ROLE_USER, "three"));

        tracker.onEvent(
                SessionEvent.builder(SESSION, SessionEvent.COMPACTION)
                        .content("summary of the first three turns")
                        .occurredAt(1_700_000_000_000L)
                        .build());

        ContextSnapshot snapshot = tracker.snapshot();
        assertTrue(tracker.isCompacted());
        assertEquals(1, tracker.getEffectiveMessageCount());
        // The full history is still 3; only what the model sees shrank.
        assertEquals(3, tracker.getMessageCount());
        assertEquals(3, snapshot.getOriginalMessageCount());
        assertEquals("summary of the first three turns", snapshot.getCompactionSummary());
        assertTrue(snapshot.getMessages().get(0).isCompaction());
        assertEquals(1_700_000_000_000L, snapshot.getCompactedAt());
    }

    @Test
    void tokensAccumulateAcrossEvents() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");
        tracker.setMaxTokens(1000);

        tracker.onEvent(
                SessionEvent.builder(SESSION, SessionEvent.MESSAGE)
                        .role(SessionEvent.ROLE_ASSISTANT)
                        .content("answer")
                        .tokens(120, 30)
                        .build());
        tracker.onEvent(
                SessionEvent.builder(SESSION, SessionEvent.MESSAGE)
                        .role(SessionEvent.ROLE_ASSISTANT)
                        .content("more")
                        .tokens(80, 20)
                        .build());

        assertEquals(200, tracker.getTokensIn());
        assertEquals(50, tracker.getTokensOut());
        assertEquals(250, tracker.snapshot().getTotalTokens());
        assertEquals(1000, tracker.getMaxTokens());
    }

    @Test
    void identicalContextsHashTheSameAcrossTrackers() {
        ContextTracker one = new ContextTracker(SESSION, "agentscope-java");
        ContextTracker two = new ContextTracker(SESSION, "agentscope-java");
        one.setSystemPrompt("be helpful");
        two.setSystemPrompt("be helpful");

        one.onEvent(message(SessionEvent.ROLE_USER, "hello"));
        two.onEvent(message(SessionEvent.ROLE_USER, "hello"));

        assertEquals(one.getContextHash(), two.getContextHash());
        assertEquals(ContextSnapshot.HASH_LEN, one.getContextHash().length());
    }

    @Test
    void systemPromptAndToolsBelongToTheHashedContext() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");
        tracker.onEvent(message(SessionEvent.ROLE_USER, "hello"));
        String before = tracker.getContextHash();

        tracker.setSystemPrompt("be terse");

        // setSystemPrompt does not itself recompute; the next event picks the change up.
        tracker.onEvent(message(SessionEvent.ROLE_ASSISTANT, "ok"));
        assertNotEquals(before, tracker.getContextHash());
        assertEquals("be terse", tracker.snapshot().getSystemPrompt());
    }

    @Test
    void snapshotSerializesForTheHttpContract() {
        ContextTracker tracker = new ContextTracker(SESSION, "agentscope-java");
        tracker.setTools(
                List.of(
                        new ContextSnapshot.ToolInfo(
                                "search", "find things", Map.of("q", "string"))));
        tracker.onEvent(message(SessionEvent.ROLE_USER, "hello"));

        Map<String, Object> json = tracker.snapshot().toJsonMap();

        assertEquals(SESSION, json.get("sessionId"));
        assertEquals("agentscope-java", json.get("framework"));
        assertTrue(json.containsKey("contextHash"));
        assertEquals(1, ((List<?>) json.get("messages")).size());
        assertEquals(1, ((List<?>) json.get("tools")).size());
    }
}
