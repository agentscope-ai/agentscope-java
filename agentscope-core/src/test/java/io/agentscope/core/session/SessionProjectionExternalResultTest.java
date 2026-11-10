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

import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.MessageUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionProjectionExternalResultTest {
    @Test
    void restoresExternalResultCommittedBeforeStateCheckpoint() {
        var store = new InMemorySessionLogStore();
        var key = new SessionKey("user", "agent", "session");
        var log = store.open(key, null);
        var first =
                ToolUseBlock.builder()
                        .id("a")
                        .name("external")
                        .state(ToolCallState.SUBMITTED)
                        .build();
        var second =
                ToolUseBlock.builder()
                        .id("b")
                        .name("external")
                        .state(ToolCallState.SUBMITTED)
                        .build();
        var request =
                AssistantMessage.builder()
                        .name("agent")
                        .content(first, second)
                        .metadata(Map.of(Msg.METADATA_EXTERNAL_EXECUTION_REQUEST_REPLY_ID, "reply"))
                        .build();
        var state =
                AgentState.builder()
                        .sessionId("session")
                        .userId("user")
                        .context(List.of(request))
                        .build();
        var answer = ToolResultBlock.success("answer A").withIdAndName("a", "external");
        var recorder = new SessionRecorder(log, "turn");
        try {
            recorder.observe(new RequireExternalExecutionEvent("reply", List.of(first, second)));
            recorder.checkpointNow(state, "suspended");
            recorder.observe(new ExternalExecutionResultEvent("reply", List.of(answer)));
            // Commit the answer without a later state checkpoint, as a heartbeat can before a
            // crash.
            recorder.flushNow();
        } finally {
            recorder.close();
        }

        var restarted = store.open(key, null);
        var restored = SessionProjection.read(restarted).restore();
        assertEquals(Set.of("b"), SessionInteractions.pending(restarted).keySet());
        assertEquals(Set.of("b"), MessageUtils.pendingToolUseIds(restored.getContext()));
        assertEquals(
                List.of(answer),
                restored.getContext().stream()
                        .flatMap(
                                message -> message.getContentBlocks(ToolResultBlock.class).stream())
                        .toList());
    }
}
