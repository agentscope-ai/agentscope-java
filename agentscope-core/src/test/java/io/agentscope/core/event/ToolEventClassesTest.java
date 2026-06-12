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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the tool-call / tool-result event POJOs: both constructors,
 * every getter, {@link AgentEvent#getType()} discriminator, and Jackson
 * polymorphic round-trip through the {@link AgentEvent} subtype registry.
 */
class ToolEventClassesTest {

    private static final String REPLY_ID = "reply-1";
    private static final String CALL_ID = "call-1";
    private static final String TOOL_NAME = "search";
    private static final String TOOL_TITLE = "Web Search";
    private static final String DELTA = "{\"q\":\"x\"}";
    private static final String EVENT_ID = "evt-1";
    private static final String CREATED_AT = "2026-01-01T00:00:00Z";

    private final ObjectMapper mapper = new ObjectMapper();

    @Nested
    class ToolCallStartEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ToolCallStartEvent e = new ToolCallStartEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals(AgentEventType.TOOL_CALL_START, e.getType());
            assertNotNull(e.getId());
            assertNotNull(e.getCreatedAt());
        }

        @Test
        void simpleConstructor_acceptsNullTitle() {
            ToolCallStartEvent e = new ToolCallStartEvent(REPLY_ID, CALL_ID, TOOL_NAME, null);
            assertNull(e.getToolCallTitle());
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ToolCallStartEvent e =
                    new ToolCallStartEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
        }

        @Test
        void jacksonRoundTrip_preservesAllFields() throws Exception {
            ToolCallStartEvent original =
                    new ToolCallStartEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            String json = mapper.writeValueAsString(original);
            AgentEvent decoded = mapper.readValue(json, AgentEvent.class);

            assertTrue(decoded instanceof ToolCallStartEvent);
            ToolCallStartEvent r = (ToolCallStartEvent) decoded;
            assertEquals(original.getId(), r.getId());
            assertEquals(original.getCreatedAt(), r.getCreatedAt());
            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
        }
    }

    @Nested
    class ToolCallEndEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ToolCallEndEvent e = new ToolCallEndEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals(AgentEventType.TOOL_CALL_END, e.getType());
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ToolCallEndEvent e =
                    new ToolCallEndEvent(EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, null);

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertNull(e.getToolCallTitle());
        }

        @Test
        void jacksonRoundTrip_preservesAllFields() throws Exception {
            ToolCallEndEvent original =
                    new ToolCallEndEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            String json = mapper.writeValueAsString(original);
            ToolCallEndEvent r = (ToolCallEndEvent) mapper.readValue(json, AgentEvent.class);

            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
        }
    }

    @Nested
    class ToolCallDeltaEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ToolCallDeltaEvent e =
                    new ToolCallDeltaEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, DELTA);

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals(DELTA, e.getDelta());
            assertEquals(AgentEventType.TOOL_CALL_DELTA, e.getType());
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ToolCallDeltaEvent e =
                    new ToolCallDeltaEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, null, DELTA);

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertNull(e.getToolCallTitle());
            assertEquals(DELTA, e.getDelta());
        }

        @Test
        void jacksonRoundTrip_preservesAllFields() throws Exception {
            ToolCallDeltaEvent original =
                    new ToolCallDeltaEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, DELTA);

            String json = mapper.writeValueAsString(original);
            ToolCallDeltaEvent r = (ToolCallDeltaEvent) mapper.readValue(json, AgentEvent.class);

            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
            assertEquals(original.getDelta(), r.getDelta());
        }
    }

    @Nested
    class ToolResultStartEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ToolResultStartEvent e =
                    new ToolResultStartEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals(AgentEventType.TOOL_RESULT_START, e.getType());
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ToolResultStartEvent e =
                    new ToolResultStartEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, null);

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertNull(e.getToolCallTitle());
        }

        @Test
        void jacksonRoundTrip_preservesAllFields() throws Exception {
            ToolResultStartEvent original =
                    new ToolResultStartEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE);

            String json = mapper.writeValueAsString(original);
            ToolResultStartEvent r =
                    (ToolResultStartEvent) mapper.readValue(json, AgentEvent.class);

            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
        }
    }

    @Nested
    class ToolResultEndEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ToolResultEndEvent e =
                    new ToolResultEndEvent(
                            REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, ToolResultState.SUCCESS);

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals(ToolResultState.SUCCESS, e.getState());
            assertEquals(AgentEventType.TOOL_RESULT_END, e.getType());
        }

        @Test
        void simpleConstructor_acceptsAllStateValues() {
            for (ToolResultState state : ToolResultState.values()) {
                ToolResultEndEvent e =
                        new ToolResultEndEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, state);
                assertEquals(state, e.getState());
            }
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ToolResultEndEvent e =
                    new ToolResultEndEvent(
                            EVENT_ID,
                            CREATED_AT,
                            REPLY_ID,
                            CALL_ID,
                            TOOL_NAME,
                            null,
                            ToolResultState.ERROR);

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertNull(e.getToolCallTitle());
            assertEquals(ToolResultState.ERROR, e.getState());
        }

        @Test
        void jacksonRoundTrip_preservesAllFields() throws Exception {
            ToolResultEndEvent original =
                    new ToolResultEndEvent(
                            EVENT_ID,
                            CREATED_AT,
                            REPLY_ID,
                            CALL_ID,
                            TOOL_NAME,
                            TOOL_TITLE,
                            ToolResultState.DENIED);

            String json = mapper.writeValueAsString(original);
            ToolResultEndEvent r = (ToolResultEndEvent) mapper.readValue(json, AgentEvent.class);

            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
            assertEquals(original.getState(), r.getState());
        }
    }

    @Nested
    class ToolResultTextDeltaEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ToolResultTextDeltaEvent e =
                    new ToolResultTextDeltaEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, "hello");

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals("hello", e.getDelta());
            assertEquals(AgentEventType.TOOL_RESULT_TEXT_DELTA, e.getType());
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ToolResultTextDeltaEvent e =
                    new ToolResultTextDeltaEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, null, "chunk");

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertNull(e.getToolCallTitle());
            assertEquals("chunk", e.getDelta());
        }

        @Test
        void jacksonRoundTrip_preservesAllFields() throws Exception {
            ToolResultTextDeltaEvent original =
                    new ToolResultTextDeltaEvent(
                            EVENT_ID,
                            CREATED_AT,
                            REPLY_ID,
                            CALL_ID,
                            TOOL_NAME,
                            TOOL_TITLE,
                            "partial");

            String json = mapper.writeValueAsString(original);
            ToolResultTextDeltaEvent r =
                    (ToolResultTextDeltaEvent) mapper.readValue(json, AgentEvent.class);

            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
            assertEquals(original.getDelta(), r.getDelta());
        }
    }

    @Nested
    class ToolResultDataDeltaEventTests {

        @Test
        void simpleConstructor_populatesFields() {
            ContentBlock data = TextBlock.builder().text("payload").build();
            ToolResultDataDeltaEvent e =
                    new ToolResultDataDeltaEvent(REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, data);

            assertEquals(REPLY_ID, e.getReplyId());
            assertEquals(CALL_ID, e.getToolCallId());
            assertEquals(TOOL_NAME, e.getToolCallName());
            assertEquals(TOOL_TITLE, e.getToolCallTitle());
            assertEquals(data, e.getData());
            assertEquals(AgentEventType.TOOL_RESULT_DATA_DELTA, e.getType());
        }

        @Test
        void jacksonConstructor_setsIdAndTimestamp() {
            ContentBlock data = TextBlock.builder().text("d").build();
            ToolResultDataDeltaEvent e =
                    new ToolResultDataDeltaEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, null, data);

            assertEquals(EVENT_ID, e.getId());
            assertEquals(CREATED_AT, e.getCreatedAt());
            assertNull(e.getToolCallTitle());
            assertEquals(data, e.getData());
        }

        @Test
        void jacksonRoundTrip_preservesScalarFields() throws Exception {
            ContentBlock data = TextBlock.builder().text("payload").build();
            ToolResultDataDeltaEvent original =
                    new ToolResultDataDeltaEvent(
                            EVENT_ID, CREATED_AT, REPLY_ID, CALL_ID, TOOL_NAME, TOOL_TITLE, data);

            String json = mapper.writeValueAsString(original);
            ToolResultDataDeltaEvent r =
                    (ToolResultDataDeltaEvent) mapper.readValue(json, AgentEvent.class);

            assertEquals(original.getId(), r.getId());
            assertEquals(original.getReplyId(), r.getReplyId());
            assertEquals(original.getToolCallId(), r.getToolCallId());
            assertEquals(original.getToolCallName(), r.getToolCallName());
            assertEquals(original.getToolCallTitle(), r.getToolCallTitle());
            assertNotNull(r.getData());
        }
    }
}
