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

package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HitlHandoffRecordSerializationTest {

    @Test
    void roundTripsServerOwnedPendingToolBlocks() {
        ToolUseBlock tool =
                new ToolUseBlock(
                        "call-1",
                        "lookup",
                        Map.of("nested", List.of(Map.of("value", 7))),
                        "{\"nested\":[{\"value\":7}]}",
                        Map.of("prompt", "confirm"),
                        ToolCallState.ASKING);
        HitlHandoffRecord record =
                new HitlHandoffRecord(
                        "handoff-1",
                        "task-1",
                        "context-1",
                        new HitlExecutionKey("alice", "agent", "context-1"),
                        A2aHandoffType.USER_CONFIRM,
                        List.of(tool),
                        "fingerprint",
                        "digest",
                        Instant.parse("2026-07-20T00:00:00Z"),
                        HitlHandoffStatus.OPEN);

        String json = JsonUtils.getJsonCodec().toJson(record);
        HitlHandoffRecord restored =
                JsonUtils.getJsonCodec().fromJson(json, HitlHandoffRecord.class);

        assertEquals(record.handoffId(), restored.handoffId());
        assertEquals(record.executionKey(), restored.executionKey());
        assertEquals(record.type(), restored.type());
        assertEquals(record.tokenDigest(), restored.tokenDigest());
        assertEquals(record.expiresAt(), restored.expiresAt());
        assertEquals(tool.getId(), restored.pendingTools().get(0).getId());
        assertEquals(tool.getName(), restored.pendingTools().get(0).getName());
        assertEquals(tool.getInput(), restored.pendingTools().get(0).getInput());
        assertEquals(tool.getState(), restored.pendingTools().get(0).getState());
    }

    @Test
    void credentialBearingRequestStringsAlwaysRedactTokens() {
        String secret = "plaintext-resume-secret";
        HitlExecutionKey key = new HitlExecutionKey("alice", "agent", "context-1");
        HitlClaimRequest claim = new HitlClaimRequest("task-1", "context-1", "handoff-1", secret);
        HitlCancelRequest cancel =
                new HitlCancelRequest("task-1", "context-1", "handoff-1", secret);
        HitlOpenRequest open =
                new HitlOpenRequest(
                        "task-1",
                        "context-1",
                        key,
                        A2aHandoffType.USER_CONFIRM,
                        List.of(),
                        secret,
                        Duration.ofDays(7),
                        null);
        HitlEncodingContext encoding =
                new HitlEncodingContext(null, key, secret, Duration.ofDays(7), null);

        for (Object request : List.of(claim, cancel, open, encoding)) {
            assertFalse(request.toString().contains(secret));
            assertTrue(request.toString().contains("<redacted>"));
        }
    }
}
