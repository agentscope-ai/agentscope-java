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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

class TextOutputDispositionEventTest {

    @Test
    void jsonRoundTripPreservesDispositionAndInheritedContext() {
        TextOutputDispositionEvent original =
                new TextOutputDispositionEvent(
                        "reply-1", TextOutputDisposition.TERMINAL, GenerateReason.MODEL_STOP);
        original.withSource("parent/researcher")
                .withMetadataEntry(AgentEvent.METADATA_TASK_ID, "task-1");

        String json = JsonUtils.getJsonCodec().toJson(original);
        AgentEvent decoded = JsonUtils.getJsonCodec().fromJson(json, AgentEvent.class);

        TextOutputDispositionEvent restored =
                assertInstanceOf(TextOutputDispositionEvent.class, decoded);
        assertEquals(AgentEventType.TEXT_OUTPUT_DISPOSITION, restored.getType());
        assertEquals("reply-1", restored.getReplyId());
        assertEquals(TextOutputDisposition.TERMINAL, restored.getDisposition());
        assertEquals(GenerateReason.MODEL_STOP, restored.getGenerateReason());
        assertEquals("parent/researcher", restored.getSource());
        assertEquals("task-1", restored.getMetadata().get(AgentEvent.METADATA_TASK_ID));
    }

    @Test
    void intermediateDispositionDoesNotClaimGenerateReason() {
        TextOutputDispositionEvent event =
                new TextOutputDispositionEvent("reply-2", TextOutputDisposition.INTERMEDIATE, null);

        assertEquals(TextOutputDisposition.INTERMEDIATE, event.getDisposition());
        assertNull(event.getGenerateReason());
    }
}
