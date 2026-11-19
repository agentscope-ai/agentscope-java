/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parsing of the submission {@code context} into {@link ProtocolTaskContext}: the fields a {@code
 * /resume} needs after the HTTP request is gone, plus the caller identity a task record may report.
 */
class ProtocolTaskContextTest {

    @Test
    void parsesProtocolFieldsFromTheSubmissionContext() {
        ProtocolTaskContext ctx =
                ProtocolTaskContext.from(
                        Map.of(
                                "user_id", "u-1",
                                "parent_session_id", "sess-parent",
                                "parent_agent_id", "planner",
                                "detail", "full"));

        assertEquals("u-1", ctx.userId());
        assertEquals("sess-parent", ctx.parentSessionId());
        assertEquals("planner", ctx.parentAgentId());
        assertEquals("full", ctx.detail());
    }

    @Test
    void detailDefaultsToStatusWhenAbsentOrBlank() {
        assertEquals("status", ProtocolTaskContext.from(Map.of("user_id", "u-1")).detail());
        assertEquals("status", ProtocolTaskContext.from(Map.of("detail", " ")).detail());
    }

    @Test
    void absentCallerIdentityReadsAsNull() {
        ProtocolTaskContext ctx = ProtocolTaskContext.from(Map.of("user_id", "u-1"));

        assertNull(ctx.parentAgentId());
        assertNull(ctx.parentSessionId());
        assertNull(ctx.sessionId());
    }

    @Test
    void emptyContextCarriesNoCallerIdentity() {
        ProtocolTaskContext ctx = ProtocolTaskContext.empty();

        assertNull(ctx.userId());
        assertNull(ctx.parentAgentId());
        assertNull(ctx.parentSessionId());
        assertEquals("status", ctx.detail());
        assertTrue(ctx.raw().isEmpty());
    }

    @Test
    void missingContextMapYieldsTheEmptyContext() {
        ProtocolTaskContext ctx = ProtocolTaskContext.from(null);

        assertNull(ctx.userId());
        assertNull(ctx.parentAgentId());
        assertEquals("status", ctx.detail());
    }

    @Test
    void sessionIdIsDerivedFromTheRawContext() {
        assertEquals("t-1", ProtocolTaskContext.from(Map.of("session_id", "t-1")).sessionId());
    }

    @Test
    void rawIsAnImmutableSnapshotOfTheSubmission() {
        Map<String, Object> submission = new HashMap<>();
        submission.put("user_id", "u-1");
        submission.put("ticket_id", null);

        ProtocolTaskContext ctx = ProtocolTaskContext.from(submission);
        submission.put("user_id", "changed");

        // Copied on construction, so later mutation of the request map cannot rewrite the context.
        assertEquals("u-1", ctx.userId());
        // JSON payloads may carry explicit nulls, which the snapshot preserves.
        assertTrue(ctx.raw().containsKey("ticket_id"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.raw().put("x", "y"));
    }
}
